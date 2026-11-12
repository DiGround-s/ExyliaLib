package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.HologramConfig;
import net.exylia.lib.hologram.HologramLine;
import net.exylia.lib.text.internal.TextEffects;

import java.util.Locale;


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
 * @param effects    the text effects every text shares
 * @param head       the player head an item line shows, as written, or
 *                   {@code null}
 * @param gap        extra blocks above the line
 * @param align      the line's alignment, or {@code null} to inherit
 * @param lineWidth  where the line wraps, or {@code null} to inherit
 * @param seeThrough whether it shows through blocks, or {@code null} to inherit
 * @param brightness its light level, or {@code null} to inherit
 * @param billboard  how it turns, or {@code null} to inherit
 * @param autoscale  whether it grows with the viewer's distance
 * @param curve      its own distance curve, or {@code null} for the hologram's
 * @param bobPeriod  seconds a bob takes
 * @param pulsePeriod seconds a pulse takes
 * @param sway       degrees it turns back and forth
 * @param swayPeriod seconds a sway takes
 * @param fade       seconds a fade takes, 0 for none
 * @param scramble   characters a second that settle when first seen, 0 for none
 * @param button     the name of its own click lines, or {@code null}
 * @param hover      what it says while aimed at, or {@code null}
 * @param region     a WorldGuard region the viewer must (or, {@code !}
 *                   first, must not) stand in, or {@code null}
 * @param world      a world the viewer must (or must not) be in, or {@code null}
 * @param entity     the mob a mob line shows, or {@code null}
 * @param slide      the side it glides in from when first shown, or {@code null}
 * @param slideTime  seconds the slide takes
 * @param image      the PNG an image line draws, or {@code null}
 * @param imageWidth how many pixels wide, 0 for the picture's own
 */
record LineStyle(String model, boolean block, double size, Integer opacity, String background,
                 Boolean shadow, String glow, double spin, double bob, double pulse,
                 double typewriter, String condition, String permission, double range,
                 TextEffects.Effects effects, String head, double gap, String align,
                 Integer lineWidth, Boolean seeThrough, Integer brightness, String billboard,
                 boolean autoscale, HologramConfig.DistanceScale curve, double bobPeriod,
                 double pulsePeriod, double sway, double swayPeriod, double fade, double scramble,
                 String button, String hover, String region, String world, String entity,
                 String slide, double slideTime, String image, int imageWidth) {

    static final LineStyle PLAIN = of(HologramLine.of(""));

    /** An item or block at size 1 is half a block, a little taller than a text line. */
    static final double MODEL_HEIGHT = 0.5;

    /** The fastest spin that still reads as one direction over a keyframe glide (168 degrees in 700 ms). */
    private static final double MAX_SPIN = 240;

    /**
     * The shortest period a keyframed motion may have: keyframes are 500 ms
     * apart, and fewer than four a cycle would alias a bob into stillness.
     */
    private static final double MIN_PERIOD = 2;

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
        String model = line.block() != null ? line.block()
                : line.head() != null ? "PLAYER_HEAD"
                : line.entity() != null ? line.entity() : line.item();
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
                effects(line), line.head(),
                line.gap() == null ? 0 : Math.clamp(line.gap(), -5, 5),
                line.align() == null ? null : line.align().toUpperCase(Locale.ROOT),
                line.width() == null ? null : Math.clamp(line.width(), 1, 10_000),
                line.seeThrough(),
                line.brightness() == null ? null : Math.clamp(line.brightness(), -1, 15),
                line.billboard() == null ? null : line.billboard().toUpperCase(Locale.ROOT),
                line.autoscale() != null, curve(line.autoscale()),
                or(line.bobPeriod(), 3, MIN_PERIOD, 60),
                or(line.pulsePeriod(), 2, MIN_PERIOD, 60),
                line.sway() == null ? 0 : Math.clamp(line.sway(), -180, 180),
                or(line.swayPeriod(), 4, MIN_PERIOD, 60),
                line.fade() == null ? 0 : Math.clamp(line.fade(), 0.5, 60),
                line.scramble() == null ? 0 : Math.clamp(line.scramble(), 1, 1000),
                line.button(), line.hover(), line.region(), line.world(), line.entity(),
                line.slide(), or(line.slideTime(), 0.6, 0.05, 10),
                line.image(), line.imageWidth() == null ? 0 : Math.clamp(line.imageWidth(), 1, 96));
    }

    /** The line's text effects, clamped the way any text's are. */
    private static TextEffects.Effects effects(HologramLine line) {
        TextEffects.Written written = new TextEffects.Written();
        written.shine = line.shine();
        written.shineSpeed = line.shineSpeed();
        written.shinePause = line.shinePause();
        written.shineWidth = line.shineWidth();
        written.flow = line.flow();
        written.rainbow = line.rainbow();
        written.frames = line.frames();
        written.blink = line.blink();
        written.marquee = line.marquee();
        written.marqueeSpeed = line.marqueeSpeed();
        return written.effects();
    }

    private static double or(Double value, double fallback, double min, double max) {
        return value == null ? fallback : Math.clamp(value, min, max);
    }

    /** A line's own curve from {@code start,end,max}, or {@code null} for the hologram's. */
    private static HologramConfig.DistanceScale curve(String written) {
        if (written == null || written.isEmpty()) {
            return null;
        }
        String[] parts = written.split(",");
        return new HologramConfig.DistanceScale(true, Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]), 1, Double.parseDouble(parts[2]));
    }

    boolean isModel() {
        return model != null;
    }

    boolean animated() {
        return spin != 0 || bob != 0 || pulse != 0 || sway != 0;
    }

    /** Whether what the line says, or its colours, change with the clock. */
    boolean shimmers() {
        return effects.moves();
    }

    /** Whether its text opacity changes with the clock. */
    boolean fades() {
        return fade > 0;
    }

    /** Whether it is revealed to each viewer when they first see it. */
    boolean reveals() {
        return typewriter > 0 || scramble > 0;
    }

    /** Whether some viewers in range may still not be shown this line. */
    boolean conditional() {
        return condition != null || permission != null || range > 0 || region != null || world != null;
    }

    /** Whether it is a mob rather than a display. */
    boolean isMob() {
        return entity != null;
    }

    /** How much smaller an image line is drawn than text: a pixel of it is about a sixteenth of a block. */
    static final double IMAGE_SCALE = 0.28;

    /** The hologram's properties with this line's overrides and size applied. */
    HologramConfig.Properties apply(HologramConfig.Properties base) {
        // A mob is drawn at its own size, an image a sixteenth of a block a pixel.
        double scale = isMob() ? size : isModel() ? size * MODEL_HEIGHT : image != null ? size * IMAGE_SCALE : size;
        // A block has no front to turn towards the player: it would swing
        // around its corner instead, unless the line asks for it.
        String turn = billboard != null ? billboard : block ? "FIXED" : base.billboard();
        return new HologramConfig.Properties(turn, align == null ? base.alignment() : align,
                base.scaleX() * scale, base.scaleY() * scale, base.scaleZ() * scale,
                // A shadow would draw every pixel of a picture twice.
                shadow != null ? shadow : image == null && base.shadow(),
                seeThrough == null ? base.seeThrough() : seeThrough,
                // An image's rows end in newlines of their own, so it never wraps.
                lineWidth != null ? lineWidth : image != null ? 10_000 : base.lineWidth(), base.lineSpacing(),
                opacity == null ? base.textOpacity() : opacity,
                base.defaultBackground() && background == null,
                background == null ? base.backgroundColor() : background,
                brightness == null ? base.brightness() : brightness, base.glowing(),
                base.distanceScale());
    }

    /** The vertical room the line itself takes, without its gap. */
    double height(HologramConfig.Properties base) {
        if (isMob()) {
            return Mobs.height(entity) * size * base.scaleY();
        }
        return isModel() ? MODEL_HEIGHT * size * base.scaleY() : base.lineSpacing() * size;
    }
}
