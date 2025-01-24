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
            sender.sendMessage(plugin.getMessage("command.usage"));
            return true;
        }

        String action = args[0];
        String channel = args.length > 1 ? args[1] : null;

        if (action.equalsIgnoreCase("add")) {
            if (sender.hasPermission("twitch.add")) {
                if (channel != null) {
                    plugin.addChannel(channel);
                    sender.sendMessage(plugin.getMessage("command.channel_added").replace("%channel%", channel));
                } else {
                    sender.sendMessage(plugin.getMessage("command.no_channel"));
                }
            } else {
                sender.sendMessage(plugin.getMessage("command.no_permission"));
            }
        } else if (action.equalsIgnoreCase("remove")) {
            if (sender.hasPermission("twitch.remove")) {
                if (channel != null) {
                    plugin.removeChannel(channel);
                    sender.sendMessage(plugin.getMessage("command.channel_removed").replace("%channel%", channel));
                } else {
                    sender.sendMessage(plugin.getMessage("command.no_channel"));
                }
            } else {
                sender.sendMessage(plugin.getMessage("command.no_permission"));
            }
        } else if (action.equalsIgnoreCase("reload")) {
            if (sender.hasPermission("twitch.reload")) {
                plugin.reloadConfig();
                plugin.createMessagesFile();
                plugin.reconnectWebSocket();
                sender.sendMessage(plugin.getMessage("command.config_reloaded"));

            } else {
                sender.sendMessage(plugin.getMessage("command.no_permission"));
            }
        } else {
            sender.sendMessage(plugin.getMessage("command.unknown_action").replace("%action%", action));
        }

        return true;
    }
}
