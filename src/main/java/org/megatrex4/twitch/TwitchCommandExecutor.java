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

        String action = args[0].toLowerCase();

        switch (action) {

            case "add":
                if (!sender.hasPermission("twitch.add")) {
                    sender.sendMessage(plugin.getMessage("command.no_permission"));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(plugin.getMessage("command.no_channel"));
                    return true;
                }

                String channelToAdd = args[1].toLowerCase();
                plugin.addChannel(channelToAdd);
                sender.sendMessage(plugin.getMessage("command.channel_added").replace("%channel%", channelToAdd));
                break;

            case "remove":
                if (!sender.hasPermission("twitch.remove")) {
                    sender.sendMessage(plugin.getMessage("command.no_permission"));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(plugin.getMessage("command.no_channel"));
                    return true;
                }

                String channelToRemove = args[1].toLowerCase();
                plugin.removeChannel(channelToRemove);
                sender.sendMessage(plugin.getMessage("command.channel_removed").replace("%channel%", channelToRemove));
                break;

            case "reload":
                if (!sender.hasPermission("twitch.reload")) {
                    sender.sendMessage(plugin.getMessage("command.no_permission"));
                    return true;
                }

                plugin.reloadConfig();
                plugin.createMessagesFile();
                plugin.getLogger().info("Reloading Twitch client...");
                plugin.onDisable();
                plugin.onEnable();

                sender.sendMessage(plugin.getMessage("command.config_reloaded"));
                break;

            default:
                sender.sendMessage(plugin.getMessage("command.unknown_action").replace("%action%", action));
                break;
        }

        return true;
    }
}
