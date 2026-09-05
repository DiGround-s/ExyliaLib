package net.exylia.lib.text.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The drop shadow under every line, when the server asks for one.
 *
 * <p>A class of its own, and the only one naming {@link ShadowColor}: the
 * component the colour rides on arrived in Minecraft 1.21.4, and a server
 * older than that has an Adventure without it. Nothing here is touched until
 * a shadow is actually configured, so the text module loads and runs on a
 * server that could not draw one.
 */
public final class Shadows {

    /** What a server writes to mean "no shadow at all" rather than "leave it alone". */
    private static final String NONE = "none";

    /** What a server writes to mean "darken whatever the letter already is". */
    private static final String AUTO = "auto";

    /**
     * How much of the letter's own colour an automatic shadow keeps.
     *
     * <p>A quarter, which is what the client does when nothing says otherwise.
     * The point of writing it down is being able to write a different one.
     */
    public static final float VANILLA_FACTOR = 0.25f;

    /** White, which is what a letter with no colour of its own is drawn as. */
    private static final int WHITE = 0xFFFFFF;

    /** Whether this server's Adventure knows what a shadow colour is. */
    private static final boolean AVAILABLE = available();

    private Shadows() {
    }

    /**
     * What to draw under a line.
     *
     * <p>Exactly one of the two is set: a colour every line shares, or the
     * fraction of its own colour each part of a line keeps.
     *
     * @param color  the shadow as packed ARGB, or {@code null} for an automatic one
     * @param factor how much of the letter's colour to keep, when automatic
     */
    public record Spec(@Nullable Integer color, float factor) {

        public boolean automatic() {
            return color == null;
        }
    }

    private static boolean available() {
        try {
            Class.forName("net.kyori.adventure.text.format.ShadowColor");
            return true;
        } catch (ClassNotFoundException | LinkageError tooOld) {
            return false;
        }
    }

    /** Whether a shadow can be drawn here at all. */
    public static boolean supported() {
        return AVAILABLE;
    }

    /**
     * Reads a configured shadow.
     *
     * <p>{@code #rrggbb} is one opaque colour under every line,
     * {@code #rrggbbaa} says how strong, {@code auto} darkens each part of a
     * line by its own colour — so a gradient casts a gradient — and
     * {@code auto:0.5} says by how much. {@code none} removes the shadow the
     * client would draw by itself, and anything empty leaves every line
     * exactly as it was.
     *
     * <p>The alpha goes last, the way MiniMessage's own {@code <shadow>} tag
     * and every generator that writes one spell it — a server copying a
     * colour out of one and into the config should get the colour they saw.
     *
     * @param written the value as configured
     * @return what to draw, or {@code null} to change nothing
     */
    public static @Nullable Spec read(@Nullable String written) {
        if (written == null) return null;
        String value = written.trim();
        if (value.isEmpty()) return null;
        if (value.equalsIgnoreCase(NONE)) return new Spec(0, 0f);
        if (value.toLowerCase(Locale.ROOT).startsWith(AUTO)) return automatic(value);
        if (value.startsWith("#")) value = value.substring(1);
        try {
            return switch (value.length()) {
                // Opaque unless the value says otherwise: a shadow written as
                // six digits is the colour somebody picked, not a transparent one.
                case 6 -> new Spec(0xFF000000 | Integer.parseInt(value, 16), 0f);
                case 8 -> {
                    int rgba = (int) Long.parseLong(value.toLowerCase(Locale.ROOT), 16);
                    yield new Spec((rgba >>> 8) | (rgba << 24), 0f);
                }
                default -> null;
            };
        } catch (NumberFormatException notAColour) {
            return null;
        }
    }

    /** {@code auto} on its own, or {@code auto:0.4} with a factor of somebody's choosing. */
    private static @Nullable Spec automatic(String value) {
        int colon = value.indexOf(':');
        if (colon < 0) {
            return value.length() == AUTO.length() ? new Spec(null, VANILLA_FACTOR) : null;
        }
        try {
            float factor = Float.parseFloat(value.substring(colon + 1).trim());
            if (factor < 0f || factor > 1f || Float.isNaN(factor)) return null;
            return new Spec(null, factor);
        } catch (NumberFormatException notAFactor) {
            return null;
        }
    }

    /**
     * Puts the shadow under a line that does not carry one of its own.
     *
     * @param component what to draw the shadow under
     * @param spec      what to draw
     * @return the same line, shadowed
     */
    public static @NotNull Component apply(@NotNull Component component, @NotNull Spec spec) {
        if (!AVAILABLE) return component;
        if (!spec.automatic()) {
            // One colour for the whole line: only the outermost style is set,
            // since the client inherits it down the tree, and a <shadow>
            // somebody wrote inside still wins where they wrote it.
            return component.shadowColorIfAbsent(ShadowColor.shadowColor(spec.color()));
        }
        return darken(component, spec.factor(), WHITE);
    }

    /**
     * Darkens each part of a line by the colour that part is drawn in.
     *
     * <p>Walked rather than set once at the top, because that is the whole
     * point: a gradient is one component per character, each its own colour,
     * and each wants its own shadow. A part with no colour of its own is
     * drawn in its parent's, so the parent's is what darkens it.
     *
     * @param inherited the colour in force here, as packed RGB
     */
    private static Component darken(Component component, float factor, int inherited) {
        TextColor own = component.color();
        int color = own == null ? inherited : own.value();
        Component out = component.shadowColor() == null
                ? component.shadowColor(ShadowColor.shadowColor(scale(color, factor)))
                : component;
        List<Component> children = out.children();
        if (children.isEmpty()) return out;
        List<Component> darkened = new ArrayList<>(children.size());
        for (Component child : children) {
            darkened.add(darken(child, factor, color));
        }
        return out.children(darkened);
    }

    /** A colour with each channel scaled, opaque, as packed ARGB. */
    static int scale(int rgb, float factor) {
        int red = Math.round(((rgb >> 16) & 0xFF) * factor);
        int green = Math.round(((rgb >> 8) & 0xFF) * factor);
        int blue = Math.round((rgb & 0xFF) * factor);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
