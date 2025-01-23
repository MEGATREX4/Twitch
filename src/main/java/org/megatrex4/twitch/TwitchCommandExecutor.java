package org.megatrex4.twitch;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class TwitchCommandExecutor implements CommandExecutor {
    private final Twitch plugin;

    public TwitchCommandExecutor(Twitch plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /twitchlink <add|remove|reload> <channel>");
            return true;
        }

        String action = args[0];
        if (args.length < 2 && !action.equalsIgnoreCase("reload")) {
            sender.sendMessage("Please specify a channel.");
            return true;
        }

        String channel = args.length > 1 ? args[1] : null;

        if (action.equalsIgnoreCase("add")) {
            if (sender.hasPermission("twitch.add")) {
                plugin.addChannel(channel);
                sender.sendMessage("Added channel: " + channel);
            } else {
                sender.sendMessage("You don't have permission to add channels.");
            }
        } else if (action.equalsIgnoreCase("remove")) {
            if (sender.hasPermission("twitch.remove")) {
                plugin.removeChannel(channel);
                sender.sendMessage("Removed channel: " + channel);
            } else {
                sender.sendMessage("You don't have permission to remove channels.");
            }
        } else if (action.equalsIgnoreCase("reload")) {
            if (sender.hasPermission("twitch.reload")) {
                plugin.reloadConfig();
                sender.sendMessage("Configuration reloaded!");
            } else {
                sender.sendMessage("You don't have permission to reload configuration.");
            }
        } else {
            sender.sendMessage("Unknown action: " + action);
        }

        return true;
    }
}

