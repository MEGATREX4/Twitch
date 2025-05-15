package org.megatrex4.twitch;

import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.philippheuer.events4j.api.domain.IDisposable;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.megatrex4.twitch.data.ConfigDataProvider;
import org.megatrex4.twitch.data.DataProvider;

import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.megatrex4.twitch.data.SqlDataProvider;
import org.megatrex4.twitch.db.DatabaseManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

public final class Twitch extends JavaPlugin {

    private TwitchClient twitchClient;
    private File messagesFile;
    private FileConfiguration messages;
    private IDisposable chatListener;
    private DataProvider dataProvider;
    private DatabaseManager databaseManager;

    private volatile boolean isDisabling = false;

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createMessagesFile();
        syncMessagesFile();

        PluginCommand command = getCommand("twitchlink");
        if (command != null) {
            command.setExecutor(new TwitchCommandExecutor(this));
            command.setTabCompleter(new TwitchCommandCompleter(this));
        }

        connectDatabase();
        startTwitchClient();
    }

    private void connectDatabase() {
        if (getConfig().getBoolean("sql.enabled", false)) {
            try {
                String jdbc = getConfig().getString("sql.jdbc");
                if (jdbc == null || jdbc.isBlank()) {
                    getLogger().severe(getConfig().getString("twitch.db_failed") + " Missing 'sql.jdbc' in config.yml");
                }

                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(jdbc);
                hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
                hikariConfig.setMaximumPoolSize(5);
                hikariConfig.setMinimumIdle(1);
                hikariConfig.setIdleTimeout(30000);
                hikariConfig.setMaxLifetime(1800000);
                hikariConfig.setConnectionTimeout(10000);
                hikariConfig.setLeakDetectionThreshold(2000);
                hikariConfig.setAutoCommit(true);

                HikariDataSource hikari = new HikariDataSource(hikariConfig);
                databaseManager = new DatabaseManager(hikari);
                databaseManager.initializeSchema();
                dataProvider = new SqlDataProvider(hikari);

                getLogger().info("twitch.db_connected");
            } catch (Exception e) {
                getLogger().severe(getConfig().getString("twitch.db_error") + " " + e.getMessage());
                getServer().getPluginManager().disablePlugin(this);
            }
        } else {
            dataProvider = new ConfigDataProvider(getConfig());
        }
    }

    @Override
    public void onDisable() {
        isDisabling = true;

        if (chatListener != null) {
            chatListener.dispose();  // <== this unregisters the listener safely
            chatListener = null;
        }

        if (twitchClient != null) {
            twitchClient.close();
            twitchClient = null;
        }
    }

    public void createMessagesFile() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void syncMessagesFile() {
        File file = new File(getDataFolder(), "messages.yml");
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(getResource("messages.yml")));


        boolean updated = false;

        for (String key : defaults.getKeys(true)) {
            if (!current.contains(key)) {
                current.set(key, defaults.get(key));
                updated = true;
            }
        }

        if (updated) {
            try {
                current.save(file);
                getLogger().severe(getConfig().getString("twitch.msg_update"));
            } catch (IOException e) {
                getLogger().severe(getConfig().getString("twitch.msg_error") + " " + e.getMessage());
            }
        }

        messages = current;
    }


    public String getMessage(String path) {
        return ChatColor.translateAlternateColorCodes('&', messages.getString(path, "Message not found: " + path));
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
        List<String> channels;

        if (getConfig().getBoolean("sql.enabled", false)) {
            try {
                channels = databaseManager.fetchColumn("SELECT channel FROM twitch_streamers");
            } catch (Exception e) {
                getLogger().severe("Failed to fetch channels from database: " + e.getMessage());
                return;
            }
        } else {
            channels = getConfig().getStringList("twitch.channels");
        }

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
            twitchClient.getChat().joinChannel(parts[0].trim());
        }

        chatListener = twitchClient.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
            if (!isEnabled() || isDisabling) return;

            String streamer = event.getChannel().getName();
            String nickname = event.getUser().getName();
            String chatMessage = event.getMessage();

            if (!streamerOnlineSafe(streamer)) return;
            if (isBlacklisted(nickname, chatMessage)) return;

            String format = getConfig().getString("twitch.message_format", "[TWITCH] %nickname%: %message%");
            String formattedMessage = ChatColor.translateAlternateColorCodes('&',
                    format.replace("%nickname%", nickname).replace("%message%", chatMessage));

            Bukkit.getScheduler().runTask(this, () -> {
                if (!isDisabling && isEnabled()) {
                    Bukkit.broadcastMessage(formattedMessage);
                }
            });

            if (getConfig().getBoolean("twitch.send_to_discord", false)) {
                sendToDiscord(nickname, chatMessage);
            }
        });

        if (isDebug()) {
            logMessage("twitch.connected");
        }
    }

    public boolean streamerOnlineSafe(String channel) {
        if (!isEnabled()) return false;

        if (getConfig().getBoolean("sql.enabled", false)) {
            try (Connection conn = databaseManager.getDataSource().getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT channel, player_name FROM twitch_streamers");
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    String dbChannel = rs.getString("channel");
                    String playerName = rs.getString("player_name");

                    if (dbChannel.equalsIgnoreCase(channel)) {
                        if (playerName != null && !playerName.isBlank()) {
                            Player player = Bukkit.getPlayerExact(playerName.trim());
                            return player != null && player.isOnline();
                        }
                        return true;
                    }
                }

            } catch (Exception e) {
                getLogger().warning("Failed to check online streamer: " + e.getMessage());
            }
        } else {
            List<String> entries = getConfig().getStringList("twitch.channels");
            for (String entry : entries) {
                String[] parts = entry.split(":");
                if (parts[0].equalsIgnoreCase(channel)) {
                    if (parts.length > 1) {
                        Player player = Bukkit.getPlayerExact(parts[1].trim());
                        return player != null && player.isOnline();
                    }
                    return true;
                }
            }
        }

        return false;
    }



    private boolean isBlacklisted(String nickname, String chatMessage) {
        return dataProvider.isUserBlacklisted(nickname) || dataProvider.isMessageBlacklisted(chatMessage);
    }


    public void sendToDiscord(String nickname, String chatMessage) {
        if (!isEnabled() || isDisabling) return;

        String webhookUrl = getConfig().getString("twitch.discord_webhook_url");
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String avatarUrl = getConfig().getBoolean("twitch.use_heads", true)
                ? "https://mc-heads.net/avatar/" + nickname
                : getConfig().getString("twitch.custom_avatar_url");

        String payload = String.format("{\"username\":\"%s\",\"avatar_url\":\"%s\",\"content\":\"%s\"}",
                nickname, avatarUrl, chatMessage.replace("\"", "\\\""));

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!isEnabled() || isDisabling) return;

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload.getBytes());
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != 204) {
                    getLogger().warning("Discord webhook responded with code: " + responseCode);
                }

            } catch (IOException e) {
                getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
            }
        });
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
