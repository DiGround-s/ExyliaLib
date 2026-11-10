package net.exylia.lib.hud;

/**
 * Where on the screen a HUD element sits. Elements sharing an anchor stack away from its edge, in
 * {@link HudWidget#order()}.
 *
 * <p>Positions are measured on a 16:9 window at the default 70&deg; field of view, inside a small safe margin. A wider
 * field of view or a narrower window draws everything nearer the centre, never off screen on 16:9 and wider.
 *
 * <p>The vanilla HUD already owns some places: the hotbar, hearts and hunger at the bottom centre, chat at the bottom
 * left, the sidebar at the middle right, the boss bar at the top centre and potion icons at the top right. Each anchor
 * here keeps clear of them.
 *
 * @since 1.566.0
 */
public enum HudAnchor {

    /** The top left corner: free in vanilla. Stacks downwards. */
    TOP_LEFT(0.015, 0.975),
    /** The top centre, under one boss bar. Stacks downwards. */
    TOP(0.5, 0.9),
    /** The top right corner, under the potion icons. Stacks downwards. The default for notices. */
    TOP_RIGHT(0.985, 0.88),
    /** The left edge, above the chat. Stacks downwards from the middle. */
    LEFT(0.015, 0.64),
    /** Just above the crosshair, leaving it clear: for big moments. Stacks downwards. */
    CENTER(0.5, 0.72),
    /** The right edge, where the sidebar goes. Stacks downwards from the middle. */
    RIGHT(0.985, 0.64),
    /** Over the hotbar, above the experience bar. Stacks upwards. */
    ABOVE_HOTBAR(0.5, 0.25),
    /** The bottom right corner: free in vanilla. Stacks upwards. */
    BOTTOM_RIGHT(0.985, 0.025);

    private final double x;
    private final double y;

    HudAnchor(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /** Where across the screen its stack starts, {@code 0} left to {@code 1} right. */
    public double x() {
        return x;
    }

    /** Where up the screen its stack starts, {@code 0} bottom to {@code 1} top. */
    public double y() {
        return y;
    }

    /** Whether a stack grows up from here rather than down. */
    public boolean stacksUp() {
        return y < 0.5;
    }

    /**
     * Reads a name as written in a file: {@code top-left}, {@code TOP_LEFT} and {@code top left} all work.
     *
     * @param name the name
     * @return the anchor, or {@code null} when nothing matches
     */
    public static HudAnchor parse(String name) {
        if (name == null) return null;
        String key = name.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (key.equals("CENTRE")) key = "CENTER";
        for (HudAnchor anchor : values()) if (anchor.name().equals(key)) return anchor;
        return null;
    }
}
