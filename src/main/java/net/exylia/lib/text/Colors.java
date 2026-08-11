package net.exylia.lib.text;

import net.exylia.lib.text.internal.TextEngine;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Access to the palette that {@code {token}} names resolve to.
 *
 * <p>Most code never needs this: writing {@code {primary}} inside a message is
 * the normal way to use a colour. This is for the cases where a colour is needed
 * as a value rather than as text, such as tinting a boss bar or a particle.
 *
 * <pre>{@code
 * TextColor accent = Colors.get("accent");
 * }</pre>
 *
 * @since 1.2.0
 */
public final class Colors {

    private Colors() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns a palette colour by name.
     *
     * @param token the token name, such as {@code primary} or {@code error},
     *              written without braces
     * @return the colour, or {@code null} if no such token exists
     */
    public static @Nullable TextColor get(@NotNull String token) {
        String tag = TextEngine.tokens().get(token);
        if (tag == null) {
            return null;
        }
        // Tokens are stored as MiniMessage tags, so strip the angle brackets.
        String value = tag.substring(1, tag.length() - 1);
        return value.startsWith("#") ? TextColor.fromHexString(value) : null;
    }

    /**
     * Returns a palette colour, or a fallback when the token is unknown.
     *
     * @param token    the token name, without braces
     * @param fallback returned when the token does not exist
     * @return the colour
     */
    public static @NotNull TextColor get(@NotNull String token, @NotNull TextColor fallback) {
        TextColor color = get(token);
        return color != null ? color : fallback;
    }

    /**
     * Applies a palette, replacing the active one.
     *
     * <p>Called by ExyliaLib when {@code colors.yml} is loaded or reloaded.
     * Consumers do not need to call this: editing that file is how a server
     * changes its colours.
     *
     * @param palette the palette to apply
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void apply(@NotNull Palette palette) {
        TextEngine.palette(palette);
    }

    /**
     * Returns every token name currently defined.
     *
     * <p>Includes the {@code snake_case} names and their {@code camelCase}
     * aliases.
     *
     * @return an immutable set of names
     */
    public static @NotNull Set<String> names() {
        return TextEngine.tokens().keySet();
    }
}
