package net.exylia.lib.guide;

import org.jetbrains.annotations.NotNull;

/**
 * How a guide text looks, and for a companion text where it floats and when it
 * steps aside.
 *
 * <pre>{@code
 * GuideTextStyle style = GuideTextStyle.companion().ahead(3).background(0xA0000000).lineWidth(180);
 * }</pre>
 *
 * @param background        ARGB of the panel behind the text; {@code 0} for none
 * @param opacity           alpha of the text itself, {@code 0}-{@code 255}
 * @param shadow            whether the text has a drop shadow
 * @param lineWidth         where lines wrap, in pixels
 * @param scale             how large it is drawn; {@code 1} is a sign's size at one block
 * @param seeThrough        whether it is drawn through walls
 * @param ahead             companion: how far ahead of the eyes it floats, in blocks
 * @param drop              companion: how far below the line of sight, in blocks, so it never
 *                          sits on the crosshair
 * @param hideAboveSpeed    companion: blocks per second above which it steps aside (sprint-jumping,
 *                          riding, an elytra); {@code 0} never
 * @param hideInCombat      companion: whether it steps aside while the player is in combat
 * @param scaleWithDistance anchored: whether it grows with distance so it stays readable from afar
 * @param maxScale          anchored: the largest it grows to, as a multiple of {@code scale}
 * @since 1.391.0
 */
public record GuideTextStyle(int background, int opacity, boolean shadow, int lineWidth, float scale,
                             boolean seeThrough, double ahead, double drop, double hideAboveSpeed,
                             boolean hideInCombat, boolean scaleWithDistance, float maxScale) {

    /** Keeps every value inside what a client can draw. */
    public GuideTextStyle {
        opacity = Math.clamp(opacity, 0, 255);
        lineWidth = Math.max(1, lineWidth);
        scale = Math.max(0.05f, scale);
        ahead = Math.clamp(ahead, 0.5, 12.0);
        hideAboveSpeed = Math.max(0, hideAboveSpeed);
        maxScale = Math.max(1f, maxScale);
    }

    /**
     * A panel ahead of the eyes: two and a half blocks out, a little low,
     * small, on a dark panel, out of the way while the player runs or fights.
     *
     * @return the companion defaults
     */
    public static @NotNull GuideTextStyle companion() {
        return new GuideTextStyle(0x78000000, 255, true, 220, 0.55f, false, 2.5, 0.35, 7.5, true,
                false, 1f);
    }

    /**
     * A sign at a place: full size, readable from about thirty blocks by
     * growing up to four times as the player walks away.
     *
     * @return the anchored defaults
     */
    public static @NotNull GuideTextStyle anchored() {
        return new GuideTextStyle(0x78000000, 255, true, 220, 1f, false, 2.5, 0, 0, false, true, 4f);
    }

    /**
     * @param argb the panel colour, {@code 0} for none
     * @return a copy with it
     */
    public @NotNull GuideTextStyle background(int argb) {
        return new GuideTextStyle(argb, opacity, shadow, lineWidth, scale, seeThrough, ahead, drop,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param alpha the text alpha
     * @return a copy with it
     */
    public @NotNull GuideTextStyle opacity(int alpha) {
        return new GuideTextStyle(background, alpha, shadow, lineWidth, scale, seeThrough, ahead, drop,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param on whether the text has a shadow
     * @return a copy with it
     */
    public @NotNull GuideTextStyle shadow(boolean on) {
        return new GuideTextStyle(background, opacity, on, lineWidth, scale, seeThrough, ahead, drop,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param pixels the wrap width
     * @return a copy with it
     */
    public @NotNull GuideTextStyle lineWidth(int pixels) {
        return new GuideTextStyle(background, opacity, shadow, pixels, scale, seeThrough, ahead, drop,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param size the drawn size
     * @return a copy with it
     */
    public @NotNull GuideTextStyle scale(float size) {
        return new GuideTextStyle(background, opacity, shadow, lineWidth, size, seeThrough, ahead, drop,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param through whether it shows through walls
     * @return a copy with it
     */
    public @NotNull GuideTextStyle seeThrough(boolean through) {
        return new GuideTextStyle(background, opacity, shadow, lineWidth, scale, through, ahead, drop,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param blocks how far ahead of the eyes
     * @return a copy with it
     */
    public @NotNull GuideTextStyle ahead(double blocks) {
        return new GuideTextStyle(background, opacity, shadow, lineWidth, scale, seeThrough, blocks, drop,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param blocks how far below the line of sight
     * @return a copy with it
     */
    public @NotNull GuideTextStyle drop(double blocks) {
        return new GuideTextStyle(background, opacity, shadow, lineWidth, scale, seeThrough, ahead, blocks,
                hideAboveSpeed, hideInCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param blocksPerSecond the speed past which it steps aside, {@code 0} never
     * @param inCombat        whether combat also makes it step aside
     * @return a copy with both
     */
    public @NotNull GuideTextStyle stepAside(double blocksPerSecond, boolean inCombat) {
        return new GuideTextStyle(background, opacity, shadow, lineWidth, scale, seeThrough, ahead, drop,
                blocksPerSecond, inCombat, scaleWithDistance, maxScale);
    }

    /**
     * @param grow    whether an anchored text grows with distance
     * @param largest the most it grows, as a multiple of its scale
     * @return a copy with both
     */
    public @NotNull GuideTextStyle growWithDistance(boolean grow, float largest) {
        return new GuideTextStyle(background, opacity, shadow, lineWidth, scale, seeThrough, ahead, drop,
                hideAboveSpeed, hideInCombat, grow, largest);
    }
}
