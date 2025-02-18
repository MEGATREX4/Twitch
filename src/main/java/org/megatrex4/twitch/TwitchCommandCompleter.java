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

        if (args.length == 1) {
            if (sender.hasPermission("twitch.add")) completions.add("add");
            if (sender.hasPermission("twitch.remove")) completions.add("remove");
            if (sender.hasPermission("twitch.reload")) completions.add("reload");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            completions.addAll(Arrays.asList("example_channel_1"));
        }

        return completions;
    }
}
