package org.megatrex4.twitch;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.List;

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

        if (channels.isEmpty()) {
            logMessage("twitch.no_channels");
            return;
        }

        try {
            URI uri = new URI("wss://irc-ws.chat.twitch.tv:443");
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    send("PASS " + oauthToken);
                    send("NICK justinfan12345");

                    for (String channel : channels) {
                        send("JOIN #" + channel);
                    }

                    logMessage("twitch.connected");
                }

                @Override
                public void onMessage(String message) {
                    if (message.startsWith("PING")) {
                        send("PONG :tmi.twitch.tv");
                    }

                    if (message.contains("PRIVMSG")) {
                        String nickname = message.split("!")[0].substring(1);
                        String chatMessage = message.split(" :")[1].replaceAll("[\\r\\n]", "");

                        if (isBlacklisted(nickname, chatMessage)) return;

                        String format = getConfig().getString("twitch.message_format", "[TWITCH] %nickname%: %message%");
                        String formattedMessage = format
                                .replace("%nickname%", nickname)
                                .replace("%message%", chatMessage);

                        formattedMessage = ChatColor.translateAlternateColorCodes('&', formattedMessage);
                        getServer().broadcastMessage(formattedMessage);

                        // Send to Discord if enabled
                        if (getConfig().getBoolean("twitch.send_to_discord", false)) {
                            sendToDiscord(nickname, chatMessage);
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    logMessage("twitch.disconnected");
                }

                @Override
                public void onError(Exception ex) {
                    logMessage("twitch.error", ex.getMessage());
                }
            };

            webSocketClient.connect();
        } catch (URISyntaxException e) {
            logMessage("twitch.websocket_error", e.getMessage());
        }
    }

    private boolean isBlacklisted(String nickname, String chatMessage) {
        List<String> blacklistedUsers = getConfig().getStringList("twitch.blacklist.users");
        List<String> blacklistedPrefixes = getConfig().getStringList("twitch.blacklist.prefixes");
        List<String> blacklistedWords = getConfig().getStringList("twitch.blacklist.words");

        if (blacklistedUsers.contains(nickname)) {
            logMessage("twitch.blacklist.user", nickname);
            return true;
        }

        if (!chatMessage.isEmpty() && blacklistedPrefixes.contains(String.valueOf(chatMessage.charAt(0)))) {
            logMessage("twitch.blacklist.prefix", String.valueOf(chatMessage.charAt(0)));
            return true;
        }

        for (String word : blacklistedWords) {
            if (chatMessage.contains(word)) {
                logMessage("twitch.blacklist.word", word);
                return true;
            }
        }

        return false;
    }

    public void addChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        if (!channels.contains(channel)) {
            channels.add(channel);
            getConfig().set("twitch.channels", channels);
            saveConfig();
            logMessage("twitch.channel_added", channel);
        }
    }

    public void removeChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        if (channels.contains(channel)) {
            channels.remove(channel);
            getConfig().set("twitch.channels", channels);
            saveConfig();
            logMessage("twitch.channel_removed", channel);
        }
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
                logMessage("twitch.discord_message_sent");
            } else {
                logMessage("twitch.discord_message_error", responseCode);
            }
        } catch (IOException e) {
            logMessage("twitch.discord_error", e.getMessage());
        }
    }




}
