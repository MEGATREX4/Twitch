package com.megatrex4;

import com.megatrex4.config.Config;
import com.megatrex4.config.ConfigManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TwitchCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("twitch")
                .requires(source -> Permissions.hasPermissionLevel(source, PermissionLevel.GAMEMASTERS))
                .then(literal("reload")
                        .executes(TwitchCommand::reload)
                )
                .then(literal("status")
                        .executes(TwitchCommand::status)
                )
                .then(literal("check")
                        .executes(TwitchCommand::check)
                )
                .then(literal("list")
                        .executes(TwitchCommand::status) // alias
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

    /**
     * Show MC online + Twitch live status for every configured streamer.
     */
    private static int status(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Config config = ConfigManager.getConfig();

        source.sendFeedback(() -> Text.literal("§5§l[Twitch] §r§fStatus"), false);
        source.sendFeedback(() -> Text.literal(
                "§7Chat: " + (Twitch.isChatEnabled() ? "§aON" : "§cOFF")
                        + "  §7Helix: " + (Twitch.isHelixEnabled() ? "§aON" : "§cOFF")
                        + "  §7LiveAnnounce: " + (config.twitch.liveAnnouncement != null && config.twitch.liveAnnouncement.enabled ? "§aON" : "§cOFF")
        ), false);

        if (!Twitch.isHelixEnabled()) {
            source.sendFeedback(() -> Text.literal(
                    "§cHelix OFF → set clientId + clientSecret in config/twitch.json, then /twitch reload."
            ), false);
        }

        List<Twitch.StreamerStatus> list = Twitch.getStreamerStatuses();
        if (list.isEmpty()) {
            source.sendFeedback(() -> Text.literal("§7No streamers configured."), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("§8channel → mc_player | Minecraft | Twitch"), false);

        int liveCount = 0;
        for (Twitch.StreamerStatus s : list) {
            if (s.twitchLive) liveCount++;
            String mc = s.mcOnline ? "§aMC online" : "§cMC offline";
            String tw = s.twitchLive ? "§aTWITCH LIVE" : "§8Twitch offline";
            String player = (s.player == null || s.player.isBlank()) ? "§8(no link)" : "§f" + s.player;
            String titlePart = (s.twitchLive && s.title != null && !s.title.isBlank())
                    ? " §7(§f" + truncate(s.title, 36) + "§7)"
                    : "";
            String announcePart = s.announcedThisPeriod ? " §d[announced]" : "";
            String cooldownPart = formatCooldown(s.lastAnnounceMs, config);

            // Example: MEGATREX4 → Yevhen4 | MC online | TWITCH LIVE (title)
            String line = "§d" + s.channel + " §7→ " + player
                    + " §7| " + mc
                    + " §7| " + tw + titlePart
                    + announcePart + cooldownPart;
            source.sendFeedback(() -> Text.literal(line), false);
        }

        int liveFinal = liveCount;
        source.sendFeedback(() -> Text.literal(
                liveFinal == 0
                        ? "§7Twitch live now: §8none of the configured channels"
                        : "§7Twitch live now: §a" + liveFinal + " §7channel(s)"
        ), false);
        source.sendFeedback(() -> Text.literal("§7Use §f/twitch check §7to force-refresh Twitch live status from Helix."), false);
        return 1;
    }

    /**
     * Force Helix poll (async), then print full status with Twitch LIVE flags.
     */
    private static int check(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        source.sendFeedback(() -> Text.literal("§eQuerying Twitch Helix for live channels…"), false);

        CompletableFuture.supplyAsync(Twitch::forcePollNow).thenAccept(msg -> {
            source.getServer().execute(() -> {
                source.sendFeedback(() -> Text.literal(msg), false);
                status(context);
            });
        }).exceptionally(ex -> {
            source.getServer().execute(() ->
                    source.sendError(Text.literal("§cHelix check failed: " + ex.getMessage()))
            );
            return null;
        });
        return 1;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String formatCooldown(Long lastMs, Config config) {
        if (lastMs == null) return "";
        int cooldownMin = config.twitch.liveAnnouncement != null
                ? Math.max(0, config.twitch.liveAnnouncement.reannounceCooldownMinutes)
                : 60;
        if (cooldownMin <= 0) return "";
        long elapsed = System.currentTimeMillis() - lastMs;
        long cooldownMs = TimeUnit.MINUTES.toMillis(cooldownMin);
        if (elapsed >= cooldownMs) return " §7(cooldown done)";
        long left = TimeUnit.MILLISECONDS.toMinutes(cooldownMs - elapsed) + 1;
        return " §7(cd ~" + left + "m)";
    }

    private static int addChannel(CommandContext<ServerCommandSource> context, String channel, String player) {
        Config config = ConfigManager.getConfig();
        Optional<Config.Streamer> existing = config.streamers.stream()
                .filter(s -> s.channelName.equalsIgnoreCase(channel))
                .findFirst();

        if (existing.isPresent()) {
            context.getSource().sendError(Text.literal("§cChannel '" + channel + "' is already being listened to."));
            return 0;
        }

        config.streamers.add(new Config.Streamer(channel, player));
        ConfigManager.save();
        Twitch.reload();

        String feedback = player == null
                ? "§aAdded channel '§e" + channel + "§a'."
                : "§aAdded channel '§e" + channel + "§a' linked to player '§e" + player + "§a'.";
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
        return CommandSource.suggestMatching(
                ConfigManager.getConfig().streamers.stream().map(s -> s.channelName),
                builder
        );
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
