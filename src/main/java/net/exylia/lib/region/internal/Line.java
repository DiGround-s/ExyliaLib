package net.exylia.lib.region.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One straight bar of an outline, or a marker when both ends are the same point.
 *
 * <p>A value, compared by its coordinates and tone, which is what lets the renderer leave alone
 * every bar that did not change between two frames.
 *
 * @param x1   start x
 * @param y1   start y
 * @param z1   start z
 * @param x2   end x
 * @param y2   end y
 * @param z2   end z
 * @param tone what the bar means, and so how it looks
 */
record Line(double x1, double y1, double z1, double x2, double y2, double z2, @NotNull Tone tone) {

    /** A bar between two points. */
    static @NotNull Line between(double x1, double y1, double z1, double x2, double y2, double z2,
                                 @NotNull Tone tone) {
        return new Line(x1, y1, z1, x2, y2, z2, tone);
    }

    /** A marker on one point: a small cube rather than a bar. */
    static @NotNull Line marker(double x, double y, double z, @NotNull Tone tone) {
        return new Line(x, y, z, x, y, z, tone);
    }

    /** Whether this is a marker rather than a bar. */
    boolean isMarker() {
        return x1 == x2 && y1 == y2 && z1 == z2;
    }

    double length() {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    double midX() {
        return (x1 + x2) / 2.0;
    }

    double midY() {
        return (y1 + y2) / 2.0;
    }

    double midZ() {
        return (z1 + z2) / 2.0;
    }

    /**
     * The part of this bar inside a box, or {@code null} when none of it is.
     *
     * <p>The Liang–Barsky clip: the bar is a parameter running from 0 at the start to 1 at the
     * end, and each face of the box can only narrow that range. A bar wholly inside comes back
     * as itself, which is what keeps an unchanged outline equal from one frame to the next.
     */
    @Nullable Line clip(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (isMarker()) {
            return x1 >= minX && x1 <= maxX && y1 >= minY && y1 <= maxY && z1 >= minZ && z1 <= maxZ
                    ? this : null;
        }
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double[] range = {0.0, 1.0};
        if (!narrow(-dx, x1 - minX, range) || !narrow(dx, maxX - x1, range)
                || !narrow(-dy, y1 - minY, range) || !narrow(dy, maxY - y1, range)
                || !narrow(-dz, z1 - minZ, range) || !narrow(dz, maxZ - z1, range)) {
            return null;
        }
        if (range[0] == 0.0 && range[1] == 1.0) {
            return this;
        }
        return new Line(x1 + dx * range[0], y1 + dy * range[0], z1 + dz * range[0],
                x1 + dx * range[1], y1 + dy * range[1], z1 + dz * range[1], tone);
    }

    /** One face of the clip: narrows {@code range}, or answers {@code false} when it empties. */
    private static boolean narrow(double toward, double room, double[] range) {
        if (toward == 0.0) {
            return room >= 0.0;
        }
        double at = room / toward;
        if (toward < 0.0) {
            if (at > range[1]) {
                return false;
            }
            range[0] = Math.max(range[0], at);
        } else {
            if (at < range[0]) {
                return false;
            }
            range[1] = Math.min(range[1], at);
        }
        return true;
    }

    /**
     * What a bar means, which is all that decides how it looks.
     *
     * <p>A palette token rather than a colour, so a server that changes its palette changes its
     * outlines with it.
     */
    enum Tone {

        /** The shape as it stands. */
        EDGE("primary", 0x8A51C4, 0.06f),

        /** The edge that closes an outline back to its first point. */
        CLOSING("secondary_light", 0xB48FD9, 0.04f),

        /** Where the next click would put things: towards the block being looked at. */
        GHOST("info", 0x59A4FF, 0.04f),

        /** A clicked point. */
        ANCHOR("highlight", 0xFFD700, 0.2f);

        private final String token;
        private final int fallback;
        private final float thickness;

        Tone(String token, int fallback, float thickness) {
            this.token = token;
            this.fallback = fallback;
            this.thickness = thickness;
        }

        /** The palette token this tone takes its colour from. */
        String token() {
            return token;
        }

        /** The colour when the palette has no such token, which is the Exylia default. */
        int fallback() {
            return fallback;
        }

        /** How thick a bar is, or how wide a marker, in blocks. */
        float thickness() {
            return thickness;
        }
    }
}
