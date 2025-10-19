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
        public String messageFormat = "<&5[TWITCH]&r> <%twitch_user%>: %twitch_message%";
        public boolean sendToDiscord = false;
        public DiscordSettings discord = new DiscordSettings();
    }

    public static class DiscordSettings {
        public boolean useHeads = true;
        public String webhookUrl = "";
        public String customAvatarUrl = "";
    }

    public static class Streamer {
        public String channelName;
        public String requiredPlayerName; // Can be null or empty

        // Add this empty constructor for Gson to use when loading the config file
        public Streamer() {
        }

        public Streamer(String channelName, String requiredPlayerName) {
            this.channelName = channelName;
            this.requiredPlayerName = requiredPlayerName;
        }
    }


    public static class Blacklist {
        public List<String> users = new ArrayList<>();
        public List<String> prefixes = List.of("!");
        public List<String> words = new ArrayList<>();
    }
}