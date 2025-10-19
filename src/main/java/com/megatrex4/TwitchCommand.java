package com.megatrex4;

import com.megatrex4.Twitch;
import com.megatrex4.config.Config;
import com.megatrex4.config.ConfigManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TwitchCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("twitch")
                .requires(source -> source.hasPermissionLevel(2)) // Default OP level
                .then(literal("reload")
                        .executes(TwitchCommand::reload)
                )
                .then(literal("add")
                        .then(argument("channel", StringArgumentType.string())
                                .executes(ctx -> addChannel(ctx, StringArgumentType.getString(ctx, "channel"), null))
                                .then(argument("player", StringArgumentType.string())
                                        .executes(ctx -> addChannel(ctx, StringArgumentType.getString(ctx, "channel"), StringArgumentType.getString(ctx, "player")))
                                )
                        )
                )
                .then(literal("remove")
                        .then(argument("channel", StringArgumentType.string())
                                .suggests(TwitchCommand::suggestExistingChannels)
                                .executes(TwitchCommand::removeChannel)
                        )
                )
                .then(literal("blacklist")
                        .then(literal("add")
                                .then(literal("user")
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> blacklistAdd(ctx, "user", StringArgumentType.getString(ctx, "value"))))
                                )
                                .then(literal("word")
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> blacklistAdd(ctx, "word", StringArgumentType.getString(ctx, "value"))))
                                )
                                .then(literal("prefix")
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> blacklistAdd(ctx, "prefix", StringArgumentType.getString(ctx, "value"))))
                                )
                        )
                        .then(literal("remove")
                                .then(literal("user")
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> CommandSource.suggestMatching(ConfigManager.getConfig().blacklist.users, builder))
                                                .executes(ctx -> blacklistRemove(ctx, "user", StringArgumentType.getString(ctx, "value"))))
                                )
                                .then(literal("word")
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> CommandSource.suggestMatching(ConfigManager.getConfig().blacklist.words, builder))
                                                .executes(ctx -> blacklistRemove(ctx, "word", StringArgumentType.getString(ctx, "value"))))
                                )
                                .then(literal("prefix")
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> CommandSource.suggestMatching(ConfigManager.getConfig().blacklist.prefixes, builder))
                                                .executes(ctx -> blacklistRemove(ctx, "prefix", StringArgumentType.getString(ctx, "value"))))
                                )
                        )
                )
        );
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        Twitch.reload();
        context.getSource().sendFeedback(() -> Text.literal("§aTwitch configuration and client reloaded."), true);
        return 1;
    }

    private static int addChannel(CommandContext<ServerCommandSource> context, String channel, String player) {
        Config config = ConfigManager.getConfig();
        Optional<Config.Streamer> existing = config.streamers.stream().filter(s -> s.channelName.equalsIgnoreCase(channel)).findFirst();

        if (existing.isPresent()) {
            context.getSource().sendError(Text.literal("§cChannel '" + channel + "' is already being listened to."));
            return 0;
        }

        config.streamers.add(new Config.Streamer(channel, player));
        ConfigManager.save();
        Twitch.reload(); // Reload to join the new channel

        String feedback = player == null ? "§aAdded channel '§e" + channel + "§a'." : "§aAdded channel '§e" + channel + "§a' linked to player '§e" + player + "§a'.";
        context.getSource().sendFeedback(() -> Text.literal(feedback), true);
        return 1;
    }

    private static int removeChannel(CommandContext<ServerCommandSource> context) {
        String channel = StringArgumentType.getString(context, "channel");
        Config config = ConfigManager.getConfig();
        boolean removed = config.streamers.removeIf(s -> s.channelName.equalsIgnoreCase(channel));

        if (removed) {
            ConfigManager.save();
            Twitch.reload();
            context.getSource().sendFeedback(() -> Text.literal("§cRemoved channel '§e" + channel + "§c'."), true);
            return 1;
        } else {
            context.getSource().sendError(Text.literal("§cChannel '" + channel + "' was not found."));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestExistingChannels(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        return CommandSource.suggestMatching(ConfigManager.getConfig().streamers.stream().map(s -> s.channelName), builder);
    }

    private static int blacklistAdd(CommandContext<ServerCommandSource> context, String type, String value) {
        var list = switch (type) {
            case "user" -> ConfigManager.getConfig().blacklist.users;
            case "word" -> ConfigManager.getConfig().blacklist.words;
            case "prefix" -> ConfigManager.getConfig().blacklist.prefixes;
            default -> null;
        };
        if (list == null) return 0;

        if (!list.contains(value)) {
            list.add(value);
            ConfigManager.save();
            context.getSource().sendFeedback(() -> Text.literal("§aAdded '§e" + value + "§a' to the " + type + " blacklist."), true);
            return 1;
        }
        context.getSource().sendError(Text.literal("§cValue '§e" + value + "§c' is already in the " + type + " blacklist."));
        return 0;
    }

    private static int blacklistRemove(CommandContext<ServerCommandSource> context, String type, String value) {
        var list = switch (type) {
            case "user" -> ConfigManager.getConfig().blacklist.users;
            case "word" -> ConfigManager.getConfig().blacklist.words;
            case "prefix" -> ConfigManager.getConfig().blacklist.prefixes;
            default -> null;
        };
        if (list == null) return 0;

        if (list.remove(value)) {
            ConfigManager.save();
            context.getSource().sendFeedback(() -> Text.literal("§cRemoved '§e" + value + "§c' from the " + type + " blacklist."), true);
            return 1;
        }
        context.getSource().sendError(Text.literal("§cValue '§e" + value + "§c' was not found in the " + type + " blacklist."));
        return 0;
    }
}
