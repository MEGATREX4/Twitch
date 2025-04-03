package org.megatrex4.twitch;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TwitchCommandCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        // Command completions for 'add', 'remove', and 'reload'
        if (args.length == 1) {
            if (sender.hasPermission("twitch.add")) completions.add("add");
            if (sender.hasPermission("twitch.remove")) completions.add("remove");
            if (sender.hasPermission("twitch.reload")) completions.add("reload");
        }
        // Channel name completions for 'add' and 'remove'
        else if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            // Example channel list (this could be dynamically generated or fetched)
            completions.addAll(Arrays.asList("example_channel_1", "example_channel_2"));
        }
        // Streamer nickname completions for 'add' when a channel is provided
        else if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            completions.add("streamer_nickname"); // You could populate this with a dynamic list of streamer nicknames
        }

        return completions;
    }
}
