package org.megatrex4.twitch;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;

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
            case "blacklist" -> {
                if (!sender.hasPermission("twitch.add")) {
                    sender.sendMessage(plugin.getMessage("command.no_permission"));
                    return true;
                }
                return handleBlacklistCommand(sender, args);
            }
            case "add", "remove" -> {
                if (!sender.hasPermission("twitch." + action)) {
                    sender.sendMessage(plugin.getMessage("command.no_permission"));
                    return true;
                }

                if (args.length >= 2 && args[1].equalsIgnoreCase("blacklist")) {
                    return handleBlacklistCommand(sender, args);
                }

                if (args.length < 2) {
                    sender.sendMessage(plugin.getMessage("command.no_channel"));
                    return true;
                }

                String channel = args[1].toLowerCase();
                String playerName = (args.length >= 3) ? args[2] : null;

                boolean useSql = plugin.getConfig().getBoolean("sql.enabled", false);

                if (action.equals("add")) {
                    if (useSql) {
                        plugin.getDatabaseManager().addStreamer(channel, playerName);
                    } else {
                        String full = (playerName != null) ? channel + ":" + playerName : channel;
                        plugin.addChannel(full);
                    }
                    sender.sendMessage(plugin.getMessage("command.channel_added").replace("%channel%", channel));
                } else {
                    if (useSql) {
                        plugin.getDatabaseManager().removeStreamer(channel);
                    } else {
                        plugin.removeChannel(channel);
                    }
                    sender.sendMessage(plugin.getMessage("command.channel_removed").replace("%channel%", channel));
                }
            }

            case "reload" -> {
                if (!sender.hasPermission("twitch.reload")) {
                    sender.sendMessage(plugin.getMessage("command.no_permission"));
                    return true;
                }
                plugin.reloadConfig();
                plugin.createMessagesFile();
                plugin.getLogger().info(plugin.getMessage("twitch.config_reloaded"));
                plugin.onDisable();
                plugin.onEnable();
                sender.sendMessage(plugin.getMessage("command.config_reloaded"));
            }

            default -> sender.sendMessage(plugin.getMessage("command.unknown_action").replace("%action%", action));
        }

        return true;
    }

    private boolean handleBlacklistCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(plugin.getMessage("command.blacklist_usage"));
            return true;
        }

        String operation = args[1].toLowerCase();
        String type = args[2].toLowerCase();
        String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        boolean useSql = plugin.getConfig().getBoolean("sql.enabled", false);
        boolean success = false;

        if (useSql) {
            var db = plugin.getDatabaseManager();
            switch (operation + "-" + type) {
                case "add-word" -> success = db.addBlacklistedWord(value);
                case "remove-word" -> success = db.removeBlacklistedWord(value);
                case "add-prefix" -> success = db.addBlacklistedPrefix(value);
                case "remove-prefix" -> success = db.removeBlacklistedPrefix(value);
                case "add-user" -> success = db.addBlacklistedUser(value);
                case "remove-user" -> success = db.removeBlacklistedUser(value);
                default -> {
                    sender.sendMessage(plugin.getMessage("command.unknown_blacklist_type").replace("%type%", type));
                    return true;
                }
            }
        } else {
            var config = plugin.getConfig();
            String path;
            switch (type) {
                case "word":
                    path = "twitch.blacklist.words";
                    break;
                case "prefix":
                    path = "twitch.blacklist.prefixes";
                    break;
                case "user":
                    path = "twitch.blacklist.users";
                    break;
                default:
                    sender.sendMessage(plugin.getMessage("command.unknown_blacklist_type") + type);
                    return true;
            }

            List<String> list = config.getStringList(path);
            if (operation.equals("add")) {
                if (!list.contains(value)) {
                    list.add(value);
                    success = true;
                }
            } else if (operation.equals("remove")) {
                success = list.remove(value);
            }

            config.set(path, list);
            plugin.saveConfig();
        }

        if (success) {
            sender.sendMessage(plugin.getMessage("command.blacklist_" + operation + "_success").replace("%type%", type).replace("%value%", value));
        } else {
            sender.sendMessage(plugin.getMessage("command.nothingChanges"));
        }

        return true;
    }
}
