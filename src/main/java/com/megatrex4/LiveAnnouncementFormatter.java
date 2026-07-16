package com.megatrex4;

import com.megatrex4.config.Config;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the go-live server announcement with a purple clickable Twitch link.
 */
public final class LiveAnnouncementFormatter {
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "<%([a-zA-Z0-9_]+)%>|%([a-zA-Z0-9_]+)%"
    );

    private LiveAnnouncementFormatter() {
    }

    public static Text format(
            Config.LiveAnnouncement settings,
            String channelName,
            String playerName,
            String streamTitle
    ) {
        String channel = channelName == null ? "" : channelName;
        String player = playerName == null || playerName.isBlank() ? channel : playerName;
        String title = streamTitle == null ? "" : streamTitle;
        String url = "https://twitch.tv/" + channel.toLowerCase(Locale.ROOT);

        MutableText root = Text.empty();
        Style currentStyle = Style.EMPTY;

        Matcher matcher = PLACEHOLDER.matcher(settings.format == null ? "" : settings.format);
        int last = 0;

        while (matcher.find()) {
            if (matcher.start() > last) {
                currentStyle = appendLiteral(root, settings.format.substring(last, matcher.start()), currentStyle);
            }

            String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            key = key.toLowerCase(Locale.ROOT);

            Text replacement = switch (key) {
                case "streamer", "channel" -> Text.literal(channel);
                case "player" -> Text.literal(player);
                case "title" -> Text.literal(title);
                case "url" -> Text.literal(url);
                case "link" -> createLink(settings, channel, player, title, url);
                default -> null;
            };

            if (replacement != null) {
                root.append(Text.empty().setStyle(currentStyle).append(replacement));
            }

            last = matcher.end();
        }

        if (settings.format != null && last < settings.format.length()) {
            appendLiteral(root, settings.format.substring(last), currentStyle);
        }

        return root;
    }

    private static Text createLink(
            Config.LiveAnnouncement settings,
            String channel,
            String player,
            String title,
            String url
    ) {
        String visible = settings.linkText == null || settings.linkText.isBlank()
                ? url
                : fillSimple(settings.linkText, channel, player, title, url);

        // Strip & codes from visible text for the clickable part, then re-apply base purple style
        MutableText linkBody = Text.empty();
        appendLiteral(linkBody, visible, Style.EMPTY);

        String hoverRaw = settings.linkHover == null || settings.linkHover.isBlank()
                ? "&dOpen stream"
                : settings.linkHover;
        Text hover = parseColored(fillSimple(hoverRaw, channel, player, title, url));

        Style linkStyle = Style.EMPTY
                .withColor(Formatting.LIGHT_PURPLE)
                .withUnderline(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(hover));

        return Text.empty().setStyle(linkStyle).append(linkBody);
    }

    private static String fillSimple(String template, String channel, String player, String title, String url) {
        return template
                .replace("%channel%", channel)
                .replace("%streamer%", channel)
                .replace("%player%", player)
                .replace("%title%", title)
                .replace("%url%", url)
                .replace("<%channel%>", channel)
                .replace("<%streamer%>", channel)
                .replace("<%player%>", player)
                .replace("<%title%>", title)
                .replace("<%url%>", url);
    }

    private static Text parseColored(String text) {
        MutableText root = Text.empty();
        appendLiteral(root, text, Style.EMPTY);
        return root;
    }

    private static Style appendLiteral(MutableText root, String text, Style startStyle) {
        Style style = startStyle;
        StringBuilder buffer = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Support \n as real newline in hover / format
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == 'n') {
                if (!buffer.isEmpty()) {
                    root.append(Text.literal(buffer.toString()).setStyle(style));
                    buffer.setLength(0);
                }
                root.append(Text.literal("\n").setStyle(style));
                i++;
                continue;
            }
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
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

    private static Style applyFormatCode(Style style, char code) {
        Formatting formatting = Formatting.byCode(code);
        if (formatting != null) {
            if (formatting == Formatting.RESET) {
                return Style.EMPTY;
            }
            return style.withFormatting(formatting);
        }
        return style;
    }
}
