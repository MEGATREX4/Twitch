package com.megatrex4;

import com.megatrex4.config.Config;
import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.object.PlayerTextObjectContents;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds rich chat {@link Text} from the configured message format string.
 * Supports {@code &} color codes and special placeholders, including the
 * Minecraft Player Object Type head via {@code <%player%>} / {@code %player%}.
 */
public final class MessageFormatter {
    /**
     * Matches either {@code <%name%>} or {@code %name%} placeholders.
     * Group 1 = name inside {@code <%...%>}, group 2 = name inside {@code %...%}.
     * The angled form is tried first so {@code <%player%>} is not split into
     * a partial match on the inner {@code %player%}.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "<%([a-zA-Z0-9_]+)%>|%([a-zA-Z0-9_]+)%"
    );

    private MessageFormatter() {
    }

    /**
     * @param streamer may be {@code null} if the channel is not in config
     */
    public static Text format(
            String format,
            String twitchUser,
            String twitchMessage,
            String channelName,
            Config.Streamer streamer,
            Config.TwitchSettings settings,
            MinecraftServer server
    ) {
        MutableText root = Text.empty();
        Style currentStyle = Style.EMPTY;

        Matcher matcher = PLACEHOLDER.matcher(format);
        int last = 0;

        while (matcher.find()) {
            // Literal text before this placeholder (with color codes)
            if (matcher.start() > last) {
                String literal = format.substring(last, matcher.start());
                currentStyle = appendLiteral(root, literal, currentStyle);
            }

            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            key = key.toLowerCase(Locale.ROOT);

            Text replacement = resolvePlaceholder(
                    key, twitchUser, twitchMessage, channelName, streamer, settings, server
            );
            if (replacement != null) {
                // Placeholders keep their own style (e.g. hover on head) and inherit color
                root.append(Text.empty().setStyle(currentStyle).append(replacement));
            }

            last = matcher.end();
        }

        if (last < format.length()) {
            appendLiteral(root, format.substring(last), currentStyle);
        }

        // Optional PlaceholderAPI pass for any remaining server placeholders
        String raw = root.getString();
        if (raw.contains("%") && raw.indexOf('%', raw.indexOf('%') + 1) != -1) {
            PlaceholderContext context = PlaceholderContext.of(server);
            return Placeholders.parseText(root, context);
        }

        return root;
    }

    /** @return replacement text, or {@code null} for unknown placeholders */
    private static Text resolvePlaceholder(
            String key,
            String twitchUser,
            String twitchMessage,
            String channelName,
            Config.Streamer streamer,
            Config.TwitchSettings settings,
            MinecraftServer server
    ) {
        return switch (key) {
            case "twitch_user" -> Text.literal(twitchUser);
            case "twitch_message" -> Text.literal(twitchMessage);
            case "channel" -> Text.literal(channelName);
            case "player" -> createPlayerHead(streamer, settings, twitchUser, channelName, server);
            default -> null; // leave unknown tokens out (or could re-insert as literal)
        };
    }

    /**
     * Builds a Player Object Type head for the streamer's linked Minecraft player.
     * Returns empty text when no player name is linked or {@code streamer} is null.
     */
    private static Text createPlayerHead(
            Config.Streamer streamer,
            Config.TwitchSettings settings,
            String twitchUser,
            String channelName,
            MinecraftServer server
    ) {
        if (streamer == null) {
            return Text.empty();
        }

        String playerName = streamer.requiredPlayerName;
        if (playerName == null || playerName.isBlank()) {
            return Text.empty();
        }

        ProfileComponent profile = resolveProfile(playerName, server);
        boolean hat = settings.playerHeadHat;

        MutableText head = Text.object(new PlayerTextObjectContents(profile, hat));

        Text hover = buildHoverText(streamer, settings, twitchUser, channelName, playerName);
        if (hover != null) {
            head = head.styled(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
        }

        return head;
    }

    private static ProfileComponent resolveProfile(String playerName, MinecraftServer server) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerName);
        if (online != null) {
            // Prefer live skin data when the streamer is online
            return ProfileComponent.ofStatic(online.getGameProfile());
        }
        // Offline / name-only – client will resolve textures by name when possible
        return ProfileComponent.ofDynamic(playerName);
    }

    /** @return hover text, or {@code null} if no hover template is configured */
    private static Text buildHoverText(
            Config.Streamer streamer,
            Config.TwitchSettings settings,
            String twitchUser,
            String channelName,
            String playerName
    ) {
        String template = streamer.hoverText;
        if (template == null || template.isBlank()) {
            template = settings.playerHoverFormat;
        }
        if (template == null || template.isBlank()) {
            return null;
        }

        String filled = template
                .replace("%channel%", channelName)
                .replace("%player%", playerName)
                .replace("%twitch_user%", twitchUser)
                .replace("<%channel%>", channelName)
                .replace("<%player%>", playerName)
                .replace("<%twitch_user%>", twitchUser);

        return parseColoredLiteral(filled);
    }

    /**
     * Appends a string that may contain {@code &} formatting codes to {@code root},
     * returning the style active after the last character.
     */
    private static Style appendLiteral(MutableText root, String text, Style startStyle) {
        Style style = startStyle;
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                // Flush pending plain text with current style
                if (!buffer.isEmpty()) {
                    root.append(Text.literal(buffer.toString()).setStyle(style));
                    buffer.setLength(0);
                }
                char code = Character.toLowerCase(text.charAt(++i));
                style = applyFormatCode(style, code);
            } else {
                buffer.append(c);
            }
        }

        if (!buffer.isEmpty()) {
            root.append(Text.literal(buffer.toString()).setStyle(style));
        }
        return style;
    }

    /** Parse a short colored string into a single Text (used for hover tooltips). */
    private static Text parseColoredLiteral(String text) {
        MutableText root = Text.empty();
        appendLiteral(root, text, Style.EMPTY);
        return root;
    }

    private static Style applyFormatCode(Style style, char code) {
        Formatting formatting = Formatting.byCode(code);
        if (formatting != null) {
            if (formatting == Formatting.RESET) {
                return Style.EMPTY;
            }
            return style.withFormatting(formatting);
        }
        // Hex colors are not supported via single & codes here
        if (code == 'x') {
            // ignore §x RGB sequences for simplicity
            return style;
        }
        // Keep style unchanged for unknown codes
        return style;
    }
}
