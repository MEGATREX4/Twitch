package com.megatrex4.config;

import java.util.ArrayList;
import java.util.List;

public class Config {
    public TwitchSettings twitch = new TwitchSettings();
    public List<Streamer> streamers = new ArrayList<>();
    public Blacklist blacklist = new Blacklist();

    public static class TwitchSettings {
        public boolean debug = false;
        public String token = "oauth:your_token_here";
        /**
         * Optional Twitch application client id (Helix).
         * Recommended for reliable go-live detection via ClientHelper + polling.
         */
        public String clientId = "";
        /**
         * Optional Twitch application client secret (Helix).
         * Used together with {@link #clientId} when set.
         */
        public String clientSecret = "";
        /**
         * Chat message format. Supports color codes with {@code &} and these placeholders:
         * <ul>
         *   <li>{@code %twitch_user%} / {@code <%twitch_user%>} – Twitch chatter name</li>
         *   <li>{@code %twitch_message%} / {@code <%twitch_message%>} – chat message body</li>
         *   <li>{@code %channel%} / {@code <%channel%>} – Twitch channel name</li>
         *   <li>{@code %player%} / {@code <%player%>} – Minecraft player head (Player Object Type)
         *       of the streamer's linked {@code requiredPlayerName}. Hover text uses
         *       {@link #playerHoverFormat} (or the streamer's own {@code hoverText}).</li>
         * </ul>
         */
        public String messageFormat = "<&5[TWITCH]&r> <%player%> <%twitch_user%>: %twitch_message%";
        /**
         * Hover tooltip shown on the {@code <%player%>} head icon.
         * Supports {@code &} color codes and placeholders:
         * {@code %channel%}, {@code %player%}, {@code %twitch_user%}.
         * Overridden per-streamer when {@link Streamer#hoverText} is set.
         */
        public String playerHoverFormat = "&dStreamer: &f%channel%";
        /** Whether the player-head object should render the hat layer. */
        public boolean playerHeadHat = true;
        public boolean sendToDiscord = false;
        public DiscordSettings discord = new DiscordSettings();
        /** Server chat announcement when a streamer goes live (and is on the MC server). */
        public LiveAnnouncement liveAnnouncement = new LiveAnnouncement();
    }

    public static class LiveAnnouncement {
        /** Master switch for go-live chat announcements. */
        public boolean enabled = true;
        /**
         * Announcement format. Supports {@code &} color codes and:
         * <ul>
         *   <li>{@code %streamer%} / {@code %channel%} – Twitch channel name</li>
         *   <li>{@code %player%} – linked Minecraft name</li>
         *   <li>{@code %title%} – stream title (may be empty)</li>
         *   <li>{@code %url%} – raw https://twitch.tv/channel text</li>
         *   <li>{@code %link%} – purple clickable link (see {@link #linkText})</li>
         * </ul>
         */
        public String format = "&d%player% &fis live on &5%link%&f!";
        /**
         * Visible text of the clickable {@code %link%} part.
         * Placeholders: {@code %channel%}, {@code %streamer%}, {@code %player%}, {@code %url%}.
         */
        public String linkText = "twitch.tv/%channel%";
        /**
         * Hover tooltip on the purple link.
         * Placeholders: {@code %channel%}, {@code %streamer%}, {@code %player%}, {@code %url%}, {@code %title%}.
         */
        public String linkHover = "&dOpen stream&r\n&7%url%";
        /**
         * If true, also announce when the linked MC player joins the server
         * while their Twitch channel is already live (not only on ChannelGoLiveEvent).
         * On join we also force a Helix status check so post-factum lives are detected.
         */
        public boolean announceOnPlayerJoin = true;
        /**
         * Periodic Helix poll of stream status for all configured channels.
         * Catches missed go-live events when ClientHelper / API lags.
         * Set to {@code 0} to disable polling (events only).
         */
        public boolean pollEnabled = true;
        /**
         * Seconds between Helix live-status polls for all streamers.
         * Default 60. Minimum useful value is ~15 (Twitch rate limits apply).
         */
        public int pollIntervalSeconds = 60;
        /**
         * Delay in seconds before the first poll after client start / reload.
         * Lets chat + ClientHelper settle; also surfaces already-live channels quickly.
         */
        public int pollInitialDelaySeconds = 10;
        /**
         * After announcing a streamer, do not announce them again for this many minutes
         * (even if the stream drops, the player crashes, or they rejoin the MC server).
         * Covers brief disconnects / OBS restarts. Default {@code 60} (1 hour).
         * Set to {@code 0} to allow re-announce as soon as a new live session is detected
         * (same-stream-id suppression still applies when {@link #suppressSameStreamId} is true).
         */
        public int reannounceCooldownMinutes = 60;
        /**
         * If true, never re-announce the same Twitch stream id (same broadcast session),
         * even after the cooldown expires. A brand-new stream gets a new id and can announce.
         */
        public boolean suppressSameStreamId = true;
    }

    public static class DiscordSettings {
        public boolean useHeads = true;
        public String webhookUrl = "";
        public String customAvatarUrl = "";
    }

    public static class Streamer {
        public String channelName;
        public String requiredPlayerName; // Can be null or empty
        /**
         * Optional hover tooltip for this streamer's {@code <%player%>} icon.
         * When null/blank, {@link TwitchSettings#playerHoverFormat} is used.
         * Supports {@code &} color codes and placeholders:
         * {@code %channel%}, {@code %player%}, {@code %twitch_user%}.
         */
        public String hoverText;

        // Empty constructor for Gson
        public Streamer() {
        }

        public Streamer(String channelName, String requiredPlayerName) {
            this.channelName = channelName;
            this.requiredPlayerName = requiredPlayerName;
        }

        public Streamer(String channelName, String requiredPlayerName, String hoverText) {
            this.channelName = channelName;
            this.requiredPlayerName = requiredPlayerName;
            this.hoverText = hoverText;
        }
    }

    public static class Blacklist {
        public List<String> users = new ArrayList<>();
        public List<String> prefixes = List.of("!");
        public List<String> words = new ArrayList<>();
    }
}
