package net.exylia.lib.text.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.jetbrains.annotations.Nullable;

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

    /** Whether this server's Adventure knows what a shadow colour is. */
    private static final boolean AVAILABLE = available();

    private Shadows() {
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
     * <p>{@code #rrggbb} is opaque, {@code #rrggbbaa} says how strong,
     * {@code none} removes the shadow the client would draw by itself, and
     * anything empty leaves every line exactly as it was.
     *
     * <p>The alpha goes last, the way MiniMessage's own {@code <shadow>} tag
     * and every generator that writes one spell it — a server copying a
     * colour out of one and into the config should get the colour they saw.
     *
     * @param written the value as configured
     * @return the shadow as packed ARGB, or {@code null} to change nothing
     */
    public static @Nullable Integer read(@Nullable String written) {
        if (written == null) return null;
        String value = written.trim();
        if (value.isEmpty()) return null;
        if (value.equalsIgnoreCase(NONE)) return 0;
        if (value.startsWith("#")) value = value.substring(1);
        try {
            return switch (value.length()) {
                // Opaque unless the value says otherwise: a shadow written as
                // six digits is the colour somebody picked, not a transparent one.
                case 6 -> 0xFF000000 | Integer.parseInt(value, 16);
                case 8 -> {
                    int rgba = (int) Long.parseLong(value.toLowerCase(Locale.ROOT), 16);
                    yield (rgba >>> 8) | (rgba << 24);
                }
                default -> null;
            };
        } catch (NumberFormatException notAColour) {
            return null;
        }
    }

    /**
     * Puts the shadow under a line that does not carry one of its own.
     *
     * <p>Only the outermost style is set: the client inherits it down the
     * tree, so a {@code <shadow>} somebody wrote inside the line still wins
     * where they wrote it.
     */
    public static Component apply(Component component, int argb) {
        return component.shadowColorIfAbsent(ShadowColor.shadowColor(argb));
    }
}
