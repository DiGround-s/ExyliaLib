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
 * <p>Or as a placeholder holding a whole encoded design, for a row whose
 * design is computed per viewer:
 *
 * <pre>{@code
 * banner_design: "%pattern_preview%"
 * }</pre>
 *
 * <p>That third form is {@link #template}, and it is a separate thing on
 * purpose. The other two are known once the file is read; a template is a
 * promise that the design arrives later, and the whole design arrives at once —
 * base colour and an unknown number of layers together. Resolving the fields of
 * a design already read cannot express it: there is nothing to read yet. A
 * shield editor previewing "your current design, plus the layer this row would
 * add" needs exactly this, and so does every menu listing designs players
 * built.
 *
 * @param baseColor the dye colour underneath, such as {@code red}, or {@code null}
 * @param patterns  the layers, in the order they are drawn
 * @param template  the placeholder carrying the encoded design, or {@code null}
 * @since 1.22.0
 */
public record Banner(@Nullable String baseColor, @NotNull List<Layer> patterns,
                     @Nullable String template) {

    public Banner {
        patterns = List.copyOf(patterns);
    }

    /**
     * A design known in full.
     *
     * @param baseColor the dye colour underneath, or {@code null}
     * @param patterns  the layers, in the order they are drawn
     */
    public Banner(@Nullable String baseColor, @NotNull List<Layer> patterns) {
        this(baseColor, patterns, null);
    }

    /**
     * A design that arrives as an encoded value when the item is drawn.
     *
     * @param placeholder the text holding it, such as {@code %pattern_preview%}
     * @return the template
     * @since 1.37.0
     */
    public static @NotNull Banner template(@NotNull String placeholder) {
        return new Banner(null, List.of(), placeholder);
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
        return baseColor == null && patterns.isEmpty() && template == null;
    }

    /**
     * Returns whether this design needs a viewer before it can be resolved.
     *
     * <p>A template always does. So does a design somebody spelled out with a
     * placeholder in one of its fields, which is what {@code TraitApplier}
     * already resolves layer by layer.
     *
     * @return whether the design depends on who is looking
     * @since 1.37.0
     */
    public boolean isDynamic() {
        if (template != null) {
            return true;
        }
        if (baseColor != null && baseColor.indexOf('%') >= 0) {
            return true;
        }
        for (Layer layer : patterns) {
            if (layer.pattern().indexOf('%') >= 0 || layer.colour().indexOf('%') >= 0) {
                return true;
            }
        }
        return false;
    }
}
