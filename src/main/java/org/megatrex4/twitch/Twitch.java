package org.megatrex4.twitch;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public final class Twitch extends JavaPlugin {

    private TwitchClient twitchClient;
    private File messagesFile;
    private FileConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createMessagesFile();

        PluginCommand command = this.getCommand("twitchlink");
        if (command != null) {
            command.setExecutor(new TwitchCommandExecutor(this));
            command.setTabCompleter(new TwitchCommandCompleter());
        }

        startTwitchClient();
    }

    @Override
    public void onDisable() {
        if (twitchClient != null) {
            twitchClient.close();
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

    private void startTwitchClient() {
        String oauthToken = getConfig().getString("twitch.token");
        List<String> channels = getConfig().getStringList("twitch.channels");

        if (isDebug() && channels.isEmpty()) {
            logMessage("twitch.no_channels");
            return;
        }

        twitchClient = TwitchClientBuilder.builder()
                .withEnableChat(true)
                .withChatAccount(oauthToken == null ? null : new OAuth2Credential("twitch", oauthToken))
                .build();

        for (String channel : channels) {
            String[] parts = channel.split(":");
            String channelName = parts[0].trim();
            twitchClient.getChat().joinChannel(channelName);
        }

        twitchClient.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
            String streamer = event.getChannel().getName();
            String nickname = event.getUser().getName();
            String chatMessage = event.getMessage();

            if (!streamerOnline(streamer)) return;
            if (isBlacklisted(nickname, chatMessage)) return;

            String format = getConfig().getString("twitch.message_format", "[TWITCH] %nickname%: %message%");
            String formattedMessage = format.replace("%nickname%", nickname).replace("%message%", chatMessage);
            formattedMessage = ChatColor.translateAlternateColorCodes('&', formattedMessage);
            Bukkit.broadcastMessage(formattedMessage);

            if (getConfig().getBoolean("twitch.send_to_discord", false)) {
                sendToDiscord(nickname, chatMessage);
            }
        });

        if (isDebug()) {
            logMessage("twitch.connected");
        }
    }

    public boolean streamerOnline(String channel) {
        List<String> channelData = getConfig().getStringList("twitch.channels");
        for (String channelEntry : channelData) {
            String[] parts = channelEntry.split(":");
            if (parts[0].trim().equalsIgnoreCase(channel)) {
                if (parts.length > 1) {
                    Player streamer = getServer().getPlayer(parts[1].trim());
                    return streamer != null && streamer.isOnline();
                }
                return true;
            }
        }
        return false;
    }

    private boolean isBlacklisted(String nickname, String chatMessage) {
        List<String> users = getConfig().getStringList("twitch.blacklist.users");
        List<String> prefixes = getConfig().getStringList("twitch.blacklist.prefixes");
        List<String> words = getConfig().getStringList("twitch.blacklist.words");

        if (users.contains(nickname)) return true;
        if (!chatMessage.isEmpty() && prefixes.contains(String.valueOf(chatMessage.charAt(0)))) return true;

        for (String word : words) {
            if (chatMessage.contains(word)) return true;
        }

        return false;
    }

    public void sendToDiscord(String nickname, String chatMessage) {
        String webhookUrl = getConfig().getString("twitch.discord_webhook_url");
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String avatarUrl = getConfig().getBoolean("twitch.use_heads", true)
                ? "https://mc-heads.net/avatar/" + nickname
                : getConfig().getString("twitch.custom_avatar_url");

        String payload = String.format("{\"username\":\"%s\",\"avatar_url\":\"%s\",\"content\":\"%s\"}",
                nickname, avatarUrl, chatMessage.replace("\"", "\\\""));

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload.getBytes());
            }

            if (isDebug() && conn.getResponseCode() != 204) {
                logMessage("twitch.discord_message_error", conn.getResponseCode());
            }

        } catch (IOException e) {
            if (isDebug()) {
                logMessage("twitch.discord_error", e.getMessage());
            }
        }
    }

    public void addChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        if (!channels.contains(channel)) {
            channels.add(channel);
            getConfig().set("twitch.channels", channels);
            saveConfig();
            if (twitchClient != null) {
                twitchClient.getChat().joinChannel(channel);
            }
        }
    }

    public void removeChannel(String channel) {
        List<String> channels = getConfig().getStringList("twitch.channels");
        channels.removeIf(entry -> entry.split(":")[0].trim().equalsIgnoreCase(channel));
        getConfig().set("twitch.channels", channels);
        saveConfig();
        if (twitchClient != null) {
            twitchClient.getChat().leaveChannel(channel);
        }
    }

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
    }
}
