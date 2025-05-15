package org.megatrex4.twitch;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.megatrex4.twitch.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TwitchCommandCompleter implements TabCompleter {

    private final Twitch plugin;

    public TwitchCommandCompleter(Twitch plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(List.of("add", "remove", "reload", "blacklist"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("blacklist")) {
            completions.addAll(List.of("add", "remove"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("blacklist")) {
            completions.addAll(List.of("word", "prefix", "user"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("blacklist")) {
            // optionally suggest existing words/prefixes/users if removing
            if (args[1].equalsIgnoreCase("remove") && plugin.getConfig().getBoolean("sql.enabled", false)) {
                try {
                    switch (args[2]) {
                        case "word" -> completions.addAll(plugin.getDatabaseManager().fetchColumn("SELECT word FROM twitch_blacklist_words"));
                        case "prefix" -> completions.addAll(plugin.getDatabaseManager().fetchColumn("SELECT prefix FROM twitch_blacklist_prefixes"));
                        case "user" -> completions.addAll(plugin.getDatabaseManager().fetchColumn("SELECT username FROM twitch_blacklist_users"));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return completions;
    }
}
