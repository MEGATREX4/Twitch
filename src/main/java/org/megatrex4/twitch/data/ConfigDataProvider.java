package org.megatrex4.twitch.data;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class ConfigDataProvider implements DataProvider {

    private final List<String> users;
    private final List<String> prefixes;
    private final List<String> words;

    public ConfigDataProvider(FileConfiguration config) {
        users = config.getStringList("twitch.blacklist.users");
        prefixes = config.getStringList("twitch.blacklist.prefixes");
        words = config.getStringList("twitch.blacklist.words");
    }

    @Override
    public boolean isUserBlacklisted(String username) {
        return users.contains(username);
    }

    @Override
    public boolean isMessageBlacklisted(String message) {
        if (message.isEmpty()) return false;
        for (String p : prefixes) {
            if (message.charAt(0) == p.charAt(0)) return true;
        }
        for (String w : words) {
            if (message.contains(w)) return true;
        }
        return false;
    }
}
