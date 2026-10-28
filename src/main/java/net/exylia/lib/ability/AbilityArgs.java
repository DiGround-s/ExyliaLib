package net.exylia.lib.ability;

import net.exylia.lib.input.InputParser;
import net.exylia.lib.util.Expressions;
import net.exylia.lib.util.Formula;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What one line of {@code conditions:} or {@code effects:} wrote, read by a
 * {@link AbilityEffect.Factory} or {@link AbilityCondition.Factory}.
 *
 * <pre>{@code
 * - damage: { target: victim, amount: 0.5 * level, every: 0.5s, times: 4 }
 * - sequence: bleed
 * }</pre>
 *
 * <p>The first line is a map: read it by key. The second is a single value:
 * {@link #value()} reads it, and the keyed readers see it under the key
 * {@code value}.
 *
 * <h2>Nothing is dropped</h2>
 * Every reader remembers the key it read. A key nobody read while compiling is
 * reported as unknown, once, and stays in the ability's {@link Ability#source()}
 * so the file and the editor keep it. A value that cannot be read is reported
 * and the reader's default is used, so a typo costs the tuning, not the feature.
 *
 * <p>Used while compiling, on one thread; not thread safe.
 *
 * @since 1.385.0
 */
public final class AbilityArgs {

    /** The variable every formula may use. */
    public static final String LEVEL = "level";

    private final String name;
    private final @Nullable Object scalar;
    private final Map<String, Object> map;
    private final Consumer<String> problems;
    private final Set<String> read = new LinkedHashSet<>();

    AbilityArgs(@NotNull String name, @Nullable Object raw, @NotNull Consumer<String> problems) {
        this.name = name;
        this.problems = problems;
        if (raw instanceof Map<?, ?> written) {
            Map<String, Object> copy = new LinkedHashMap<>();
            written.forEach((key, value) -> copy.put(String.valueOf(key).toLowerCase(Locale.ROOT), value));
            this.map = copy;
            this.scalar = null;
        } else {
            this.map = raw == null ? Map.of() : Map.of("value", raw);
            this.scalar = raw;
        }
    }

    /** The effect's or condition's name, as registered. */
    public @NotNull String name() {
        return name;
    }

    /** The single value when the line wrote one, {@code null} when it wrote a map. */
    public @Nullable Object value() {
        if (scalar != null) {
            read.add("value");
        }
        return scalar;
    }

    /** Whether the line wrote this key. */
    public boolean has(@NotNull String key) {
        return map.containsKey(key);
    }

    /** The raw value under a key, marking it read. */
    public @Nullable Object raw(@NotNull String key) {
        read.add(key);
        return map.get(key);
    }

    /** Text, or the default when absent. */
    public @NotNull String string(@NotNull String key, @NotNull String fallback) {
        Object value = raw(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    /** A plain number, or the default when absent or unreadable. */
    public double number(@NotNull String key, double fallback) {
        Object value = raw(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim().replace("%", ""));
        } catch (NumberFormatException unreadable) {
            problem("'" + key + "' is not a number (" + value + "); using " + fallback);
            return fallback;
        }
    }

    /** A whole number, or the default. */
    public int integer(@NotNull String key, int fallback) {
        return (int) Math.round(number(key, fallback));
    }

    /** {@code true}/{@code false}/{@code yes}/{@code no}, or the default. */
    public boolean flag(@NotNull String key, boolean fallback) {
        Object value = raw(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> {
                problem("'" + key + "' is not true or false (" + value + "); using " + fallback);
                yield fallback;
            }
        };
    }

    /**
     * A formula of {@code level}, compiled once.
     *
     * <p>A trailing {@code %} is ignored, so {@code chance: 15%} reads as 15.
     * An unreadable formula is reported and the default is used.
     *
     * @param key      the key
     * @param fallback the formula to use when absent or unreadable; must be readable
     * @return the compiled formula
     */
    public @NotNull Formula formula(@NotNull String key, @NotNull String fallback) {
        Object value = raw(key);
        if (value == null) {
            return Expressions.compile(fallback, LEVEL);
        }
        return compile(String.valueOf(value), fallback, key);
    }

    /**
     * A length of time: {@code 8s}, {@code 500ms}, {@code 2m}, a plain number
     * of seconds, or a formula of {@code level} in seconds.
     *
     * @param key      the key
     * @param fallback seconds when absent or unreadable
     * @return the compiled formula, in seconds
     */
    public @NotNull Formula seconds(@NotNull String key, double fallback) {
        Object value = raw(key);
        if (value == null) {
            return Expressions.compile(Double.toString(fallback), LEVEL);
        }
        return seconds(String.valueOf(value), fallback, key);
    }

    /** A fixed length of time, for keys that are never formulas; the default when absent or unreadable. */
    public @NotNull Duration duration(@NotNull String key, @NotNull Duration fallback) {
        Object value = raw(key);
        if (value == null) {
            return fallback;
        }
        Duration parsed = InputParser.duration().parse(String.valueOf(value).trim()).value();
        if (parsed == null) {
            problem("'" + key + "' is not a length of time (" + value + "); using " + fallback.toMillis() + "ms");
            return fallback;
        }
        return parsed;
    }

    /** A list of text: a YAML list, or one value. Empty when absent. */
    public @NotNull List<String> strings(@NotNull String key) {
        Object value = raw(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(String.valueOf(item).trim()));
            return Collections.unmodifiableList(out);
        }
        return List.of(String.valueOf(value).trim());
    }

    /** Reports a problem with this line; the message is prefixed with its name. */
    public void problem(@NotNull String message) {
        problems.accept(name + ": " + message);
    }

    /** The keys the line wrote that nobody read. */
    @NotNull Set<String> unread() {
        Set<String> unread = new LinkedHashSet<>(map.keySet());
        unread.removeAll(read);
        return unread;
    }

    /** The keys the line wrote or a reader asked for, for the editor's form. */
    @NotNull Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>(read);
        keys.addAll(map.keySet());
        return keys;
    }

    Formula compile(String text, String fallback, String key) {
        String written = text.trim();
        if (written.endsWith("%")) {
            written = written.substring(0, written.length() - 1);
        }
        try {
            return Expressions.compile(written, LEVEL);
        } catch (IllegalArgumentException unreadable) {
            problem("'" + key + "' cannot be read (" + text + ": " + unreadable.getMessage() + "); using " + fallback);
            return Expressions.compile(fallback, LEVEL);
        }
    }

    Formula seconds(String text, double fallback, String key) {
        String written = text.trim();
        if (!written.isEmpty() && Character.isLetter(written.charAt(written.length() - 1))) {
            Duration parsed = InputParser.duration().parse(written).value();
            if (parsed != null) {
                return Expressions.compile(Double.toString(parsed.toMillis() / 1000.0), LEVEL);
            }
        }
        return compile(written, Double.toString(fallback), key);
    }
}
