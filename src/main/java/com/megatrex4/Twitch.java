package com.megatrex4;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.philippheuer.events4j.api.domain.IDisposable;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.megatrex4.config.Config;
import com.megatrex4.config.ConfigManager;
import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.TextParserUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class Twitch implements ModInitializer {
	public static final String MOD_ID = "twitch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static TwitchClient twitchClient;
	private static IDisposable chatListener;
	private static MinecraftServer server;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Twitch Integration Mod...");
		ConfigManager.initialize();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				TwitchCommand.register(dispatcher)
		);

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
	}

	private void onServerStarted(MinecraftServer server) {
		Twitch.server = server;
		startTwitchClient();
	}

	private void onServerStopping(MinecraftServer server) {
		LOGGER.info("Shutting down Twitch client...");
		if (chatListener != null) {
			chatListener.dispose();
			chatListener = null;
		}
		if (twitchClient != null) {
			twitchClient.close();
			twitchClient = null;
		}
	}

	public static void reload() {
		LOGGER.info("Reloading Twitch Integration config and client...");
		if (server == null) {
			LOGGER.warn("Cannot reload, server is not running yet.");
			return;
		}

		if (chatListener != null) chatListener.dispose();
		if (twitchClient != null) twitchClient.close();

		ConfigManager.load();
		startTwitchClient();
	}

	public static void startTwitchClient() {
		Config config = ConfigManager.getConfig();
		String oauthToken = config.twitch.token;

		if (oauthToken == null || oauthToken.equals("oauth:your_token_here") || oauthToken.isBlank()) {
			LOGGER.error("Twitch OAuth token is not configured. Please set it in 'config/twitch.json'.");
			return;
		}

		if (config.streamers.isEmpty()) {
			LOGGER.warn("No Twitch channels configured to listen to.");
			return;
		}

		twitchClient = TwitchClientBuilder.builder()
				.withEnableChat(true)
				.withChatAccount(new OAuth2Credential("twitch", oauthToken))
				.build();

		for (Config.Streamer streamer : config.streamers) {
			twitchClient.getChat().joinChannel(streamer.channelName);
			if (config.twitch.debug) LOGGER.info("Joined Twitch channel: {}", streamer.channelName);
		}

		chatListener = twitchClient.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
			if (server == null) return;

			String channelName = event.getChannel().getName();
			String nickname = event.getUser().getName();
			String message = event.getMessage();

			if (!isStreamerOnline(channelName) || isMessageBlacklisted(nickname, message)) {
				return;
			}

			server.execute(() -> {
				// Replace placeholders in the format string
				String formattedMessage = config.twitch.messageFormat
						.replace("%twitch_user%", nickname)
						.replace("%twitch_message%", message);

				// Parse color codes and any server placeholders
				Text formattedText = parseFormattedText(formattedMessage);

				server.getPlayerManager().broadcast(formattedText, false);

				if (config.twitch.sendToDiscord) {
					sendToDiscord(nickname, message);
				}
			});
		});

		LOGGER.info("Twitch client started and connected to channels.");
	}

	/**
	 * Parses text with color codes and PlaceholderAPI support
	 */
	private static Text parseFormattedText(String text) {
		// First, convert color codes (& to §)
		String colorParsed = text.replace("&", "§");

		// Convert to Text object
		Text baseText = Text.literal(colorParsed);

		// If there are any server placeholders (like %server:name%), parse them
		if (text.contains("%") && text.indexOf('%', text.indexOf('%') + 1) != -1) {
			PlaceholderContext context = PlaceholderContext.of(server);
			return Placeholders.parseText(baseText, context);
		}

		return baseText;
	}

	private static boolean isStreamerOnline(String channelName) {
		Config.Streamer streamerInfo = ConfigManager.getConfig().streamers.stream()
				.filter(s -> s.channelName.equalsIgnoreCase(channelName))
				.findFirst().orElse(null);

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