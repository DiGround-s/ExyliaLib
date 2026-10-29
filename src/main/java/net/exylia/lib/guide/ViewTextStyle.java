package net.exylia.lib.guide;

import org.jetbrains.annotations.NotNull;

/**
 * How a view text looks and where on the screen it sits.
 *
 * <pre>{@code
 * ViewTextStyle style = ViewTextStyle.subtitle().screenY(0.3f).lineWidth(260);
 * }</pre>
 *
 * <p>Sizes and positions are measured at the default 70&deg; field of view; a
 * player with a wider one sees the panel a little smaller and nearer the
 * centre, never off screen.
 *
 * @param background ARGB of the panel behind the text; {@code 0} for none
 * @param opacity    alpha of the text itself, {@code 0}-{@code 255}
 * @param shadow     whether the text has a drop shadow
 * @param lineWidth  where lines wrap, in pixels
 * @param scale      how large it is drawn; {@code 0.2} is a cinematic subtitle
 * @param screenY    where its bottom edge sits, as a share of the screen height from the bottom
 *                   ({@code 0} bottom, {@code 0.5} centre, {@code 1} top); it grows upwards from there
 * @param seeThrough whether it is drawn through a wall closer than the panel (on by default: the
 *                   panel floats under a block ahead of the eyes, so a player facing a wall would
 *                   otherwise lose it)
 * @since 1.395.0
 */
public record ViewTextStyle(int background, int opacity, boolean shadow, int lineWidth, float scale,
                            float screenY, boolean seeThrough) {

    /** Keeps every value inside what a client can draw. */
    public ViewTextStyle {
        opacity = Math.clamp(opacity, 0, 255);
        lineWidth = Math.max(1, lineWidth);
        scale = Math.clamp(scale, 0.02f, 2f);
        screenY = Math.clamp(screenY, 0f, 0.95f);
    }

    /**
     * A cinematic subtitle for normal play: small, on a translucent panel,
     * centred, its bottom edge a quarter of the way up the screen so it clears
     * the hotbar, hearts, hunger and experience bar at any GUI scale.
     *
     * @return the subtitle defaults
     */
    public static @NotNull ViewTextStyle subtitle() {
        return new ViewTextStyle(0x70000000, 255, true, 300, 0.2f, 0.25f, true);
    }

    /**
     * @param argb the panel colour, {@code 0} for none
     * @return a copy with it
     */
    public @NotNull ViewTextStyle background(int argb) {
        return new ViewTextStyle(argb, opacity, shadow, lineWidth, scale, screenY, seeThrough);
    }

    /**
     * @param alpha the text alpha
     * @return a copy with it
     */
    public @NotNull ViewTextStyle opacity(int alpha) {
        return new ViewTextStyle(background, alpha, shadow, lineWidth, scale, screenY, seeThrough);
    }

    /**
     * @param on whether the text has a shadow
     * @return a copy with it
     */
    public @NotNull ViewTextStyle shadow(boolean on) {
        return new ViewTextStyle(background, opacity, on, lineWidth, scale, screenY, seeThrough);
    }

    /**
     * @param pixels the wrap width
     * @return a copy with it
     */
    public @NotNull ViewTextStyle lineWidth(int pixels) {
        return new ViewTextStyle(background, opacity, shadow, pixels, scale, screenY, seeThrough);
    }

    /**
     * @param size the drawn size
     * @return a copy with it
     */
    public @NotNull ViewTextStyle scale(float size) {
        return new ViewTextStyle(background, opacity, shadow, lineWidth, size, screenY, seeThrough);
    }

    /**
     * @param share where the bottom edge sits, from the bottom of the screen
     * @return a copy with it
     */
    public @NotNull ViewTextStyle screenY(float share) {
        return new ViewTextStyle(background, opacity, shadow, lineWidth, scale, share, seeThrough);
    }

    /**
     * @param through whether it shows through a wall right in front of the eyes
     * @return a copy with it
     */
    public @NotNull ViewTextStyle seeThrough(boolean through) {
        return new ViewTextStyle(background, opacity, shadow, lineWidth, scale, screenY, through);
    }
}
