package com.megatrex4;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.philippheuer.events4j.api.domain.IDisposable;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.events.ChannelGoLiveEvent;
import com.github.twitch4j.events.ChannelGoOfflineEvent;
import com.github.twitch4j.helix.domain.Stream;
import com.github.twitch4j.helix.domain.StreamList;
import com.megatrex4.config.Config;
import com.megatrex4.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Twitch implements ModInitializer {
	public static final String MOD_ID = "twitch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static TwitchClient twitchClient;
	private static IDisposable chatListener;
	private static IDisposable goLiveListener;
	private static IDisposable goOfflineListener;
	private static MinecraftServer server;
	/** True when Twitch4J Helix module is available. */
	private static volatile boolean helixEnabled = false;
	/** True when our direct Helix HTTP client can run (clientId+secret or user token). */
	private static volatile boolean helixHttpOk = false;
	/** True when chat module connected (may be anonymous). */
	private static volatile boolean chatEnabled = false;

	/** Channels currently known live (lowercase channel name). */
	private static final Set<String> liveChannels = ConcurrentHashMap.newKeySet();
	/** Stream titles for live channels (lowercase channel → title). */
	private static final Map<String, String> liveTitles = new ConcurrentHashMap<>();
	/** Current Helix stream id while live (lowercase channel → stream id). */
	private static final Map<String, String> liveStreamIds = new ConcurrentHashMap<>();

	/**
	 * Wall-clock millis of the last successful chat announce per channel.
	 * Survives brief offline / player crash so we can apply reannounce cooldown.
	 */
	private static final Map<String, Long> lastAnnounceAtMs = new ConcurrentHashMap<>();
	/** Stream id we last announced for each channel (same-session suppression). */
	private static final Map<String, String> lastAnnouncedStreamId = new ConcurrentHashMap<>();
	/**
	 * Channels announced during the current continuous "known live" period.
	 * Cleared on offline; re-announce still blocked by cooldown / same stream id.
	 */
	private static final Set<String> announcedThisLivePeriod = ConcurrentHashMap.newKeySet();

	private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "twitch-live-poller");
		t.setDaemon(true);
		return t;
	});
	private static ScheduledFuture<?> pollTask;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Twitch Integration Mod...");
		ConfigManager.initialize();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				TwitchCommand.register(dispatcher)
		);

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		// Post-factum: streamer joins MC while already live (or status was missed)
		ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraftServer) -> {
			if (server == null) return;
			Config config = ConfigManager.getConfig();
			if (config.twitch.liveAnnouncement == null || !config.twitch.liveAnnouncement.enabled) return;
			if (!config.twitch.liveAnnouncement.announceOnPlayerJoin) return;

			ServerPlayerEntity player = handler.getPlayer();
			// Yarn/Mojmap: getName() on older, name() on newer GameProfile records
			String playerName = player.getGameProfile().name();

			List<Config.Streamer> matched = new ArrayList<>();
			for (Config.Streamer streamer : config.streamers) {
				if (streamer.requiredPlayerName == null || streamer.requiredPlayerName.isBlank()) continue;
				if (!streamer.requiredPlayerName.equalsIgnoreCase(playerName)) continue;
				if (streamer.channelName == null || streamer.channelName.isBlank()) continue;
				matched.add(streamer);
			}
			if (matched.isEmpty()) return;

			// Fast path: already know live from cache (still subject to cooldown / same stream)
			for (Config.Streamer streamer : matched) {
				String key = streamer.channelName.toLowerCase(Locale.ROOT);
				if (liveChannels.contains(key)) {
					tryAnnounceLive(
							streamer,
							liveTitles.getOrDefault(key, ""),
							liveStreamIds.get(key),
							"player_join"
					);
				}
			}

			// Slow path: force Helix check for this player's channels (post-factum / lag recovery)
			CompletableFuture.runAsync(() -> {
				for (Config.Streamer streamer : matched) {
					try {
						pollSingleChannel(streamer, "player_join_poll");
					} catch (Exception e) {
						if (config.twitch.debug) {
							LOGGER.warn("Join live-check failed for {}: {}", streamer.channelName, e.getMessage());
						}
					}
				}
			});
		});
	}

	private void onServerStarted(MinecraftServer server) {
		Twitch.server = server;
		startTwitchClient();
	}

	private void onServerStopping(MinecraftServer server) {
		LOGGER.info("Shutting down Twitch client...");
		stopPoller();
		disposeListeners();
		if (twitchClient != null) {
			twitchClient.close();
			twitchClient = null;
		}
		clearLiveState();
		// Keep lastAnnounceAtMs / lastAnnouncedStreamId only for this JVM run;
		// full clear on stop is fine (server restart = fresh session).
		lastAnnounceAtMs.clear();
		lastAnnouncedStreamId.clear();
	}

	public static void reload() {
		LOGGER.info("Reloading Twitch Integration config and client...");
		if (server == null) {
			LOGGER.warn("Cannot reload, server is not running yet.");
			return;
		}

		stopPoller();
		disposeListeners();
		if (twitchClient != null) {
			twitchClient.close();
			twitchClient = null;
		}

		// Live cache resets; cooldown maps intentionally KEEP so crash/reload
		// within reannounceCooldownMinutes does not re-spam the server.
		clearLiveState();
		HelixHttp.clearTokenCache();

		ConfigManager.load();
		startTwitchClient();
	}

	private static void clearLiveState() {
		liveChannels.clear();
		liveTitles.clear();
		liveStreamIds.clear();
		announcedThisLivePeriod.clear();
	}

	private static void disposeListeners() {
		if (chatListener != null) {
			chatListener.dispose();
			chatListener = null;
		}
		if (goLiveListener != null) {
			goLiveListener.dispose();
			goLiveListener = null;
		}
		if (goOfflineListener != null) {
			goOfflineListener.dispose();
			goOfflineListener = null;
		}
	}

	private static void stopPoller() {
		if (pollTask != null) {
			pollTask.cancel(false);
			pollTask = null;
		}
	}

	public static void startTwitchClient() {
		Config config = ConfigManager.getConfig();
		String oauthToken = config.twitch.token;

		if (oauthToken == null || oauthToken.equals("oauth:your_token_here") || oauthToken.isBlank()) {
			LOGGER.error("Twitch OAuth token is not configured. Please set it in 'config/twitch.json'. This is not a required field, but if u need a PM feature, u need to set it.(PM to implemented yet, but i think in feature it can be added)");
		}

		if (config.streamers.isEmpty()) {
			LOGGER.warn("No Twitch channels configured to listen to.");
			return;
		}

		// Never crash the Minecraft server if Twitch4J / Helix deps are missing
		try {
			startTwitchClientInternal(config, oauthToken);
		} catch (NoClassDefFoundError | ExceptionInInitializerError e) {
			LOGGER.error(
					"Failed to start Twitch client – missing library on the classpath: {}. " +
							"Helix (live announce / poll) needs commons-logging bundled into the mod jar. " +
							"Add to build.gradle and rebuild:\n" +
							"  implementation \"commons-logging:commons-logging:1.2\"\n" +
							"  include \"commons-logging:commons-logging:1.2\"\n" +
							"Also include twitch4j (+ its transitive deps) with `include` so they ship in the jar.",
					e.getMessage() != null ? e.getMessage() : e.getClass().getName(),
					e
			);
			twitchClient = null;
		} catch (Exception e) {
			LOGGER.error("Failed to start Twitch client. Chat bridge / live announce disabled until fixed.", e);
			twitchClient = null;
		}
	}

	private static void startTwitchClientInternal(Config config, String oauthToken) {
		helixEnabled = false;
		helixHttpOk = HelixHttp.isConfigured();
		chatEnabled = false;

		OAuth2Credential credential = new OAuth2Credential("twitch", oauthToken == null ? "" : oauthToken);

		boolean wantLiveFeatures = config.twitch.liveAnnouncement != null
				&& config.twitch.liveAnnouncement.enabled;

		// Prefer chat-only Twitch4J build (avoids commons-logging crash).
		// Live status uses HelixHttp (plain HTTP) when clientId+secret are set.
		TwitchClientBuilder builder = TwitchClientBuilder.builder()
				.withEnableChat(true)
				.withChatAccount(credential)
				.withDefaultAuthToken(credential);

		if (config.twitch.clientId != null && !config.twitch.clientId.isBlank()) {
			builder.withClientId(config.twitch.clientId);
		}
		if (config.twitch.clientSecret != null && !config.twitch.clientSecret.isBlank()) {
			builder.withClientSecret(config.twitch.clientSecret);
		}

		// Optionally try Twitch4J Helix (events) – may fail without deps
		try {
			TwitchClientBuilder withHelix = TwitchClientBuilder.builder()
					.withEnableChat(true)
					.withEnableHelix(true)
					.withChatAccount(credential)
					.withDefaultAuthToken(credential);
			if (config.twitch.clientId != null && !config.twitch.clientId.isBlank()) {
				withHelix.withClientId(config.twitch.clientId);
			}
			if (config.twitch.clientSecret != null && !config.twitch.clientSecret.isBlank()) {
				withHelix.withClientSecret(config.twitch.clientSecret);
			}
			twitchClient = withHelix.build();
			helixEnabled = isHelixActuallyAvailable();
			if (!helixEnabled) {
				LOGGER.warn("Twitch4J Helix module present but getHelix() returned null.");
			}
		} catch (NoClassDefFoundError e) {
			LOGGER.warn(
					"Twitch4J Helix unavailable ({}). Using chat + direct Helix HTTP for live status.",
					e.getMessage()
			);
			twitchClient = builder.build();
			helixEnabled = false;
		} catch (Exception e) {
			LOGGER.warn("Twitch4J Helix build failed ({}). Falling back to chat + Helix HTTP.", e.getMessage());
			twitchClient = builder.build();
			helixEnabled = false;
		}

		chatEnabled = twitchClient != null && twitchClient.getChat() != null;

		if (!helixHttpOk && !helixEnabled) {
			LOGGER.warn(
					"No Helix path available. Set clientId + clientSecret in config for live polls " +
							"(direct HTTP), or fix Twitch4J Helix deps (commons-logging include)."
			);
		} else if (helixHttpOk) {
			LOGGER.info("Direct Helix HTTP ready (client credentials / user token).");
		}

		for (Config.Streamer streamer : config.streamers) {
			if (streamer.channelName == null || streamer.channelName.isBlank()) continue;

			try {
				twitchClient.getChat().joinChannel(streamer.channelName);
				if (config.twitch.debug) LOGGER.info("Joined Twitch channel: {}", streamer.channelName);
			} catch (Exception e) {
				LOGGER.warn("Failed to join chat channel {}: {}", streamer.channelName, e.getMessage());
			}

			if (wantLiveFeatures && helixEnabled) {
				try {
					twitchClient.getClientHelper().enableStreamEventListener(streamer.channelName);
					if (config.twitch.debug) {
						LOGGER.info("Enabled stream event listener for: {}", streamer.channelName);
					}
				} catch (Exception e) {
					LOGGER.warn("Failed to enable stream event listener for {}: {}",
							streamer.channelName, e.getMessage());
				}
			}
		}

		chatListener = twitchClient.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
			if (server == null) return;

			String channelName = event.getChannel().getName();
			String nickname = event.getUser().getName();
			String message = event.getMessage();

			Config.Streamer streamerInfo = findStreamer(channelName);

			if (!isStreamerOnline(streamerInfo) || isMessageBlacklisted(nickname, message)) {
				return;
			}

			server.execute(() -> {
				Text formattedText = MessageFormatter.format(
						config.twitch.messageFormat,
						nickname,
						message,
						channelName,
						streamerInfo,
						config.twitch,
						server
				);

				server.getPlayerManager().broadcast(formattedText, false);

				if (config.twitch.sendToDiscord) {
					sendToDiscord(nickname, message);
				}
			});
		});

		if (wantLiveFeatures && helixEnabled) {
			goLiveListener = twitchClient.getEventManager().onEvent(ChannelGoLiveEvent.class, event -> {
				String channelName = event.getChannel().getName();
				String title = "";
				String streamId = null;
				if (event.getStream() != null) {
					if (event.getStream().getTitle() != null) {
						title = event.getStream().getTitle();
					}
					streamId = event.getStream().getId();
				}

				markLive(channelName.toLowerCase(Locale.ROOT), title, streamId, "go_live_event");

				Config.Streamer streamer = findStreamer(channelName);
				if (streamer == null) return;

				tryAnnounceLive(streamer, title, streamId, "go_live");
			});

			goOfflineListener = twitchClient.getEventManager().onEvent(ChannelGoOfflineEvent.class, event -> {
				markOffline(event.getChannel().getName().toLowerCase(Locale.ROOT), "go_offline_event");
			});
		}

		// Poller uses HelixHttp (or Twitch4J if available) – works without Twitch4J Helix
		if (wantLiveFeatures && (helixHttpOk || helixEnabled)) {
			startPoller(config);
			LOGGER.info(
					"Twitch client started (chat={}, t4jHelix={}, helixHttp={}).",
					chatEnabled, helixEnabled, helixHttpOk
			);
		} else {
			LOGGER.info(
					"Twitch client started (chat only – no Helix path for live announce)."
			);
		}
	}

	/** Probe whether Helix can be used on the current client. */
	private static boolean isHelixActuallyAvailable() {
		if (twitchClient == null) return false;
		try {
			return twitchClient.getHelix() != null;
		} catch (Exception e) {
			return false;
		}
	}

	// region Status API (used by /twitch status|check)

	public static boolean isHelixEnabled() {
		// Status/check work via HelixHttp even when Twitch4J Helix is off
		return (helixEnabled || helixHttpOk) && (twitchClient != null || helixHttpOk);
	}

	public static boolean isChatEnabled() {
		return chatEnabled && twitchClient != null;
	}

	public static String forcePollNow() {
		if (!helixHttpOk && !helixEnabled) {
			return "§cHelix is not available. Set clientId + clientSecret in config/twitch.json, then /twitch reload.";
		}
		try {
			pollAllLiveStatus("manual_check");
			// Build a clear summary of who is LIVE on Twitch right now
			List<String> liveNames = new ArrayList<>();
			for (StreamerStatus s : getStreamerStatuses()) {
				if (s.twitchLive) {
					String t = (s.title != null && !s.title.isBlank()) ? " §7– §f" + s.title : "";
					liveNames.add("§a" + s.channel + t);
				}
			}
			if (liveNames.isEmpty()) {
				return "§aHelix refreshed. §7No configured streamers are live on Twitch right now.";
			}
			return "§aHelix refreshed. §fLive on Twitch: " + String.join("§7, ", liveNames);
		} catch (Exception e) {
			return "§cHelix poll failed: " + e.getMessage();
		}
	}

	/**
	 * Snapshot of one streamer for /twitch status.
	 */
	public static final class StreamerStatus {
		public final String channel;
		public final String player;
		public final boolean mcOnline;
		public final boolean twitchLive;
		public final String title;
		public final String streamId;
		public final boolean announcedThisPeriod;
		public final Long lastAnnounceMs;

		public StreamerStatus(
				String channel,
				String player,
				boolean mcOnline,
				boolean twitchLive,
				String title,
				String streamId,
				boolean announcedThisPeriod,
				Long lastAnnounceMs
		) {
			this.channel = channel;
			this.player = player;
			this.mcOnline = mcOnline;
			this.twitchLive = twitchLive;
			this.title = title;
			this.streamId = streamId;
			this.announcedThisPeriod = announcedThisPeriod;
			this.lastAnnounceMs = lastAnnounceMs;
		}
	}

	public static List<StreamerStatus> getStreamerStatuses() {
		Config config = ConfigManager.getConfig();
		List<StreamerStatus> out = new ArrayList<>();
		if (server == null) return out;

		for (Config.Streamer s : config.streamers) {
			if (s.channelName == null || s.channelName.isBlank()) continue;
			String key = s.channelName.toLowerCase(Locale.ROOT);
			String player = s.requiredPlayerName == null ? "" : s.requiredPlayerName;
			boolean mcOnline = false;
			if (player != null && !player.isBlank()) {
				mcOnline = server.getPlayerManager().getPlayer(player) != null;
			}
			out.add(new StreamerStatus(
					s.channelName,
					player,
					mcOnline,
					liveChannels.contains(key),
					liveTitles.getOrDefault(key, ""),
					liveStreamIds.get(key),
					announcedThisLivePeriod.contains(key),
					lastAnnounceAtMs.get(key)
			));
		}
		return out;
	}

	// endregion

	private static void startPoller(Config config) {
		stopPoller();
		Config.LiveAnnouncement ann = config.twitch.liveAnnouncement;
		if (ann == null || !ann.enabled || !ann.pollEnabled) {
			if (config.twitch.debug) LOGGER.info("Live status poller disabled.");
			return;
		}

		int interval = Math.max(15, ann.pollIntervalSeconds);
		int initial = Math.max(0, ann.pollInitialDelaySeconds);

		pollTask = scheduler.scheduleAtFixedRate(() -> {
			try {
				pollAllLiveStatus("scheduled_poll");
			} catch (Exception e) {
				LOGGER.warn("Live status poll failed: {}", e.getMessage());
				if (config.twitch.debug) {
					LOGGER.warn("Poll stacktrace", e);
				}
			}
		}, initial, interval, TimeUnit.SECONDS);

		LOGGER.info("Live status poller started (every {}s, first in {}s).", interval, initial);
	}

	/**
	 * Helix batch poll for all configured streamers.
	 * Prefers direct HTTP ({@link HelixHttp}) so we do not depend on Twitch4J Helix.
	 */
	private static void pollAllLiveStatus(String reason) {
		if (server == null) return;

		Config config = ConfigManager.getConfig();
		List<String> logins = new ArrayList<>();
		Map<String, Config.Streamer> byLogin = new HashMap<>();

		for (Config.Streamer streamer : config.streamers) {
			if (streamer.channelName == null || streamer.channelName.isBlank()) continue;
			String login = streamer.channelName.toLowerCase(Locale.ROOT);
			logins.add(login);
			byLogin.put(login, streamer);
		}
		if (logins.isEmpty()) return;

		Map<String, HelixHttp.StreamInfo> liveMap;
		try {
			if (helixHttpOk) {
				liveMap = HelixHttp.getLiveStreams(logins);
			} else if (helixEnabled && twitchClient != null) {
				liveMap = pollViaTwitch4j(logins, bareToken(config.twitch.token));
			} else {
				LOGGER.warn("Helix getStreams skipped ({}) – no Helix path.", reason);
				return;
			}
		} catch (Exception e) {
			LOGGER.warn("Helix getStreams failed ({}): {}", reason, e.getMessage());
			return;
		}

		Set<String> currentlyLive = new HashSet<>(liveMap.keySet());

		for (Map.Entry<String, HelixHttp.StreamInfo> e : liveMap.entrySet()) {
			String login = e.getKey();
			HelixHttp.StreamInfo info = e.getValue();
			boolean wasLive = liveChannels.contains(login);
			markLive(login, info.title, info.id, reason);
			if (!wasLive && config.twitch.debug) {
				LOGGER.info("[Poll] Detected LIVE: {} – \"{}\" id={} ({})", login, info.title, info.id, reason);
			}
			Config.Streamer streamer = byLogin.get(login);
			if (streamer != null) {
				tryAnnounceLive(streamer, info.title, info.id, reason);
			}
		}

		Set<String> known = new HashSet<>(liveChannels);
		for (String login : known) {
			if (!currentlyLive.contains(login) && byLogin.containsKey(login)) {
				markOffline(login, reason);
				if (config.twitch.debug) {
					LOGGER.info("[Poll] Detected OFFLINE: {} ({})", login, reason);
				}
			}
		}

		// Always log manual checks so /twitch check is easy to verify in console
		if (config.twitch.debug || "manual_check".equals(reason)) {
			LOGGER.info("[Poll] {} – watched {} channel(s), LIVE on Twitch: {}",
					reason, logins.size(),
					currentlyLive.isEmpty() ? "(none)" : currentlyLive);
		}
	}

	private static Map<String, HelixHttp.StreamInfo> pollViaTwitch4j(List<String> logins, String token) {
		Map<String, HelixHttp.StreamInfo> live = new HashMap<>();
		StreamList result = twitchClient.getHelix()
				.getStreams(token, null, null, logins.size(), null, null, null, logins)
				.execute();
		if (result != null && result.getStreams() != null) {
			for (Stream stream : result.getStreams()) {
				String login = stream.getUserLogin();
				if (login == null || login.isBlank()) login = stream.getUserName();
				if (login == null) continue;
				login = login.toLowerCase(Locale.ROOT);
				live.put(login, new HelixHttp.StreamInfo(
						stream.getId(),
						stream.getTitle(),
						stream.getUserName(),
						login
				));
			}
		}
		return live;
	}

	/** Helix check for a single channel (used on player join for post-factum announce). */
	private static void pollSingleChannel(Config.Streamer streamer, String reason) {
		if (streamer.channelName == null) return;
		if (!helixHttpOk && !helixEnabled) return;

		String login = streamer.channelName.toLowerCase(Locale.ROOT);
		try {
			Map<String, HelixHttp.StreamInfo> map;
			if (helixHttpOk) {
				map = HelixHttp.getLiveStreams(List.of(login));
			} else {
				map = pollViaTwitch4j(List.of(login), bareToken(ConfigManager.getConfig().twitch.token));
			}
			HelixHttp.StreamInfo info = map.get(login);
			if (info != null) {
				boolean wasLive = liveChannels.contains(login);
				markLive(login, info.title, info.id, reason);
				if (!wasLive) {
					LOGGER.info("[Poll] Post-factum LIVE: {} – \"{}\" id={} ({})", login, info.title, info.id, reason);
				}
				tryAnnounceLive(streamer, info.title, info.id, reason);
			} else {
				if (liveChannels.contains(login)) {
					markOffline(login, reason);
				}
				if (ConfigManager.getConfig().twitch.debug) {
					LOGGER.info("[Poll] {} is not live ({})", login, reason);
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Helix getStreams failed for {} ({}): {}", login, reason, e.getMessage());
		}
	}

	private static void markLive(String key, String title, String streamId, String reason) {
		liveChannels.add(key);
		liveTitles.put(key, title == null ? "" : title);
		if (streamId != null && !streamId.isBlank()) {
			liveStreamIds.put(key, streamId);
		}
		Config config = ConfigManager.getConfig();
		if (config.twitch.debug && "go_live_event".equals(reason)) {
			LOGGER.info("[GoLive] {} is live – title: {} id={}", key, title, streamId);
		}
	}

	/**
	 * Marks channel offline for live-chat bridging state.
	 * Does NOT clear announce cooldown / last stream id – those prevent re-spam
	 * after crash, OBS restart, or brief disconnect within the cooldown window.
	 */
	private static void markOffline(String key, String reason) {
		liveChannels.remove(key);
		liveTitles.remove(key);
		liveStreamIds.remove(key);
		announcedThisLivePeriod.remove(key);
		Config config = ConfigManager.getConfig();
		if (config.twitch.debug) {
			LOGGER.info("[GoOffline] {} went offline ({}) – cooldown/stream-id lock retained", key, reason);
		}
	}

	/** Strip optional {@code oauth:} prefix for Helix Authorization header. */
	private static String bareToken(String token) {
		if (token == null) return null;
		String t = token.trim();
		if (t.regionMatches(true, 0, "oauth:", 0, 6)) {
			return t.substring(6);
		}
		return t;
	}

	/**
	 * Whether we are allowed to post a new live announcement for this channel.
	 * Blocks:
	 * <ul>
	 *   <li>already announced during the current continuous live period</li>
	 *   <li>same Twitch stream id as last announce ({@code suppressSameStreamId})</li>
	 *   <li>within {@code reannounceCooldownMinutes} of the last announce</li>
	 * </ul>
	 */
	private static boolean shouldSuppressAnnounce(String key, String streamId, String reason) {
		Config config = ConfigManager.getConfig();
		Config.LiveAnnouncement ann = config.twitch.liveAnnouncement;
		if (ann == null) return false;

		if (announcedThisLivePeriod.contains(key)) {
			if (config.twitch.debug) {
				LOGGER.info("Skip announce {} – already announced this live period ({})", key, reason);
			}
			return true;
		}

		if (ann.suppressSameStreamId
				&& streamId != null
				&& !streamId.isBlank()
				&& streamId.equals(lastAnnouncedStreamId.get(key))) {
			if (config.twitch.debug) {
				LOGGER.info("Skip announce {} – same stream id {} ({})", key, streamId, reason);
			}
			return true;
		}

		int cooldownMin = Math.max(0, ann.reannounceCooldownMinutes);
		if (cooldownMin > 0) {
			Long last = lastAnnounceAtMs.get(key);
			if (last != null) {
				long elapsedMs = System.currentTimeMillis() - last;
				long cooldownMs = TimeUnit.MINUTES.toMillis(cooldownMin);
				if (elapsedMs < cooldownMs) {
					long leftMin = TimeUnit.MILLISECONDS.toMinutes(cooldownMs - elapsedMs) + 1;
					if (config.twitch.debug) {
						LOGGER.info("Skip announce {} – reannounce cooldown (~{} min left, reason={})",
								key, leftMin, reason);
					}
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Announces that a streamer is live when allowed by cooldown / same-stream rules
	 * and the linked Minecraft player is online.
	 */
	private static void tryAnnounceLive(Config.Streamer streamer, String title, String streamId, String reason) {
		Config config = ConfigManager.getConfig();
		Config.LiveAnnouncement ann = config.twitch.liveAnnouncement;
		if (ann == null || !ann.enabled) return;
		if (server == null) return;
		if (streamer.channelName == null || streamer.channelName.isBlank()) return;

		String key = streamer.channelName.toLowerCase(Locale.ROOT);

		// Prefer live-cached stream id if caller didn't have one
		if ((streamId == null || streamId.isBlank()) && liveStreamIds.containsKey(key)) {
			streamId = liveStreamIds.get(key);
		}

		if (shouldSuppressAnnounce(key, streamId, reason)) {
			return;
		}

		// Require linked MC player online when a name is configured
		String playerName = streamer.requiredPlayerName;
		if (playerName != null && !playerName.isBlank()) {
			ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerName);
			if (online == null) {
				if (config.twitch.debug) {
					LOGGER.info("Skip live announce for {} – player {} is not on the server (reason={})",
							key, playerName, reason);
				}
				return;
			}
			playerName = online.getGameProfile().name();
		} else {
			playerName = streamer.channelName;
		}

		// Lock this live period + record cooldown / stream id BEFORE broadcasting
		announcedThisLivePeriod.add(key);
		lastAnnounceAtMs.put(key, System.currentTimeMillis());
		if (streamId != null && !streamId.isBlank()) {
			lastAnnouncedStreamId.put(key, streamId);
		}

		String displayPlayer = playerName;
		String streamTitle = title == null ? "" : title;

		server.execute(() -> {
			Text message = LiveAnnouncementFormatter.format(
					ann,
					streamer.channelName,
					displayPlayer,
					streamTitle
			);
			server.getPlayerManager().broadcast(message, false);
			LOGGER.info("Live announce for {} (reason={}, streamId={})", key, reason, lastAnnouncedStreamId.get(key));
		});
	}

	private static Config.Streamer findStreamer(String channelName) {
		return ConfigManager.getConfig().streamers.stream()
				.filter(s -> s.channelName != null && s.channelName.equalsIgnoreCase(channelName))
				.findFirst()
				.orElse(null);
	}

	private static boolean isStreamerOnline(Config.Streamer streamerInfo) {
		if (streamerInfo == null) return false;

		if (streamerInfo.requiredPlayerName == null || streamerInfo.requiredPlayerName.isBlank()) {
			return true;
		}

		ServerPlayerEntity player = server.getPlayerManager().getPlayer(streamerInfo.requiredPlayerName);
		return player != null;
	}

	private static boolean isMessageBlacklisted(String username, String message) {
		Config.Blacklist blacklist = ConfigManager.getConfig().blacklist;

		if (blacklist.users.stream().anyMatch(u -> u.equalsIgnoreCase(username))) return true;
		if (message.isEmpty()) return false;

		char firstChar = message.charAt(0);
		if (blacklist.prefixes.stream().anyMatch(p -> !p.isEmpty() && p.charAt(0) == firstChar)) return true;

		return blacklist.words.stream().anyMatch(message::contains);
	}

	public static void sendToDiscord(String nickname, String message) {
		Config config = ConfigManager.getConfig();
		String webhookUrl = config.twitch.discord.webhookUrl;
		if (webhookUrl == null || webhookUrl.isEmpty()) return;

		String avatarUrl = config.twitch.discord.useHeads
				? "https://mc-heads.net/avatar/" + nickname
				: config.twitch.discord.customAvatarUrl;

		String payload = String.format("{\"username\":\"%s\",\"avatar_url\":\"%s\",\"content\":\"%s\"}",
				nickname, avatarUrl, message.replace("\"", "\\\""));

		CompletableFuture.runAsync(() -> {
			try {
				HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
				conn.setRequestMethod("POST");
				conn.setDoOutput(true);
				conn.setRequestProperty("Content-Type", "application/json");

				try (OutputStream out = conn.getOutputStream()) {
					out.write(payload.getBytes());
				}

				int responseCode = conn.getResponseCode();
				if (responseCode < 200 || responseCode > 299) {
					LOGGER.warn("Discord webhook responded with code: " + responseCode);
				}
			} catch (IOException e) {
				LOGGER.warn("Failed to send Discord webhook: " + e.getMessage());
			}
		});
	}
}
