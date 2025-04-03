package org.megatrex4.twitch;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class Twitch extends JavaPlugin {

    private WebSocketClient webSocketClient;
    private File messagesFile;
    private FileConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createMessagesFile();

        if (getConfig().getStringList("twitch.channels") == null) {
            getConfig().set("twitch.channels", List.of());
            saveConfig();
        }

        PluginCommand command = this.getCommand("twitchlink");
        if (command != null) {
            command.setExecutor(new TwitchCommandExecutor(this));
            command.setTabCompleter(new TwitchCommandCompleter());
        }

        connectToWebSocket();
    }

    @Override
    public void onDisable() {
        logMessage("plugin.disabled");
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    public void createMessagesFile() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMessage(String path) {
        return ChatColor.translateAlternateColorCodes('&', messages.getString(path, "Message not found: " + path));
    }

    public void logMessage(String path) {
        getLogger().info(getMessage(path));
    }

    public void logMessage(String path, Object... replacements) {
        String message = getMessage(path);
        for (int i = 0; i < replacements.length; i++) {
            message = message.replace("%" + i + "%", replacements[i].toString());
        }
        getLogger().info(message);
    }

    private void connectToWebSocket() {
        String oauthToken = getConfig().getString("twitch.token");
        List<String> channels = getConfig().getStringList("twitch.channels");
        List<String> streamers = new ArrayList<>();
        for (String channel : channels) {
            String[] parts = channel.split(":");
            streamers.add(parts[0].trim());
            if (parts.length > 1) {
                streamers.add(parts[1].trim());
            }
        }

        if (streamers.isEmpty()) {
            logMessage("twitch.no_channels");
            return;
        }

        try {
            URI uri = new URI("ws://irc-ws.chat.twitch.tv:80");
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send("PASS " + oauthToken);
                    send("NICK justinfan12345");

                    for (String channel : streamers) {
                        send("JOIN #" + channel);
                    }
                    if (isDebug()){
                    logMessage("twitch.connected");
                    }
                }

                @Override
                public void onMessage(String message) {
                    // Handle PING-PONG for connection keep-alive
                    if (message.startsWith("PING")) {
                        send("PONG :tmi.twitch.tv");
                    }

                    // Check if the message is a PRIVMSG (chat message)
                    if (message.contains("PRIVMSG")) {

                        String streamer = message.split(" ")[2].substring(1); // Get the part after '#', which is the channel name (streamer)
                        if (streamerOnline(streamer)) {
                            // Extract the nickname (sender's Twitch username) from the message
                            String nickname = message.split("!")[0].substring(1);

                            // Extract the chat message (text after " :")
                            String chatMessage = message.split(" :")[1].replaceAll("[\\r\\n]", "");

                            // Optional: If the message is blacklisted, don't process it further
                            if (isBlacklisted(nickname, chatMessage)) return;

                            // Get message format from config (or default to a simple format)
                            String format = getConfig().getString("twitch.message_format", "[TWITCH] %nickname%: %message%");
                            String formattedMessage = format
                                    .replace("%nickname%", nickname)
                                    .replace("%message%", chatMessage);

                            // Convert color codes and send it as a broadcast message
                            formattedMessage = ChatColor.translateAlternateColorCodes('&', formattedMessage);
                            getServer().broadcastMessage(formattedMessage);

                            // Send the message to Discord if enabled
                            if (getConfig().getBoolean("twitch.send_to_discord", false)) {
                                sendToDiscord(nickname, chatMessage);
                            }
                        }
                    }
                }




                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (isDebug()) {
                        logMessage("twitch.disconnected");
                    }
                }

                @Override
                public void onError(Exception ex) {
                    if (isDebug()) {
                        logMessage("twitch.error", ex.getMessage());
                    }
                }
            };

            webSocketClient.connect();
        } catch (URISyntaxException e) {
            if (isDebug()) {
                logMessage("twitch.websocket_error", e.getMessage());
            }
        }
    }

    public boolean streamerOnline(String channel) {
        List<String> channelData = getConfig().getStringList("twitch.channels");
        for (String channelEntry : channelData) {
            String[] parts = channelEntry.split(":");
            if (parts[0].trim().equalsIgnoreCase(channel)) {

                if (parts.length > 1) {
                    String streamerNickname = parts[1].trim();

                    Player streamer = getServer().getPlayer(streamerNickname);
                    if (streamer != null) {
                        boolean isOnline = streamer.isOnline();
                        return isOnline;
                    } else {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }


    private boolean isBlacklisted(String nickname, String chatMessage) {
        List<String> blacklistedUsers = getConfig().getStringList("twitch.blacklist.users");
        List<String> blacklistedPrefixes = getConfig().getStringList("twitch.blacklist.prefixes");
        List<String> blacklistedWords = getConfig().getStringList("twitch.blacklist.words");

        if (blacklistedUsers.contains(nickname)) {
            if (isDebug()){
                logMessage("twitch.blacklist.user", nickname);
        }
            return true;
        }

        if (!chatMessage.isEmpty() && blacklistedPrefixes.contains(String.valueOf(chatMessage.charAt(0)))) {
            if (isDebug()){
                logMessage("twitch.blacklist.prefix", String.valueOf(chatMessage.charAt(0)));
        }
            return true;
        }

        for (String word : blacklistedWords) {
            if (chatMessage.contains(word)) {
                if (isDebug()) {
                    logMessage("twitch.blacklist.word", word);
                }
                return true;
            }
        }

        return false;
    }

    public void addChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        String channelData = channel;  // Channel without nickname

        if (!channels.contains(channelData)) {
            channels.add(channelData);
            getConfig().set("twitch.channels", channels);
            saveConfig();
            if (isDebug()) {
                logMessage("twitch.channel_added", channelData);
            }
            reconnectWebSocket();
        }
    }

    public void addChannel(String channel, String streamerNickname) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        String channelData = channel + ":" + streamerNickname;  // Channel with nickname

        if (!channels.contains(channelData)) {
            channels.add(channelData);
            getConfig().set("twitch.channels", channels);
            saveConfig();
            if (isDebug()) {
                logMessage("twitch.channel_added_with_nickname", channelData);
            }
            reconnectWebSocket();
        }
    }


    public void removeChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");

        // First check if the channel exists in the list
        for (String channelEntry : channels) {
            String[] parts = channelEntry.split(":");

            // If the channel matches and the format is correct
            if (parts[0].trim().equalsIgnoreCase(channel)) {
                // Remove the channel entry with or without nickname
                channels.remove(channelEntry);
                getConfig().set("twitch.channels", channels);
                saveConfig();
                if (isDebug()) {
                    logMessage("twitch.channel_removed", channelEntry);
                }
                reconnectWebSocket();
                return; // Exit after removing the channel
            }
        }

        // If no matching channel is found
        logMessage("twitch.channel_not_found", channel);
    }


    public void reconnectWebSocket() {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
        }
        connectToWebSocket();
    }

    public void sendToDiscord(String nickname, String chatMessage) {
        // Fetch configuration values
        String webhookUrl = getConfig().getString("twitch.discord_webhook_url");
        boolean useHeads = getConfig().getBoolean("twitch.use_heads", true);
        String customAvatarUrl = getConfig().getString("twitch.custom_avatar_url",
                "https://media.discordapp.net/attachments/1029529713519108178/1332347300739027025/w5M5aGW.webp");

        // Validate webhook URL
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logMessage("twitch.discord_webhook_not_set");
            return;
        }

        // Determine avatar URL
        String avatarUrl = useHeads
                ? "https://mc-heads.net/avatar/" + nickname
                : customAvatarUrl;

        // Format the message content
        String content = String.format("%s", chatMessage);

        // Construct the JSON payload
        String payload = String.format("{"
                        + "\"username\": \"%s\","
                        + "\"avatar_url\": \"%s\","
                        + "\"content\": \"%s\""
                        + "}",
                nickname, avatarUrl, content.replace("\"", "\\\""));

        try {
            // Open connection to the webhook URL
            HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            // Send the JSON payload
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload.getBytes());
                outputStream.flush();
            }

            // Handle the response
            int responseCode = connection.getResponseCode();
            if (responseCode == 204) {
                if (isDebug()) {
                    logMessage("twitch.discord_message_sent");
                }
            } else {
                if (isDebug()) {
                    logMessage("twitch.discord_message_error", responseCode);
                }
            }
        } catch (IOException e) {
            if (isDebug()) {
                e.printStackTrace();
            }
            logMessage("twitch.discord_error", e.getMessage());
        }
    }

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
    }
}