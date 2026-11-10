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
        double x = (anchor.x() * 2 - 1) * HALF_WIDTH;
        double y = (anchor.y() * 2 - 1) * HALF_HEIGHT;
        if (anchor.x() < 0.5) x += wide / 2;
        else if (anchor.x() > 0.5) x -= wide / 2;
        y = anchor.stacksUp() ? y + offset : y - offset - tall;
        return new double[]{x, y};
    }

    /**
     * Where an element slides in from and out to: off its own edge by its width at a side, a little lower in the
     * middle.
     */
    static double[] aside(HudAnchor anchor, double[] at, double wide, double tall) {
        if (anchor.x() < 0.5) return new double[]{at[0] - wide * 0.6, at[1]};
        if (anchor.x() > 0.5) return new double[]{at[0] + wide * 0.6, at[1]};
        return new double[]{at[0], at[1] + (anchor.stacksUp() ? -tall : tall) * 0.5};
    }

    /** The translation for a spot in the view, with the seat above the eyes taken back off for this pitch. */
    static float[] seated(double x, double y, double pitch, double seat) {
        double radians = Math.toRadians(pitch);
        return new float[]{(float) x, (float) (y - seat * Math.cos(radians)),
                (float) (-GuidePackets.VIEW_DISTANCE - seat * Math.sin(radians))};
    }

    /**
     * The field of view a client widens to as it moves, against its own: sprinting, speed and slowness, flying. The
     * client's own sum ({@code AbstractClientPlayer#getFieldOfViewModifier}) at the default FOV Effects of 100%.
     *
     * @param speed     the movement speed attribute, sprinting included
     * @param walkSpeed Bukkit's walk speed ({@code 0.2} by default, twice the client's)
     * @param flying    whether they fly
     */
    static double fovModifier(double speed, float walkSpeed, boolean flying) {
        if (walkSpeed <= 0 || Double.isNaN(speed)) return 1;
        double modifier = (speed / (walkSpeed / 2.0) + 1) / 2;
        if (flying) modifier *= 1.1;
        return Math.clamp(modifier, 0.1, 1.5);
    }

    /**
     * How much larger to draw everything so a widened view shows it where it was: what a 70 degree view widened by
     * {@code modifier} would shrink it by, the other way round.
     */
    static double zoom(double modifier) {
        return Math.tan(Math.toRadians(35 * modifier)) / Math.tan(Math.toRadians(35));
    }
}
