package org.megatrex4.twitch;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

public final class Twitch extends JavaPlugin {

    private WebSocketClient webSocketClient;

    @Override
    public void onEnable() {
        // Initialize config if it's empty
        saveDefaultConfig();
        if (getConfig().getStringList("twitch.channels") == null) {
            getConfig().set("twitch.channels", List.of());  // Default empty list
            saveConfig();
        }

        // Get the command
        this.getCommand("twitchlink").setExecutor(new TwitchCommandExecutor(this));
        this.getCommand("twitchlink").setTabCompleter(new TwitchCommandCompleter());

        // Connect to WebSocket
        connectToWebSocket();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("Twitch plugin disabled!");
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    private void connectToWebSocket() {
        String oauthToken = getConfig().getString("twitch.token"); // OAuth token from config
        List<String> channels = getConfig().getStringList("twitch.channels"); // Channels from config

        if (channels.isEmpty()) {
            getLogger().warning("No Twitch channels are configured.");
            return;
        }

        try {
            URI uri = new URI("wss://irc-ws.chat.twitch.tv:443");
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    // Send the IRC authentication message to connect to the channels
                    send("PASS " + oauthToken);
                    send("NICK justinfan12345"); // This is a temporary username for anonymous chat

                    // Join all configured channels
                    for (String channel : channels) {
                        send("JOIN #" + channel);
                    }

                    getLogger().info("Connected to Twitch IRC server.");
                }

                @Override
                public void onMessage(String message) {
                    // Look for the IRC message prefix (e.g., ":tmi.twitch.tv PRIVMSG #your_channel :Hello!"))

                    if (message.startsWith("PING")) {
                        send("PONG :tmi.twitch.tv");
                        getLogger().info("Responded to PING with PONG.");
                    }

                    if (message.contains("PRIVMSG")) {
                        // Split message to get the nickname and the actual chat message
                        String nickname = message.split("!")[0].substring(1); // Extract nickname before the '!' character
                        String chatMessage = message.split(" :")[1]; // Extract the actual chat message after the ':'

                        // Clean the chat message by removing any '\r\n' (carriage return and newline characters)
                        chatMessage = chatMessage.replaceAll("\r\n", "").replaceAll("\n", "").replaceAll("\r", "");

                        // Get prefix and message format from config
                        String format = getConfig().getString("twitch.message_format", "[TWITCH] %nickname%: %message%");
                        String formattedMessage = format
                                .replace("%nickname%", nickname)
                                .replace("%message%", chatMessage);

                        // Translate color codes (e.g., &7 to gray)
                        formattedMessage = ChatColor.translateAlternateColorCodes('&', formattedMessage);

                        // Broadcast formatted message
                        getServer().broadcastMessage(formattedMessage);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    getLogger().info("Disconnected from Twitch IRC server.");
                }

                @Override
                public void onError(Exception ex) {
                    getLogger().severe("Error with Twitch WebSocket connection: " + ex.getMessage());
                }
            };

            webSocketClient.connect();
        } catch (URISyntaxException e) {
            getLogger().severe("Error creating WebSocket connection: " + e.getMessage());
        }
    }

    // Utility function to send messages to the Twitch IRC server
    private void send(String message) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.send(message);
        }
    }

    // Add channel to config
    public void addChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        if (!channels.contains(channel)) {
            channels.add(channel);
            getConfig().set("twitch.channels", channels);
            saveConfig();
            getLogger().info("Added channel: " + channel);
        }
    }

    // Remove channel from config
    public void removeChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        if (channels.contains(channel)) {
            channels.remove(channel);
            getConfig().set("twitch.channels", channels);
            saveConfig();
            getLogger().info("Removed channel: " + channel);
        }
    }
}
