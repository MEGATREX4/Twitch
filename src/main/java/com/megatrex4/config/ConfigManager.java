package com.megatrex4.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    public static void save() {
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            Twitch.LOGGER.error("Failed to save config file.", e);
        }
    }

    private static void createNewConfig() {
        config = new Config();
        // Add some default example data
        config.streamers.add(new Config.Streamer("your_twitch_channel", "your_minecraft_name"));
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
