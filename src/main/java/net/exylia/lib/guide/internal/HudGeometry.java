package net.exylia.lib.guide.internal;

import net.exylia.lib.hud.HudAnchor;

/**
 * Where HUD elements sit, as numbers, so they are tested without a client.
 *
 * <p>Everything is in the axes of a {@code CENTER} billboard seated on the player, {@link GuidePackets#VIEW_DISTANCE}
 * ahead: x right, y up, z towards the eye. A text display stands on the bottom centre of its text.
 */
final class HudGeometry {

    /** The window a position is measured on. Wider windows only move things outwards. */
    static final double ASPECT = 16.0 / 9.0;
    /** Half the view's height at the distance, at the default 70 degree field of view. */
    static final double HALF_HEIGHT = Math.tan(Math.toRadians(35)) * GuidePackets.VIEW_DISTANCE;
    static final double HALF_WIDTH = HALF_HEIGHT * ASPECT;
    /** A font pixel at text scale 1, in blocks. */
    static final double FONT_PIXEL = 0.025;
    /** One line of a text display, in font pixels. */
    static final int LINE = 10;
    /** The space between two stacked elements, in font pixels. */
    static final double GAP = 3;

    private HudGeometry() {
    }

    /** The text display scale that draws a font pixel as large as a text of {@code size} would one block away. */
    static float scale(double size) {
        return (float) (size * GuidePackets.VIEW_DISTANCE);
    }

    /** A font pixel at that size, in blocks. */
    static double pixel(double size) {
        return FONT_PIXEL * scale(size);
    }

    /** The text flags an anchor's lines are aligned with. */
    static int align(HudAnchor anchor) {
        if (anchor.x() < 0.5) return GuidePackets.ALIGN_LEFT;
        if (anchor.x() > 0.5) return GuidePackets.ALIGN_RIGHT;
        return 0;
    }

    /**
     * Where one element of a stack stands, before the seat is taken off.
     *
     * @param anchor  where the stack starts
     * @param offset  how far along the stack it starts, in blocks (the elements before it and their gaps)
     * @param wide    its width, in blocks
     * @param tall    its height, in blocks
     * @return {@code x, y}
     */
    static double[] place(HudAnchor anchor, double offset, double wide, double tall) {
        return place(anchor, offset, wide, tall, 1, ASPECT);
    }

    /**
     * The same in a player's own window: {@code zoom} times the view's height at FOV 70 (the tangent of half their FOV
     * over that of 35 degrees), {@code aspect} its width over its height.
     */
    static double[] place(HudAnchor anchor, double offset, double wide, double tall, double zoom, double aspect) {
        double x = (anchor.x() * 2 - 1) * HALF_HEIGHT * zoom * aspect;
        double y = (anchor.y() * 2 - 1) * HALF_HEIGHT * zoom;
        if (anchor.x() < 0.5) x += wide / 2;
        else if (anchor.x() > 0.5) x -= wide / 2;
        y = anchor.stacksUp() ? y + offset : y - offset - tall;
        return new double[]{x, y};
    }

    /**
     * Where an element's bottom centre stands while it is drawn {@code k} times its size, so it grows from and shrinks
     * into its own middle: a pop in place, never a slide across the screen's edge (1.588.0).
     */
    static double[] popped(double[] at, double tall, double k) {
        return new double[]{at[0], at[1] + tall / 2 * (1 - k)};
    }

    /** The translation for a spot in the view, with the seat above the eyes taken back off for this pitch. */
    static float[] seated(double x, double y, double pitch, double seat) {
        return seated(x, y, pitch, seat, 1);
    }

    /**
     * The same, the spot pulled {@code f} of the way towards the eye: it looks exactly as large and in the same place,
     * only nearer, so stacked layers keep their order in the depth buffer.
     */
    static float[] seated(double x, double y, double pitch, double seat, double f) {
        double radians = Math.toRadians(pitch);
        return new float[]{(float) (x * f), (float) (y * f - seat * Math.cos(radians)),
                (float) (-GuidePackets.VIEW_DISTANCE * f - seat * Math.sin(radians))};
    }

    /**
     * How much the client widens its view as the player moves, against its own: sprinting, speed and slowness,
     * flying. The client's own sum ({@code AbstractClientPlayer#getFieldOfViewModifier}) with their FOV Effects slider.
     *
     * @param speed     the movement speed attribute, sprinting included
     * @param walkSpeed Bukkit's walk speed ({@code 0.2} by default, twice the client's)
     * @param flying    whether they fly
     * @param effects   their FOV Effects slider, 0 to 1
     */
    static double fovModifier(double speed, float walkSpeed, boolean flying, double effects) {
        if (walkSpeed <= 0 || Double.isNaN(speed)) return 1;
        double modifier = (speed / (walkSpeed / 2.0) + 1) / 2;
        if (flying) modifier *= 1.1;
        return Math.clamp(1 + (modifier - 1) * effects, 0.1, 1.5);
    }

    /**
     * How much larger to draw the HUD, around the middle of the view, so a view of {@code fov} degrees widened by
     * {@code modifier} shows it where it was.
     */
    static double widen(double modifier, double fov) {
        double half = Math.toRadians(fov / 2);
        return Math.tan(half * modifier) / Math.tan(half);
    }

    /**
     * Points spread over a rectangle of the view, its edges included, no further than {@code step} apart. A block
     * nearer than the HUD is never smaller than one unit of it, so at a step under one no block over the rectangle is
     * missed.
     */
    static double[][] samples(double left, double bottom, double right, double top, double step) {
        int columns = Math.max(1, (int) Math.ceil((right - left) / step));
        int rows = Math.max(1, (int) Math.ceil((top - bottom) / step));
        double[][] out = new double[(columns + 1) * (rows + 1)][];
        int n = 0;
        for (int c = 0; c <= columns; c++) {
            for (int r = 0; r <= rows; r++) {
                out[n++] = new double[]{left + (right - left) * c / columns, bottom + (top - bottom) * r / rows};
            }
        }
        return out;
    }
}
