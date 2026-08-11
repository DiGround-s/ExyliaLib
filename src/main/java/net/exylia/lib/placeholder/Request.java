package net.exylia.lib.placeholder;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Everything a placeholder might need to produce a value.
 *
 * <p>There is one resolver type in this module rather than one per situation.
 * A resolver takes a request and returns a value; whether it looks at the
 * viewer, the target, the arguments or nothing at all is its own business. That
 * is what removes the need to pick a scope, register in a different place for
 * each kind, and write the same wiring four times.
 *
 * <h2>Who is who</h2>
 * <ul>
 *   <li>{@link #viewer()} is who will read the text. Almost every placeholder
 *       only needs this.</li>
 *   <li>{@link #target()} is who the text is <em>about</em>. It is the same as
 *       the viewer unless the text is relational, such as a name tag one player
 *       sees above another.</li>
 * </ul>
 *
 * <h2>Arguments</h2>
 * A placeholder written as {@code %clan_top_3%} where {@code clan_top} is
 * registered arrives with {@code ["3"]} in {@link #args()}, already split.
 * {@link #arg(int, int)} converts them, so a resolver never parses strings by
 * hand:
 *
 * <pre>{@code
 * group.add("top", request -> leaderboard.get(request.arg(0, 1)));
 * }</pre>
 *
 * <h2>Extra data</h2>
 * {@link #get(String, Class)} carries values the caller attached for this render,
 * such as the arena a scoreboard belongs to. It replaces the separate "context"
 * placeholder kind entirely.
 *
 * @param viewer who reads the text, or {@code null} when there is none
 * @param target who the text is about; defaults to the viewer
 * @param args   arguments parsed from the placeholder name
 * @param data   extra values attached by the caller
 * @since 1.3.0
 */
public record Request(@Nullable Player viewer,
                      @Nullable OfflinePlayer target,
                      @NotNull List<String> args,
                      @NotNull Map<String, Object> data) {

    /** A request with no viewer, no target and no data. */
    public static final Request EMPTY = new Request(null, null, List.of(), Map.of());

    /**
     * Returns the viewer, or throws when there is none.
     *
     * <p>For resolvers that genuinely cannot work without a player. Prefer
     * returning {@code null} from the resolver over throwing, so the caller sees
     * a fallback rather than an error.
     *
     * @return the viewer
     * @throws IllegalStateException when the request has no viewer
     */
    public @NotNull Player requireViewer() {
        if (viewer == null) {
            throw new IllegalStateException("This placeholder needs a player, but none was given");
        }
        return viewer;
    }

    /**
     * Returns whether there is a viewer.
     *
     * @return {@code true} when a player will read this text
     */
    public boolean hasViewer() {
        return viewer != null;
    }

    /**
     * Returns an argument as text.
     *
     * @param index    zero-based argument position
     * @param fallback returned when the argument is absent
     * @return the argument, or {@code fallback}
     */
    public @NotNull String arg(int index, @NotNull String fallback) {
        return index >= 0 && index < args.size() ? args.get(index) : fallback;
    }

    /**
     * Returns an argument as a whole number.
     *
     * <p>A value that is not a number yields {@code fallback} rather than an
     * exception: the argument comes from a config file a human edited.
     *
     * @param index    zero-based argument position
     * @param fallback returned when the argument is absent or not a number
     * @return the parsed argument, or {@code fallback}
     */
    public int arg(int index, int fallback) {
        if (index < 0 || index >= args.size()) {
            return fallback;
        }
        try {
            return Integer.parseInt(args.get(index).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Returns an argument as a decimal number.
     *
     * @param index    zero-based argument position
     * @param fallback returned when the argument is absent or not a number
     * @return the parsed argument, or {@code fallback}
     */
    public double arg(int index, double fallback) {
        if (index < 0 || index >= args.size()) {
            return fallback;
        }
        try {
            return Double.parseDouble(args.get(index).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Returns how many arguments were given.
     *
     * @return the argument count
     */
    public int argCount() {
        return args.size();
    }

    /**
     * Returns a value the caller attached to this request.
     *
     * @param key  the name it was attached under
     * @param type the expected type
     * @param <T>  the value type
     * @return the value, or {@code null} when absent or of another type
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(@NotNull String key, @NotNull Class<T> type) {
        Object value = data.get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    /**
     * Returns a value the caller attached, or a fallback.
     *
     * @param key      the name it was attached under
     * @param type     the expected type
     * @param fallback returned when absent or of another type
     * @param <T>      the value type
     * @return the value, or {@code fallback}
     */
    public <T> @NotNull T get(@NotNull String key, @NotNull Class<T> type, @NotNull T fallback) {
        T value = get(key, type);
        return value != null ? value : fallback;
    }

    /**
     * Returns whether the text is about somebody other than the reader.
     *
     * @return {@code true} when viewer and target differ
     */
    public boolean isRelational() {
        return viewer != null && target != null && !viewer.getUniqueId().equals(target.getUniqueId());
    }
}
