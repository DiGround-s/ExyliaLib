package net.exylia.lib.screen.internal;

import net.exylia.lib.text.Colors;
import net.kyori.adventure.text.format.TextColor;

/**
 * The arithmetic a screen needs, kept pure so it is tested on its own.
 *
 * <p>Internal: free to change without notice.
 */
public final class ScreenMath {

    private ScreenMath() {
    }

    /**
     * A colour as ARGB: a palette token ({@code {primary}}), a token with a hex alpha ({@code {primary}40}),
     * {@code #rrggbb} (opaque) or {@code #aarrggbb}; {@code fallback} for anything else, {@code 0} (nothing) for
     * {@code null}, blank or {@code none}.
     */
    public static int argb(String colour, int fallback) {
        if (colour == null || colour.isBlank()) return 0;
        String value = colour.trim();
        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("transparent")) return 0;
        if (value.startsWith("{")) {
            int end = value.indexOf('}');
            if (end < 0) return fallback;
            TextColor token = Colors.get(value.substring(1, end));
            String alpha = value.substring(end + 1);
            if (token == null || !(alpha.isEmpty() || alpha.matches("[0-9a-fA-F]{2}"))) return fallback;
            int a = alpha.isEmpty() ? 0xFF : Integer.parseInt(alpha, 16);
            return a << 24 | token.value();
        }
        if (value.startsWith("#")) value = value.substring(1);
        try {
            if (value.length() == 6) return 0xFF000000 | Integer.parseInt(value, 16);
            if (value.length() == 8) return (int) Long.parseLong(value, 16);
        } catch (NumberFormatException ignored) {
            // falls through
        }
        return fallback;
    }

    /**
     * How far the view turned sideways between two packets, in degrees from -180 to 180. The client never wraps
     * its yaw and the server does, so a raw difference can be a whole turn off.
     */
    public static double turn(float from, float to) {
        return ((to - from) % 360 + 540) % 360 - 180;
    }

    /** {@code value} held between {@code min} and {@code max}. */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
