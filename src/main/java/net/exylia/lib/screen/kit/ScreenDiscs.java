package net.exylia.lib.screen.kit;

import net.exylia.lib.screen.internal.ScreenMath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Round pictures for table games, drawn in the server's palette: a casino chip and a wheel of coloured segments. Each is
 * ARGB pixels row by row, for {@link net.exylia.lib.screen.Node#image} or a scene sprite's {@code image}. Colours are
 * written as on a screen: {@code {error}}, {@code {primary}40}, {@code var(--success)}, {@code #rrggbb}.
 *
 * <pre>{@code
 * Node chip = Node.image(16, 16, ScreenDiscs.chip(16, "{error}", "{highlight}"));
 * }</pre>
 *
 * Markup draws a chip with {@code <img chip="{error}" size="16" ring="{highlight}">}.
 *
 * @since 1.598.0
 */
public final class ScreenDiscs {

    private static final int WHITE = 0xFFF4EEFF;

    private ScreenDiscs() {
    }

    /**
     * A casino chip: a disc in {@code face} with six light notches round its edge and a thin inner ring.
     *
     * @param ring an outer ring round it (the chip picked), or {@code null} for none
     */
    public static int @NotNull [] chip(int size, @NotNull String face, @Nullable String ring) {
        int fill = ScreenMath.argb(face, 0xFF8A51C4) | 0xFF000000;
        int edge = ring == null || ring.isBlank() ? 0 : ScreenMath.argb(ring, 0) | 0xFF000000;
        int dark = shade(fill, 0.62);
        int[] argb = new int[size * size];
        double c = (size - 1) / 2.0, outer = size / 2.0;
        double band = Math.max(1.5, size / 7.0);
        double inner = outer * 0.6;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                double d = Math.hypot(col - c, row - c);
                if (d > outer - 0.25) continue;
                int colour = fill;
                double from = edge != 0 ? outer - 1.25 : outer;
                if (edge != 0 && d > outer - 1.25) {
                    colour = edge;
                } else if (d > from - band) {
                    // Twelve slices round the edge, every other one a light notch.
                    double turn = (Math.toDegrees(Math.atan2(col - c, c - row)) + 375) % 360;
                    colour = ((int) (turn / 30)) % 2 == 0 ? WHITE : dark;
                } else if (Math.abs(d - inner) < 0.5) {
                    colour = mix(fill, WHITE, 0.45);
                }
                argb[row * size + col] = colour;
            }
        }
        return argb;
    }

    /**
     * A wheel seen from above: {@code segments} round it clockwise from the top, a rim outside them and a hub inside.
     *
     * @param hole how far out the segments start, as a share of the radius (0.55 leaves room for a number in the hub)
     */
    public static int @NotNull [] wheel(int size, @NotNull List<String> segments, @NotNull String rim, @NotNull String hub, double hole) {
        int[] colours = segments.stream().mapToInt(s -> ScreenMath.argb(s, 0xFF333333) | 0xFF000000).toArray();
        int rimColour = ScreenMath.argb(rim, 0) | 0xFF000000;
        int hubColour = ScreenMath.argb(hub, 0);
        int[] argb = new int[size * size];
        double c = (size - 1) / 2.0, outer = size / 2.0;
        double rimFrom = outer - Math.max(1.5, size / 24.0);
        double start = outer * Math.max(0.1, Math.min(0.9, hole));
        double slice = 360.0 / Math.max(1, colours.length);
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                double d = Math.hypot(col - c, row - c);
                if (d > outer - 0.25) continue;
                int colour;
                if (d > rimFrom) colour = rimColour;
                else if (d > start) {
                    double turn = (Math.toDegrees(Math.atan2(col - c, c - row)) + 360 + slice / 2) % 360;
                    colour = colours.length == 0 ? rimColour : colours[(int) (turn / slice) % colours.length];
                    // The inner edge of the segments a shade darker, so the ring reads as a bowl.
                    if (d < start + 1) colour = shade(colour, 0.7);
                } else if (d > start - 1) colour = rimColour;
                else colour = hubColour;
                argb[row * size + col] = colour;
            }
        }
        return argb;
    }

    private static int shade(int argb, double by) {
        int r = (int) (((argb >> 16) & 0xFF) * by), g = (int) (((argb >> 8) & 0xFF) * by), b = (int) ((argb & 0xFF) * by);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
