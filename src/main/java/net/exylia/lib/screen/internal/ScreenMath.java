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
     * A {@code linear-gradient(...)}: its colours from the start side to the end, spread evenly; {@code vertical}
     * when it runs top to bottom (or bottom to top, with the colours reversed).
     */
    public record Gradient(boolean vertical, int[] stops) {

        /** The colour {@code t} of the way along (0 to 1), channels and alpha mixed straight. */
        public int at(double t) {
            if (stops.length == 1) return stops[0];
            double at = clamp(t, 0, 1) * (stops.length - 1);
            int i = Math.min(stops.length - 2, (int) at);
            double f = at - i;
            int a = stops[i], b = stops[i + 1];
            int alpha = (int) Math.round((a >>> 24) + ((b >>> 24) - (a >>> 24)) * f);
            return alpha << 24 | (blend(a, b, f) & 0xFFFFFF);
        }
    }

    /**
     * {@code linear-gradient([to bottom|to top|to right|to left|<n>deg,] colour, colour...)}, or {@code null} when
     * {@code value} is not one or a colour does not read. Angles turn to the nearest side (180deg is to bottom).
     */
    public static Gradient gradient(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (!v.toLowerCase(java.util.Locale.ROOT).startsWith("linear-gradient(") || !v.endsWith(")")) return null;
        String[] parts = v.substring(16, v.length() - 1).split(",");
        int from = 0;
        String side = "bottom";
        String first = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (first.startsWith("to ")) {
            side = first.substring(3).trim();
            from = 1;
        } else if (first.endsWith("deg")) {
            try {
                double deg = ((Double.parseDouble(first.substring(0, first.length() - 3).trim()) % 360) + 360) % 360;
                side = deg < 45 || deg >= 315 ? "top" : deg < 135 ? "right" : deg < 225 ? "bottom" : "left";
            } catch (NumberFormatException bad) {
                return null;
            }
            from = 1;
        }
        int count = parts.length - from;
        if (count < 1) return null;
        int[] stops = new int[count];
        for (int i = 0; i < count; i++) {
            int c = argb(parts[from + i], 1);
            if (c == 1) return null;
            stops[i] = c;
        }
        boolean reverse = side.equals("top") || side.equals("left");
        if (!side.equals("bottom") && !side.equals("right") && !reverse) return null;
        if (reverse) {
            for (int i = 0; i < count / 2; i++) {
                int t = stops[i];
                stops[i] = stops[count - 1 - i];
                stops[count - 1 - i] = t;
            }
        }
        return new Gradient(side.equals("bottom") || side.equals("top"), stops);
    }

    /** How many bands a gradient {@code length} pixels long is drawn as: one each five pixels, 2 to 12. */
    public static int bands(double length) {
        return (int) clamp(Math.round(length / 5), 2, 12);
    }

    /** {@code top} laid over {@code bottom} (treated as opaque): the opaque colour the eye sees. */
    public static int over(int top, int bottom) {
        int a = top >>> 24;
        if (a == 0xFF) return top;
        int r = mix(top >> 16, bottom >> 16, a);
        int g = mix(top >> 8, bottom >> 8, a);
        int b = mix(top, bottom, a);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int mix(int top, int bottom, int alpha) {
        return ((top & 0xFF) * alpha + (bottom & 0xFF) * (255 - alpha) + 127) / 255;
    }

    /**
     * What is left of a rect with a hole taken out, each {centre x, centre y, width, height}: up to four rects, sides
     * first. All are centred, y up.
     */
    public static java.util.List<double[]> around(double x, double y, double w, double h,
                                                  double hx, double hy, double hw, double hh) {
        double left = x - w / 2, right = x + w / 2, bottom = y - h / 2, top = y + h / 2;
        double holeLeft = Math.max(left, hx - hw / 2), holeRight = Math.min(right, hx + hw / 2);
        double holeBottom = Math.max(bottom, hy - hh / 2), holeTop = Math.min(top, hy + hh / 2);
        java.util.List<double[]> out = new java.util.ArrayList<>(4);
        for (double[] p : new double[][]{{left, holeLeft, bottom, top}, {holeRight, right, bottom, top},
                {holeLeft, holeRight, holeTop, top}, {holeLeft, holeRight, bottom, holeBottom}}) {
            if (p[1] - p[0] >= 1e-4 && p[3] - p[2] >= 1e-4) {
                out.add(new double[]{(p[0] + p[1]) / 2, (p[2] + p[3]) / 2, p[1] - p[0], p[3] - p[2]});
            }
        }
        return out;
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

    /** Part of the way from one opaque colour to another, channel by channel; opaque. */
    static int blend(int from, int to, double f) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
