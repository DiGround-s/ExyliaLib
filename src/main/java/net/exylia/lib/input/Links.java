package net.exylia.lib.input;

import net.exylia.lib.input.internal.DialogTransport;
import net.exylia.lib.input.internal.DialogTransport.Link;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Links a player can follow without the chat: a native dialog (Minecraft 1.21.6+) with one button per link.
 * An address goes through Minecraft's own "open this page?" screen, so it is the same as clicking it in chat,
 * also where the chat cannot be clicked (a screen is up).
 *
 * <pre>{@code
 * Links.open(player, "https://dc.exylia.net", "Discord");   // what [URL] https://dc.exylia.net;Discord does
 * Links.show(player, line);   // the links of a chat line, from a toast or a hologram
 * }</pre>
 *
 * @since 1.554.0
 */
public final class Links {

    /** More buttons than this is not a dialog anybody reads. */
    private static final int MAX = 8;

    private Links() {
    }

    /**
     * Offers one address: a dialog to open or copy it, or a clickable chat line where dialogs do not reach
     * (older clients, no PacketEvents, dialogs turned off).
     *
     * @param url   the address; {@code https://} is added when it has no scheme
     * @param title what the dialog is called (formatting allowed); {@code null} for the address's host
     */
    public static void open(@NotNull Player player, @NotNull String url, @Nullable String title) {
        String address = address(url);
        String shown = shown(address);
        Component name = title == null || title.isBlank() ? Component.text(shown) : Text.component(title);
        boolean opened = DialogTransport.links(player, name, Component.text(shown, NamedTextColor.GRAY), List.of(
                new Link(Text.component(Phrases.tr("Open link")), Component.text(address), Link.Kind.OPEN_URL, address),
                new Link(Text.component(Phrases.tr("Copy link")), Text.component(Phrases.tr("Copies it to your clipboard")),
                        Link.Kind.COPY, address)));
        if (!opened) {
            player.sendMessage(Component.text().append(name).append(Component.text(": ", NamedTextColor.GRAY))
                    .append(Component.text(shown, NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(address))));
        }
    }

    /**
     * Shows the links of a line (open, copy, run and suggest clicks) as a dialog with the line above them.
     *
     * @return whether a dialog was shown: {@code false} when the line has none or dialogs do not reach the player
     */
    public static boolean show(@NotNull Player player, @NotNull Component line) {
        return DialogTransport.links(player, Text.component(Phrases.tr("Links")), line, found(line));
    }

    /** Whether {@link #show} has a dialog for this line and this player. */
    public static boolean showable(@NotNull Player player, @NotNull Component line) {
        return !found(line).isEmpty() && DialogTransport.reaches(player);
    }

    /** The address with a scheme: {@code dc.exylia.net} is {@code https://dc.exylia.net}. */
    static String address(String url) {
        String trimmed = url.trim();
        return trimmed.contains("://") ? trimmed : "https://" + trimmed;
    }

    /** The address as a player reads it: no scheme, no trailing slash. */
    static String shown(String address) {
        String shown = address.substring(address.indexOf("://") + 3);
        return shown.endsWith("/") ? shown.substring(0, shown.length() - 1) : shown;
    }

    /** Every link in a line once, in order: the words it is on are its label. */
    static List<Link> found(Component line) {
        List<Link> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder words = new StringBuilder();
        ClickEvent[] current = {null};
        walk(line, null, (text, click) -> {
            if (!click(click).equals(click(current[0]))) {
                add(links, seen, words.toString(), current[0]);
                words.setLength(0);
                current[0] = click;
            }
            words.append(text);
        });
        add(links, seen, words.toString(), current[0]);
        return links;
    }

    private interface Visit {
        void text(String text, @Nullable ClickEvent click);
    }

    private static void walk(Component node, @Nullable ClickEvent inherited, Visit visit) {
        ClickEvent click = node.clickEvent() != null ? node.clickEvent() : inherited;
        if (node instanceof TextComponent text && !text.content().isEmpty()) visit.text(text.content(), click);
        for (Component child : node.children()) walk(child, click, visit);
    }

    private static String click(@Nullable ClickEvent click) {
        return click == null ? "" : click.action() + " " + click.value();
    }

    private static void add(List<Link> links, Set<String> seen, String words, @Nullable ClickEvent click) {
        if (click == null || links.size() >= MAX || !seen.add(click(click))) return;
        String value = click.value();
        Link.Kind kind = switch (click.action()) {
            case OPEN_URL -> Link.Kind.OPEN_URL;
            case COPY_TO_CLIPBOARD -> Link.Kind.COPY;
            case RUN_COMMAND -> Link.Kind.RUN_COMMAND;
            case SUGGEST_COMMAND -> Link.Kind.SUGGEST_COMMAND;
            default -> null;
        };
        if (kind == null || value.isBlank()) return;
        String label = words.isBlank() ? (kind == Link.Kind.OPEN_URL ? shown(value) : value) : words.trim();
        Component tooltip = kind == Link.Kind.COPY ? Text.component(Phrases.tr("Copies it to your clipboard")) : Component.text(value);
        links.add(new Link(Component.text(label), tooltip, kind, value));
    }
}
