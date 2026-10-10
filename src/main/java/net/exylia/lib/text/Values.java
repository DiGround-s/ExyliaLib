package net.exylia.lib.text;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a message, a menu or an effect is about: a named bag of placeholder values.
 *
 * <p>Built in one expression and handed to whichever of them needs it:
 *
 * <pre>{@code
 * Values values = Values.of("mine", mine.id()).putText("owner", typedName);
 *
 * messages.send(player, config.created(), values);          // PluginMessages
 * menus.open(player, "mine_list", values.map());            // a menu context
 * inputs.confirm(player, values.apply(config.deletePrompt())); // a plain string
 * }</pre>
 *
 * <p>Those take the values in three shapes — {@link Text} one substitution at a
 * time, a menu a whole {@code Map}, a prompt a finished string — and a screen
 * usually feeds more than one. Building the bag once is what stops a message
 * and the menu it opens from drifting apart.
 *
 * <h2>Whose value it is</h2>
 * {@link #put} is for a value the server wrote — a configured display name, a
 * number, a formatted price — and its formatting is honoured. {@link #putText}
 * is for something a player typed: it reaches the screen exactly as typed,
 * whichever of the three shapes carries it. Choosing by whose the value is,
 * not by its type, is the same rule {@link Text#with} and
 * {@link Text#withFormatted} follow.
 *
 * <p>Names are written without percent signs; each becomes {@code %name%}. A
 * {@code null} value becomes an empty string, because a placeholder left
 * unfilled is shown raw to a player.
 *
 * <p>Not thread-safe and not meant to be: one is built, used and dropped inside
 * a single handler.
 *
 * @since 1.266.0
 */
public final class Values {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private Values() {
    }

    /** An empty bag. */
    public static @NotNull Values of() {
        return new Values();
    }

    /** A bag holding one server-written value. */
    public static @NotNull Values of(@NotNull String name, @Nullable Object value) {
        return of().put(name, value);
    }

    /**
     * Adds a value the server wrote; its formatting is honoured.
     *
     * @param name  the placeholder name, without percent signs
     * @param value what it stands for; {@code null} becomes an empty string
     * @return this bag
     */
    public @NotNull Values put(@NotNull String name, @Nullable Object value) {
        values.put(name, value == null ? "" : value);
        return this;
    }

    /**
     * Adds a value somebody typed; it reaches the screen exactly as typed.
     *
     * @param name  the placeholder name, without percent signs
     * @param value what it stands for; {@code null} becomes an empty string
     * @return this bag
     */
    public @NotNull Values putText(@NotNull String name, @Nullable Object value) {
        values.put(name, new Literal(value == null ? "" : String.valueOf(value)));
        return this;
    }

    /**
     * The values, as a menu context takes them.
     *
     * <p>A typed value is held as an object whose {@code toString} is
     * {@link #inert inert}, so a menu that parses its context still shows it as
     * typed. The map is this bag's own: writing to it writes to the bag.
     */
    public @NotNull Map<String, Object> map() {
        return values;
    }

    /** Whether nothing was added. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Substitutes these values into a line, leaving everything else alone.
     *
     * <p>For text that is assembled before it is parsed — a prompt, an effect
     * line, a hologram — where the line still carries its colours and its
     * {@code %papi%} placeholders. A typed value goes in {@link #inert inert}.
     *
     * @param text the line; {@code null} becomes an empty string
     * @return the line with these values in it
     */
    public @NotNull String apply(@Nullable String text) {
        if (text == null) return "";
        String filled = text;
        for (Map.Entry<String, Object> value : values.entrySet()) {
            filled = filled.replace("%" + value.getKey() + "%", String.valueOf(value.getValue()));
        }
        return filled;
    }

    /**
     * These values substituted into a prepared text: server-written ones with
     * {@link Text#withFormatted}, typed ones with {@link Text#with}.
     *
     * @param text the prepared text
     * @return a new prepared text; the original is unchanged
     */
    public @NotNull Text applyTo(@NotNull Text text) {
        Text filled = text;
        for (Map.Entry<String, Object> value : values.entrySet()) {
            String placeholder = "%" + value.getKey() + "%";
            filled = value.getValue() instanceof Literal literal
                    ? filled.with(placeholder, literal.text())
                    : filled.withFormatted(placeholder, value.getValue());
        }
        return filled;
    }

    /**
     * Typed text made safe to splice into a line that is parsed afterwards.
     *
     * <p>{@link Text#escape} plus one thing it cannot know about: a
     * {@code %placeholder%} spliced into a line would be resolved when the line
     * is shown, so a player who types {@code %player_ip%} would read somebody's
     * address. Breaking the percent sign with an empty tag stops that and
     * draws nothing.
     *
     * @param typed what somebody typed; {@code null} becomes an empty string
     * @return the same text, inert to the parser and to placeholders
     */
    public static @NotNull String inert(@Nullable String typed) {
        String escaped = Text.escape(typed);
        return escaped.indexOf('%') < 0 ? escaped : escaped.replace("%", "%<b></b>");
    }

    /** A typed value: literal in a {@link Text}, inert when spliced into a string. */
    record Literal(String text) {
        @Override
        public String toString() {
            return inert(text);
        }
    }
}
