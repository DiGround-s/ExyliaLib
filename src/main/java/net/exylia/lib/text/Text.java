package net.exylia.lib.text;

import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.text.internal.EffectTag;
import net.exylia.lib.text.internal.EffectTagPlayer;
import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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

    /** The one placeholder the text module resolves itself, since it owns prefixes. */
    private static final String PREFIX_TOKEN = "%prefix%";

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private final String raw;
    private final List<String> keys;
    private final List<String> values;

    /** Who the placeholders are resolved for, or {@code null} to leave them alone. */
    private final Player viewer;

    /** The plugin this text belongs to, so {@code %prefix%} knows whose to use. */
    private final Plugin owner;

    private Text(String raw, List<String> keys, List<String> values, Player viewer, Plugin owner) {
        this.raw = raw;
        this.keys = keys;
        this.values = values;
        this.viewer = viewer;
        this.owner = owner;
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
        return new Text(text, List.of(), List.of(), null, null);
    }

    /**
     * Prepares a message belonging to a plugin, so {@code %prefix%} resolves to
     * that plugin's prefix.
     *
     * <p>For the ordinary case of a message read from a plugin's own messages
     * file:
     *
     * <pre>{@code
     * Prefixes.set(this, messages.prefix());
     * Text.from(this, messages.warmup().ready()).send(player);
     * }</pre>
     *
     * <p>{@link #of} leaves {@code %prefix%} untouched, because text that does
     * not say which plugin it came from has no prefix to use.
     *
     * @param plugin the plugin the message belongs to
     * @param text   the raw text, in any supported notation
     * @return the prepared text
     */
    public static @NotNull Text from(@NotNull Plugin plugin, @NotNull String text) {
        return new Text(text, List.of(), List.of(), null, plugin);
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
        return new Text(raw, newKeys, newValues, viewer, owner);
    }

    /**
     * Resolves registered placeholders in this text for a player.
     *
     * <p>Placeholders are filled in <em>after</em> parsing, on the component
     * tree, for the same reason {@link #with} works that way: the text itself is
     * identical for everybody, so it is parsed once and shared, and only the
     * values differ per player. Resolving them into the string first would
     * produce a different string per player and miss the parse cache entirely.
     *
     * <pre>{@code
     * Text.of("{letters}Coins: {highlight}%eco_balance:comma%").forPlayer(player).send(player);
     * }</pre>
     *
     * <p>Values are inserted as literal text, so a placeholder cannot inject
     * colour codes into a message.
     *
     * @param player who to resolve for; {@code null} leaves placeholders alone
     * @return a new prepared text; the original is unchanged
     */
    public @NotNull Text forPlayer(Player player) {
        return new Text(raw, keys, values, player, owner);
    }

    /**
     * Builds the component.
     *
     * @return the parsed component, with every substitution applied
     */
    public @NotNull Component build() {
        // The tag is an instruction, not text: it never reaches the screen,
        // a log, or an item name.
        EffectTag.Parsed parsed = EffectTag.parse(raw);

        // Before parsing and before centring: the prefix carries its own colours
        // and its width counts towards a centred line.
        String source = applyPrefix(parsed.message());
        if (parsed.centered()) {
            source = Centering.center(source);
        }
        Component component = TextEngine.parse(source);

        if (viewer != null) {
            List<String> pairs = Placeholders.resolveInto(raw, viewer);
            for (int i = 0; i < pairs.size(); i += 2) {
                String placeholder = pairs.get(i);
                String value = pairs.get(i + 1);
                component = component.replaceText(builder -> builder
                        .matchLiteral(placeholder)
                        .replacement(Component.text(value)));
            }
        }

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
     * Substitutes {@code %prefix%} with the owning plugin's prefix.
     *
     * <p>Done on the string rather than on the component tree, because a prefix
     * is formatting: {@code {primary}&lEXYLIA} has to go through the parser to
     * become bold and coloured, and a value inserted into a component is
     * deliberately literal.
     *
     * @param message the message, with the effect tag already removed
     * @return the message with the prefix in place
     */
    private String applyPrefix(String message) {
        if (owner == null || message.indexOf(PREFIX_TOKEN) < 0) {
            return message;
        }
        String prefix = Prefixes.get(owner);
        return prefix == null ? message : message.replace(PREFIX_TOKEN, prefix);
    }

    /**
     * Sends this text as a chat message.
     *
     * <p>Must be called on the thread that owns the receiver.
     *
     * @param receiver who to send it to
     */
    public void send(@NotNull CommandSender receiver) {
        // A console cannot hear a sound or see a firework, so it just gets
        // the message. The tag itself is dropped by build(), wherever the
        // text ends up.
        if (receiver instanceof Player player) {
            EffectTag.Parsed parsed = EffectTag.parse(raw);
            if (parsed.hasEffects()) {
                EffectTagPlayer.play(parsed, player);
            }
        }
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
