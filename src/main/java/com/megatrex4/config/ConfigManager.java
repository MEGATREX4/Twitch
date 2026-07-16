package com.megatrex4.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.megatrex4.Twitch;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;
    private static Config config;

    public static void initialize() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve(Twitch.MOD_ID + ".json").toFile();
        load();
    }

    public static void load() {
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, Config.class);
                if (config == null) {
                    Twitch.LOGGER.warn("Config file was empty or malformed. Creating a new one.");
                    createNewConfig();
                } else {
                    sanitize(config);
                }
            } catch (JsonSyntaxException e) {
                // Never crash the whole server on a bad config edit
                Twitch.LOGGER.error(
                        "Invalid JSON in config/twitch.json ({}). Using defaults until you fix the file. " +
                                "Common issues: missing comma, extra '}}', trailing comma, unquoted text.",
                        e.getMessage()
                );
                if (config == null) {
                    createNewConfig();
                } else {
                    Twitch.LOGGER.warn("Keeping previously loaded config in memory.");
                }
            } catch (IOException e) {
                Twitch.LOGGER.error("Failed to read config file, using default values.", e);
                createNewConfig();
            }
        } else {
            Twitch.LOGGER.info("No config file found, creating a new one.");
            createNewConfig();
        }
    }

    /** Fill null nested objects after loading older or partial configs. */
    private static void sanitize(Config c) {
        if (c.twitch == null) c.twitch = new Config.TwitchSettings();
        if (c.streamers == null) c.streamers = new java.util.ArrayList<>();
        if (c.blacklist == null) c.blacklist = new Config.Blacklist();
        if (c.twitch.discord == null) c.twitch.discord = new Config.DiscordSettings();
        if (c.twitch.liveAnnouncement == null) c.twitch.liveAnnouncement = new Config.LiveAnnouncement();
        if (c.twitch.playerHoverFormat == null) {
            c.twitch.playerHoverFormat = "&dStreamer: &f%channel%";
        }
        if (c.twitch.messageFormat == null) {
            c.twitch.messageFormat = "<&5[TWITCH]&r> <%player%> <%twitch_user%>: %twitch_message%";
        }
        if (c.blacklist.users == null) c.blacklist.users = new java.util.ArrayList<>();
        if (c.blacklist.prefixes == null) c.blacklist.prefixes = new java.util.ArrayList<>(java.util.List.of("!"));
        if (c.blacklist.words == null) c.blacklist.words = new java.util.ArrayList<>();
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            Twitch.LOGGER.error("Failed to save config file.", e);
        }
    }

    private static void createNewConfig() {
        config = new Config();
        sanitize(config);
        // Example streamers – one with a linked MC name + custom hover
        config.streamers.add(new Config.Streamer(
                "your_twitch_channel",
                "your_minecraft_name",
                "&dLive now on &f%channel%"
        ));
        config.streamers.add(new Config.Streamer("another_twitch_channel", null));
        config.blacklist.users.add("nightbot");
        save();
    }

    public static Config getConfig() {
        if (config == null) {
            initialize();
        }
        return config;
    }
}
