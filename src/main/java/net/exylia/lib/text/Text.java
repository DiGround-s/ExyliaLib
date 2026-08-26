package net.exylia.lib.text;

import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.placeholder.Request;
import net.exylia.lib.placeholder.Template;
import net.exylia.lib.placeholder.internal.CompiledTemplate;
import net.exylia.lib.placeholder.internal.ValueRenderer;
import net.exylia.lib.text.internal.EffectTag;
import net.exylia.lib.text.internal.EffectTagPlayer;
import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** A value to substitute: the exact text it replaces and how it is inserted. */
    private record Substitution(String key, String value, boolean formatted) {
    }

    private final List<Substitution> substitutions;

    /** Who the placeholders are resolved for, or {@code null} to leave them alone. */
    private final Player viewer;

    /** The plugin this text belongs to, so {@code %prefix%} knows whose to use. */
    private final Plugin owner;

    private Text(String raw, List<Substitution> substitutions, Player viewer, Plugin owner) {
        this(raw, substitutions, viewer, owner, false);
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
        return new Text(text, List.of(), null, null);
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
        return new Text(text, List.of(), null, plugin);
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
        return substitute(placeholder, value, false);
    }

    /**
     * Substitutes a value that carries its own formatting.
     *
     * <p>For values that come from a config and say what they look like, such
     * as a class's display name written as {@code <#c8c8c8><bold>ARCHER</bold>}.
     * The value goes through the same parser as the message itself, so its
     * colours and styles are honoured — which {@link #with} deliberately
     * refuses, because a value typed by a player is data, not formatting.
     *
     * @param placeholder the exact text to replace, such as {@code %class%}
     * @param value       the value to parse and insert
     * @return a new prepared text; the original is unchanged
     */
    public @NotNull Text withFormatted(@NotNull String placeholder, Object value) {
        return substitute(placeholder, value, true);
    }

    private Text substitute(String placeholder, Object value, boolean formatted) {
        List<Substitution> updated = new ArrayList<>(substitutions.size() + 1);
        updated.addAll(substitutions);
        updated.add(new Substitution(placeholder, value == null ? "" : String.valueOf(value), formatted));
        return new Text(raw, updated, viewer, owner);
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
        return new Text(raw, substitutions, player, owner);
    }

    /**
     * Resolves registered placeholders for a player, honouring formatting in
     * the values they return.
     *
     * <p>For placeholders that answer with a display name from a config — a
     * class shown as {@code <#c8c8c8><bold>ARCHER</bold>} — where inserting the
     * answer as literal text would print the raw tags to chat. Values typed by
     * players should come back through a resolver that strips formatting, not
     * through this.
     *
     * @param player who to resolve for
     * @return a new prepared text; the original is unchanged
     */
    public @NotNull Text forPlayerFormatted(Player player) {
        return new Text(raw, substitutions, player, owner, true);
    }

    /** Whether resolved placeholder values are parsed for formatting. */
    private final boolean resolveFormatted;

    private Text(String raw, List<Substitution> substitutions, Player viewer, Plugin owner,
                 boolean resolveFormatted) {
        this.raw = raw;
        this.substitutions = substitutions;
        this.viewer = viewer;
        this.owner = owner;
        this.resolveFormatted = resolveFormatted;
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

        // Every value is stood in for by a single private-use character before
        // the text is parsed. A gradient colours one character at a time, so
        // "%streak%" written inside one comes back as eight components and
        // matchLiteral on the whole token matches nothing — which is how a
        // streak announcement reached players with the token still in it. A
        // one-character marker is always a component of its own, whatever the
        // formatting around it. The marker does not carry the value, so the
        // parse still caches per template rather than per player.
        List<Marked> marked = new ArrayList<>();
        for (Substitution substitution : allValues()) {
            if (!source.contains(substitution.key())) {
                continue;
            }
            String marker = String.valueOf((char) (MARKER_BASE + marked.size()));
            source = source.replace(substitution.key(), marker);
            marked.add(new Marked(marker, substitution.value(), substitution.formatted()));
        }

        Component component = TextEngine.parse(source);

        // What has to be substituted into a click's command as well as into the
        // text: replaceText only walks what is read, and a run_command is not.
        boolean clickable = source.contains("click:");
        Map<String, String> commands = clickable ? new java.util.LinkedHashMap<>() : Map.of();

        for (Marked value : marked) {
            Component replacement = value.formatted()
                    ? TextEngine.parseUncached(value.value())
                    : Component.text(value.value());
            component = component.replaceText(builder -> builder
                    .matchLiteral(value.marker())
                    .replacement(replacement));
            if (clickable) {
                commands.put(value.marker(), value.value());
            }
        }
        return commands.isEmpty() ? component : fillClicks(component, commands);
    }

    /** Where the private-use markers start, well clear of anything a font draws. */
    private static final char MARKER_BASE = '\uE000';

    /** A value and the marker standing in for it while the text is parsed. */
    private record Marked(String marker, String value, boolean formatted) {
    }

    /**
     * Every value this text substitutes: the resolver's first, then the ones
     * given through {@link #with}.
     */
    private List<Substitution> allValues() {
        if (viewer == null) {
            return substitutions;
        }
        // The names this class substitutes itself are told to the resolver,
        // so a value supplied through with() is not reported as unknown
        // moments before being substituted — which is exactly the false
        // alarm that fired on a live server.
        Template template = Placeholders.compile(raw);
        List<String> triples = template instanceof CompiledTemplate compiled
                ? compiled.resolveTriples(new Request(viewer, viewer, List.of(), Map.of()),
                        resolveFormatted ? FORMATTED_RENDERER : ValueRenderer.LITERAL,
                        handledNames())
                : List.of();
        if (triples.isEmpty()) {
            return substitutions;
        }
        List<Substitution> values = new ArrayList<>(triples.size() / 3 + substitutions.size());
        for (int i = 0; i < triples.size(); i += 3) {
            values.add(new Substitution(triples.get(i), triples.get(i + 1),
                    triples.get(i + 2).equals("formatted")));
        }
        values.addAll(substitutions);
        return values;
    }

    /**
     * Substitutes into the commands and links a click carries.
     *
     * <p>{@code <click:run_command:'/events join %event_id%'>} is the shape
     * every "click to join" line in Exylia is written in, and the command is not
     * text: {@code replaceText} renders what a player reads, so the value landed
     * in the message and the button still ran the placeholder verbatim.
     *
     * <p>The value goes in as written, so a click runs the same command the text
     * shows. Values from players reach this through {@link #with}, which is
     * already the literal side of the split.
     */
    private static Component fillClicks(Component component, Map<String, String> values) {
        Component result = component;
        ClickEvent click = result.clickEvent();
        if (click != null) {
            String command = click.value();
            for (Map.Entry<String, String> value : values.entrySet()) {
                if (command.contains(value.getKey())) {
                    command = command.replace(value.getKey(), value.getValue());
                }
            }
            if (!command.equals(click.value())) {
                result = result.clickEvent(ClickEvent.clickEvent(click.action(), command));
            }
        }
        List<Component> children = result.children();
        if (children.isEmpty()) {
            return result;
        }
        List<Component> filled = new ArrayList<>(children.size());
        for (Component child : children) {
            filled.add(fillClicks(child, values));
        }
        return result.children(filled);
    }

    /** Parses a trusted value, honouring its formatting. */
    private static final ValueRenderer FORMATTED_RENDERER = TextEngine::parseUncached;

    /**
     * The placeholder tokens this text substitutes itself, so the resolver does
     * not report them as unknown.
     */
    private Set<String> handledNames() {
        // The common line has no substitutions and no owner, so there is nothing to
        // exempt and the set would be built empty on every render.
        if (substitutions.isEmpty() && owner == null) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (Substitution substitution : substitutions) {
            String key = substitution.key();
            if (key.length() > 2 && key.startsWith("%") && key.endsWith("%")) {
                names.add(key);
            }
        }
        if (owner != null) {
            // The prefix is substituted on the string before parsing.
            names.add(PREFIX_TOKEN);
        }
        return names;
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
