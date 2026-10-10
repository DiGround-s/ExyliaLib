package net.exylia.lib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Sending one plugin's own lines, with their values filled in.
 *
 * <pre>{@code
 * // onEnable
 * messages = PluginMessages.of(this);
 *
 * messages.send(player, config.created(), Values.of("mine", mine.id()));
 * }</pre>
 *
 * <p>Every line goes through {@link Text#from}, so {@code %prefix%} is this
 * plugin's prefix and a leading effect tag ({@code [sound:NAME|1|1]}) plays.
 *
 * <h2>A blank line sends nothing</h2>
 * That is how a server owner turns one message off: emptying its value in the
 * file, rather than finding the code that sends it. {@link Text#send} itself
 * sends an empty line on purpose, so the rule lives here, on the path that
 * reads lines from a messages file.
 *
 * <p>Stateless apart from the plugin; hold one per plugin. Sending must happen
 * on the thread that owns the receiver, as with {@link Text#send}.
 *
 * @since 1.266.0
 */
public final class PluginMessages {

    private final Plugin plugin;

    private PluginMessages(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Messages belonging to a plugin.
     *
     * @param plugin whose prefix {@code %prefix%} is
     * @return the sender
     */
    public static @NotNull PluginMessages of(@NotNull Plugin plugin) {
        return new PluginMessages(Objects.requireNonNull(plugin, "plugin"));
    }

    /** The plugin these lines belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** Sends a line; a blank one, or a {@code null} receiver, sends nothing. */
    public void send(@Nullable CommandSender to, @Nullable String line) {
        send(to, line, null);
    }

    /**
     * Sends a line with the values it is about.
     *
     * @param to     who reads it; {@code null} sends nothing
     * @param line   the line from the messages file; blank sends nothing
     * @param values what it is about, or {@code null}
     */
    public void send(@Nullable CommandSender to, @Nullable String line, @Nullable Values values) {
        if (to == null || line == null || line.isBlank()) return;
        text(line, values).send(to);
    }

    /** Sends several lines in order, each under the same rules as {@link #send(CommandSender, String, Values)}. */
    public void send(@Nullable CommandSender to, @Nullable List<String> lines, @Nullable Values values) {
        if (to == null || lines == null) return;
        for (String line : lines) {
            send(to, line, values);
        }
    }

    /**
     * The line as a prepared text, for when it goes somewhere other than chat.
     *
     * @param line   the line; {@code null} becomes an empty text
     * @param values what it is about, or {@code null}
     * @return the prepared text
     */
    public @NotNull Text text(@Nullable String line, @Nullable Values values) {
        Text text = Text.from(plugin, line == null ? "" : line);
        return values == null ? text : values.applyTo(text);
    }

    /**
     * The line as an item name or lore line: not italic unless it says so.
     *
     * <p>Minecraft draws custom item text in italics by default. Menus built by
     * the library already undo that; an item a plugin builds by hand does not.
     */
    public @NotNull Component item(@Nullable String line, @Nullable Values values) {
        return text(line, values).build().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** The line with every bit of formatting removed, for logs and the console. */
    public @NotNull String plain(@Nullable String line, @Nullable Values values) {
        return text(line, values).plain();
    }
}
