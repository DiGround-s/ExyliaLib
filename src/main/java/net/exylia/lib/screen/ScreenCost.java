package net.exylia.lib.screen;

/**
 * What a screen has cost the viewer's game at its heaviest since the server started: the display entities it drew
 * at once and the picture glyphs among them (each pixel of an {@code <img>}, a face, a body or a background picture
 * is one glyph the client lays out, four times). Display entities are what a client's frame rate follows most; a
 * picture is the heaviest thing one display can hold.
 *
 * @param displays      the most display entities drawn at once, out of {@link #DISPLAY_BUDGET}
 * @param pictureGlyphs the most picture glyphs drawn at once, against {@link #GLYPH_GUIDE}
 * @param lightened     whether it had to drop detail to fit the budget for a viewer who did not ask for it
 * @since 1.565.0
 */
public record ScreenCost(int displays, int pictureGlyphs, boolean lightened) {

    /** The most display entities one screen draws; past it the screen drops detail. */
    public static final int DISPLAY_BUDGET = 400;

    /**
     * Picture glyphs past which a screen is heavy for an ordinary graphics card, measured on a mid-range one (an
     * RTX 3050 at 1080p held about 100 frames a second at 205 displays).
     */
    public static final int GLYPH_GUIDE = 16_000;

    /** Nothing drawn yet. */
    public static final ScreenCost NONE = new ScreenCost(0, 0, false);

    /** Whether it is heavy for the client: near the display budget or past the picture guide. */
    public boolean heavy() {
        return displays > DISPLAY_BUDGET * 3 / 4 || pictureGlyphs > GLYPH_GUIDE;
    }

    /** The heavier of the two, field by field. */
    public ScreenCost max(ScreenCost other) {
        return new ScreenCost(Math.max(displays, other.displays), Math.max(pictureGlyphs, other.pictureGlyphs),
                lightened || other.lightened);
    }
}
