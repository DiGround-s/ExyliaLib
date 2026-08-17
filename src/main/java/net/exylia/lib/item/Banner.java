package net.exylia.lib.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A banner design, applied to a banner or the banner on a shield.
 *
 * <p>Configurations carry these two ways. Written out, for a design somebody
 * typed:
 *
 * <pre>{@code
 * banner_patterns:
 *   base_color: red
 *   patterns:
 *     - pattern: stripe_top
 *       color: white
 * }</pre>
 *
 * <p>Or as one base64 string, for a design a player built in an editor and the
 * plugin saved. {@link Items} decodes that on the way in, so by the time a
 * design is a {@code Banner} the two are the same thing.
 *
 * @param baseColor the dye colour underneath, such as {@code red}, or {@code null}
 * @param patterns  the layers, in the order they are drawn
 * @since 1.22.0
 */
public record Banner(@Nullable String baseColor, @NotNull List<Layer> patterns) {

    public Banner {
        patterns = List.copyOf(patterns);
    }

    /**
     * One layer of a banner.
     *
     * @param pattern the vanilla pattern key, such as {@code stripe_top}
     * @param colour  the dye colour, such as {@code white}
     */
    public record Layer(@NotNull String pattern, @NotNull String colour) {
    }

    /** Returns whether this design would change anything. */
    public boolean isEmpty() {
        return baseColor == null && patterns.isEmpty();
    }
}
