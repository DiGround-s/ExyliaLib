package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.hologram.HologramLine;


/**
 * How one line of a text hologram is drawn, read from tags at its start.
 *
 * <pre>
 * lines:
 *   - '&lt;block:NETHERITE_BLOCK&gt;&lt;spin:45&gt;&lt;glow:#ff6b9d&gt;'
 *   - '&lt;size:2.4&gt;&lt;shine&gt;{accent}&amp;lTHE ARENA'
 *   - '&lt;flow&gt;{secondary}Season II · open now'
 *   - '&lt;if:"%player_level% &gt;= 10"&gt;{success}You qualify'
 *   - '&lt;perm:!arena.ranked&gt;&lt;opacity:180&gt;{letters_black}Ranked unlocks at level 10'
 *   - '&lt;range:5&gt;&lt;typewriter&gt;&lt;background:#55000000&gt;{letters}Right-click to queue'
 * </pre>
 *
 * <p>Only these tags, and only at the start of a line, are read: anything else,
 * {@code <gradient:...>} included, is left for the text parser. A tag whose
 * value does not parse ends the run and stays in the text, so a typo shows up
 * on screen instead of vanishing. A value holding {@code >}, as a condition
 * can, is written in double quotes: {@code <if:"%player_level% > 10">}.
 *
 * @param model      what an item or block line shows, or {@code null} for text
 * @param block      whether {@link #model} is a block rather than an item
 * @param size       how much bigger than the hologram's own scale, which also
 *                   stretches the room the line takes
 * @param opacity    the line's text opacity, or {@code null} to inherit
 * @param background the line's background, or {@code null} to inherit
 * @param shadow     the line's shadow, or {@code null} to inherit
 * @param glow       the outline colour of an item or block line as
 *                   {@code #RRGGBB}, empty for the team colour, {@code null}
 *                   for none
 * @param spin       degrees a second the line turns around itself
 * @param bob        blocks the line floats up and down
 * @param pulse      how much the line breathes, as a fraction of its size
 * @param typewriter characters a second the line is typed in at when a player
 *                   first sees it, 0 to show it whole
 * @param condition  a comparison that must hold for the viewer, or {@code null}
 * @param permission a permission the viewer must have, {@code !} first to
 *                   require not having it, or {@code null}
 * @param range      the furthest a viewer still sees the line, 0 for the
 *                   hologram's own view distance
 * @param shine      the colour of a band of light that sweeps over the line,
 *                   as a palette name or {@code #RRGGBB}, or {@code null}
 * @param flow       characters a second the line's own colours flow along it
 */
record LineStyle(String model, boolean block, double size, Integer opacity, String background,
                 Boolean shadow, String glow, double spin, double bob, double pulse,
                 double typewriter, String condition, String permission, double range,
                 String shine, double flow) {

    static final LineStyle PLAIN = new LineStyle(null, false, 1.0, null, null, null, null,
            0, 0, 0, 0, null, null, 0, null, 0);

    /** An item or block at size 1 is half a block, a little taller than a text line. */
    static final double MODEL_HEIGHT = 0.5;

    /** The fastest spin that still reads as one direction between two keyframes. */
    private static final double MAX_SPIN = 300;

    /** A line split into its style and the text left to parse. */
    record Parsed(LineStyle style, String body) {
    }

    static Parsed parse(String line) {
        HologramLine written = HologramLine.parse(line);
        return new Parsed(of(written), written.text());
    }

    /**
     * A written line's style, clamped to what draws well and with the
     * defaults filled in. The reading itself is {@link HologramLine}'s, so an
     * editor and the hologram can never disagree on what a line says.
     */
    static LineStyle of(HologramLine line) {
        String model = line.block() != null ? line.block() : line.item();
        return new LineStyle(model, line.block() != null,
                line.size() == null ? 1.0 : Math.clamp(line.size(), 0.1, 10.0),
                line.opacity() == null ? null : Math.clamp(line.opacity(), 0, 255),
                line.background(), line.shadow(), line.glow(),
                line.spin() == null ? 0 : Math.clamp(line.spin(), -MAX_SPIN, MAX_SPIN),
                line.bob() == null ? 0 : Math.clamp(line.bob(), 0, 2),
                line.pulse() == null ? 0 : Math.clamp(line.pulse(), 0, 0.9),
                line.typewriter() == null ? 0 : Math.clamp(line.typewriter(), 1, 1000),
                line.condition(), line.permission(),
                line.range() == null ? 0 : Math.max(0, line.range()),
                line.shine() == null ? null : line.shine().isEmpty() ? "letters" : line.shine(),
                line.flow() == null ? 0 : Math.clamp(line.flow(), -40, 40));
    }

    boolean isModel() {
        return model != null;
    }

    boolean animated() {
        return spin != 0 || bob != 0 || pulse != 0;
    }

    /** Whether the line's colours move. */
    boolean shimmers() {
        return shine != null || flow != 0;
    }

    /** Whether some viewers in range may still not be shown this line. */
    boolean conditional() {
        return condition != null || permission != null || range > 0;
    }

    /** The hologram's properties with this line's overrides and size applied. */
    HologramConfig.Properties apply(HologramConfig.Properties base) {
        double scale = isModel() ? size * MODEL_HEIGHT : size;
        // A block has no front to turn towards the player: it would swing
        // around its corner instead.
        String billboard = block ? "FIXED" : base.billboard();
        return new HologramConfig.Properties(billboard, base.alignment(),
                base.scaleX() * scale, base.scaleY() * scale, base.scaleZ() * scale,
                shadow == null ? base.shadow() : shadow,
                base.seeThrough(), base.lineWidth(), base.lineSpacing(),
                opacity == null ? base.textOpacity() : opacity,
                base.defaultBackground() && background == null,
                background == null ? base.backgroundColor() : background,
                base.brightness(), base.glowing());
    }

    /** The vertical room this line takes. */
    double height(HologramConfig.Properties base) {
        return isModel() ? MODEL_HEIGHT * size * base.scaleY() : base.lineSpacing() * size;
    }
}
