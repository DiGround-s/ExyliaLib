package net.exylia.lib.text;

import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns text into Adventure components, understanding every notation Exylia
 * uses.
 *
 * <p>This is the single door every player-facing string goes through: chat,
 * titles, action bars, item names, lore, scoreboards.
 *
 * <h2>Notations</h2>
 * All three work, mixed freely in one string:
 * <pre>{@code
 * Text.of("{primary}&lWELCOME &8[{success}online&8]");
 * Text.of("<gradient:#8a51c4:#ff6b9d>Exylia</gradient>");
 * Text.of("&#ff9500Careful");
 * }</pre>
 *
 * <table border="1">
 *   <caption>Supported notations</caption>
 *   <tr><th>Form</th><th>Example</th></tr>
 *   <tr><td>Palette token</td><td>{@code {primary}}, {@code {error}}</td></tr>
 *   <tr><td>Legacy code</td><td>{@code &a}, {@code &l}</td></tr>
 *   <tr><td>Legacy hex</td><td>{@code &#8a51c4}, {@code &x&8&a&5&1&c&4}</td></tr>
 *   <tr><td>MiniMessage</td><td>{@code <bold>}, {@code <gradient:...>}</td></tr>
 * </table>
 *
 * <h2>Performance</h2>
 * Text with no formatting characters skips the parser entirely, and everything
 * else is parsed once and cached, so re-sending the same line every tick costs a
 * cache lookup rather than a parse. That is what makes this safe to call from a
 * scoreboard or action bar loop.
 *
 * <p>When a line contains values that differ per player, use
 * {@link #with(String, Object)} so the shared part still benefits from the
 * cache:
 *
 * <pre>{@code
 * Text.of("{letters}Coins: {highlight}%coins%")
 *     .with("%coins%", player.getCoins())
 *     .send(player);
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * Building text is safe from any thread. Sending it must happen on the thread
 * that owns the receiver, which is what
 * {@link net.exylia.lib.task.TaskScheduler#runAtEntity} is for.
 *
 * @since 1.2.0
 */
public final class Text {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private final String raw;
    private final List<String> keys;
    private final List<String> values;

    private Text(String raw, List<String> keys, List<String> values) {
        this.raw = raw;
        this.keys = keys;
        this.values = values;
    }

    /**
     * Prepares a piece of text.
     *
     * <p>Nothing is parsed until the text is used, so building one is free.
     *
     * @param text the raw text, in any supported notation
     * @return the prepared text
     */
    public static @NotNull Text of(@NotNull String text) {
        return new Text(text, List.of(), List.of());
    }

    /**
     * Builds a component directly, for when there is nothing to substitute.
     *
     * <p>The shortest path from a string to a component, and the one to use on
     * hot paths.
     *
     * @param text the raw text
     * @return the parsed component
     */
    public static @NotNull Component component(@NotNull String text) {
        return TextEngine.parse(text);
    }

    /**
     * Substitutes a value into the text.
     *
     * <p>Substitution happens <em>after</em> parsing, on the component tree, so
     * the surrounding text still hits the parse cache no matter how often the
     * value changes. That is the difference between a scoreboard costing a cache
     * lookup per tick and costing a full parse per tick.
     *
     * <p>Because the value is inserted as literal text, it cannot inject
     * formatting: a player who names themselves {@code &cX} shows up as
     * {@code &cX}, not as red text.
     *
     * @param placeholder the exact text to replace, such as {@code %coins%}
     * @param value       the value to insert; {@code null} becomes an empty string
     * @return a new prepared text; the original is unchanged
     */
    public @NotNull Text with(@NotNull String placeholder, Object value) {
        List<String> newKeys = new ArrayList<>(keys.size() + 1);
        List<String> newValues = new ArrayList<>(values.size() + 1);
        newKeys.addAll(keys);
        newValues.addAll(values);
        newKeys.add(placeholder);
        newValues.add(value == null ? "" : String.valueOf(value));
        return new Text(raw, newKeys, newValues);
    }

    /**
     * Builds the component.
     *
     * @return the parsed component, with every substitution applied
     */
    public @NotNull Component build() {
        Component component = TextEngine.parse(raw);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = values.get(i);
            component = component.replaceText(builder -> builder
                    .matchLiteral(key)
                    .replacement(Component.text(value)));
        }
        return component;
    }

    /**
     * Sends this text as a chat message.
     *
     * <p>Must be called on the thread that owns the receiver.
     *
     * @param receiver who to send it to
     */
    public void send(@NotNull CommandSender receiver) {
        receiver.sendMessage(build());
    }

    /**
     * Returns the text with all formatting removed.
     *
     * <p>For logs, comparisons, and anywhere a console reads the value.
     *
     * @return the plain text
     */
    public @NotNull String plain() {
        return PLAIN.serialize(build());
    }

    /**
     * Returns the text in the legacy section-sign form.
     *
     * <p>Only for APIs that still demand a legacy string, such as older
     * scoreboard or inventory calls. Prefer components everywhere else: legacy
     * strings cannot carry hover text, click actions, or full RGB reliably.
     *
     * @return the legacy string
     */
    public @NotNull String legacy() {
        return SECTION.serialize(build());
    }

    /**
     * Returns the raw text this was built from, before any parsing.
     *
     * @return the original string
     */
    public @NotNull String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "Text[" + raw + "]";
    }
}
