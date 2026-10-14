package net.exylia.lib.hologram.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.HSVLike;

import java.util.ArrayList;
import java.util.List;

/**
 * Text that moves with the clock: a band of light that sweeps over a line
 * ({@code <shine>}), its own colours flowing along it ({@code <flow>}), a
 * rainbow running through it ({@code <rainbow>}), frames that take turns
 * ({@code <frames>}), a window scrolling through it ({@code <marquee>}), a blink
 * ({@code <blink>}) and random glyphs settling into it ({@code <scramble>}).
 *
 * <p>Works on the parsed component, one character at a time, so it moves over
 * whatever the line already is: a gradient, a {@code {primary}} title, a
 * placeholder's value. Every style but the colour is kept. Anything that is
 * not plain text, a sprite for instance, is left as it is.
 */
final class Shimmer {

    /** Characters a second the shine band travels, unless the line says. */
    static final double SHINE_SPEED = 10;
    /** Half the width of the band, in characters. */
    private static final double SHINE_HALF_WIDTH = 3;
    /** Seconds of rest between two sweeps. */
    private static final double SHINE_PAUSE = 2.5;

    /** Characters one full turn of the rainbow spans. */
    private static final double RAINBOW_SPAN = 24;
    /** Soft enough to sit next to the palette rather than shout over it. */
    private static final float RAINBOW_SATURATION = 0.55f;

    /** Spaces between the end of a scrolling line and its start coming round again. */
    private static final int MARQUEE_GAP = 4;

    /** The part of a blink the line is shown for. */
    private static final double BLINK_SHOWN = 0.65;

    /** The faintest a fade goes; 0 to 3 are read as opaque by some clients. */
    static final int FADE_FLOOR = 4;

    /** What a glyph is drawn as before it settles. */
    private static final String SCRAMBLE_GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#$%&*+=?<>";

    /** {@link #key} for a frame that differs from the last one every time. */
    static final long MOVING = Long.MIN_VALUE;

    private static final TextColor PLAIN = TextColor.color(0xFFFFFF);

    private Shimmer() {
    }

    /** One character, or one non-text piece, with the style it is drawn in. */
    private record Glyph(String text, Style style, Component other) {
    }

    /**
     * The line with the shine band where it stands at {@code seconds}.
     *
     * @return the lit line, or {@code null} while the band is resting off it
     */
    static Component shine(Component line, TextColor light, double seconds) {
        return shine(line, light, seconds, SHINE_SPEED, SHINE_HALF_WIDTH, SHINE_PAUSE);
    }

    /**
     * The line with a shine band of the given speed, half width and pause
     * where it stands at {@code seconds}.
     *
     * @return the lit line, or {@code null} while the band is resting off it
     */
    static Component shine(Component line, TextColor light, double seconds, double speed,
                           double halfWidth, double pause) {
        List<Glyph> glyphs = flatten(line);
        int length = glyphs.size();
        double sweep = (length + 2 * halfWidth) / speed;
        double at = seconds % (sweep + pause);
        if (at > sweep) {
            return null;
        }
        double centre = at * speed - halfWidth;
        List<TextColor> colours = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            TextColor base = colourOf(glyphs.get(i));
            double strength = 1 - Math.abs(i - centre) / halfWidth;
            colours.add(strength <= 0 ? base : TextColor.lerp((float) strength, base, light));
        }
        return rebuild(glyphs, colours);
    }

    /** Whether a shine at {@code seconds} is resting, so the plain line is what shows. */
    static boolean resting(Component line, double seconds) {
        return resting(line, seconds, SHINE_SPEED, SHINE_HALF_WIDTH, SHINE_PAUSE);
    }

    static boolean resting(Component line, double seconds, double speed, double halfWidth,
                           double pause) {
        int length = flatten(line).size();
        double sweep = (length + 2 * halfWidth) / speed;
        return seconds % (sweep + pause) > sweep;
    }

    /**
     * The line as one viewer is shown it at {@code seconds}: its frame, the
     * window of it that scrolls into view, its moving colours, and nothing
     * while it blinks off. A line with none of these is returned as it is.
     */
    static Component dress(LineStyle style, Component line, double seconds) {
        if (!style.shimmers()) {
            return line;
        }
        if (style.blink() > 0 && !blinkShown(style.blink(), seconds)) {
            return Component.empty();
        }
        Component frame = content(style, line, seconds);
        if (style.flow() != 0) {
            frame = flow(frame, style.flow(), seconds);
        }
        if (style.rainbow() != 0) {
            frame = rainbow(frame, style.rainbow(), seconds);
        }
        if (style.shine() != null) {
            Component shone = shine(frame, colour(style.shine()), seconds, style.shineSpeed(),
                    style.shineWidth(), style.shinePause());
            if (shone != null) {
                frame = shone;
            }
        }
        return frame;
    }

    /**
     * What {@link #dress} would show at {@code seconds}, as a number that only
     * changes when the frame does, so a line is only re-sent when it looks
     * different; {@link #MOVING} when it changes every time.
     */
    static long key(LineStyle style, Component line, double seconds) {
        if (style.flow() != 0 || style.rainbow() != 0) {
            return MOVING;
        }
        boolean shown = style.blink() <= 0 || blinkShown(style.blink(), seconds);
        if (shown) {
            Component frame = content(style, line, seconds);
            if (style.marquee() > 0 && flatten(frame).size() > style.marquee()) {
                return MOVING;
            }
            if (style.shine() != null && !resting(frame, seconds, style.shineSpeed(),
                    style.shineWidth(), style.shinePause())) {
                return MOVING;
            }
        }
        long key = style.frames() > 0 ? (long) Math.floor(seconds / style.frames()) : 0;
        return key * 2 + (shown ? 1 : 0);
    }

    /** The frame and the scrolled window, before any colour moves. */
    private static Component content(LineStyle style, Component line, double seconds) {
        Component frame = line;
        if (style.frames() > 0) {
            frame = frame(frame, (long) Math.floor(seconds / style.frames()));
        }
        if (style.marquee() > 0) {
            frame = marquee(frame, style.marquee(), style.marqueeSpeed(), seconds);
        }
        return frame;
    }

    /** A palette name such as {@code highlight} or {@code {highlight}}, or a hex colour. */
    static TextColor colour(String value) {
        String token = value.replace("{", "").replace("}", "").trim();
        TextColor parsed = token.startsWith("#") ? TextColor.fromHexString(token)
                : net.exylia.lib.text.Colors.get(token);
        return parsed == null ? TextColor.color(0xFFFFFF) : parsed;
    }

    /**
     * One frame of a line whose frames are split by
     * {@link net.exylia.lib.hologram.HologramLine#FRAME_SEPARATOR}, counting
     * round: frame 3 of a two-frame line is its second. A style set before a
     * separator carries on into the next frame, as it would on one line.
     */
    static Component frame(Component line, long index) {
        List<Glyph> glyphs = flatten(line);
        List<List<Glyph>> frames = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        for (int i = 0; i < glyphs.size(); i++) {
            if (bar(glyphs.get(i)) && i + 1 < glyphs.size() && bar(glyphs.get(i + 1))) {
                frames.add(current);
                current = new ArrayList<>();
                i++;
                continue;
            }
            current.add(glyphs.get(i));
        }
        frames.add(current);
        if (frames.size() == 1) {
            return line;
        }
        return rebuild(frames.get((int) Math.floorMod(index, (long) frames.size())));
    }

    private static boolean bar(Glyph glyph) {
        return "|".equals(glyph.text());
    }

    /**
     * The line's colours replaced by a rainbow that runs along it, {@code speed}
     * characters a second; every other style is kept.
     */
    static Component rainbow(Component line, double speed, double seconds) {
        List<Glyph> glyphs = flatten(line);
        List<TextColor> colours = new ArrayList<>(glyphs.size());
        for (int i = 0; i < glyphs.size(); i++) {
            double hue = ((i - seconds * speed) / RAINBOW_SPAN) % 1;
            colours.add(TextColor.color(HSVLike.hsvLike((float) (hue < 0 ? hue + 1 : hue),
                    RAINBOW_SATURATION, 1f)));
        }
        return rebuild(glyphs, colours);
    }

    /**
     * {@code width} characters of the line, scrolled {@code seconds * speed}
     * characters along and coming round after a short gap. A line that fits
     * is returned whole and still.
     */
    static Component marquee(Component line, int width, double speed, double seconds) {
        List<Glyph> glyphs = flatten(line);
        int length = glyphs.size();
        if (length <= width) {
            return line;
        }
        int loop = length + MARQUEE_GAP;
        int start = (int) Math.floorMod((long) Math.floor(seconds * speed), (long) loop);
        List<Glyph> window = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            int at = (start + i) % loop;
            window.add(at < length ? glyphs.get(at) : new Glyph(" ", Style.empty(), null));
        }
        return rebuild(window);
    }

    /**
     * The line with its first {@code elapsed * speed} characters settled and
     * the rest drawn as random glyphs that change every {@code frame}. Spaces
     * stay spaces, so the shape of the line shows from the start.
     */
    static Component scramble(Component line, double elapsed, double speed, long frame) {
        List<Glyph> glyphs = flatten(line);
        int settled = (int) Math.floor(elapsed * speed);
        List<Glyph> drawn = new ArrayList<>(glyphs.size());
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph glyph = glyphs.get(i);
            if (i < settled || glyph.text() == null || glyph.text().isBlank()) {
                drawn.add(glyph);
                continue;
            }
            int pick = Math.floorMod(Long.hashCode(frame * 31 + i) * 0x9E3779B1, SCRAMBLE_GLYPHS.length());
            drawn.add(new Glyph(String.valueOf(SCRAMBLE_GLYPHS.charAt(pick)), glyph.style(), null));
        }
        return rebuild(drawn);
    }

    /** How many characters {@link #scramble} and the typewriter count in a line. */
    static int length(Component line) {
        return flatten(line).size();
    }

    /** Whether a blink of {@code period} seconds shows the line at {@code seconds}. */
    static boolean blinkShown(double period, double seconds) {
        return seconds % period < period * BLINK_SHOWN;
    }

    /**
     * The text opacity of a fading line at {@code seconds}: from {@code max}
     * down to almost nothing and back, once every {@code period} seconds, in
     * steps of 8 so it is only re-sent when it visibly changes.
     */
    static int fadeOpacity(int max, double period, double seconds) {
        double level = 0.5 + 0.5 * Math.cos(seconds * 2 * Math.PI / period);
        int opacity = (int) Math.round(FADE_FLOOR + (max - FADE_FLOOR) * level);
        int stepped = opacity > max - 4 ? max : opacity / 8 * 8;
        return Math.clamp(stepped, FADE_FLOOR, Math.max(FADE_FLOOR, max));
    }

    /**
     * The line with its colours moved {@code seconds * speed} characters
     * along. The colours run there and back, so a gradient flows without a
     * seam where it wraps; a line in one colour gets a soft wave of that
     * colour's lighter shade instead, so it still moves.
     */
    static Component flow(Component line, double speed, double seconds) {
        List<Glyph> glyphs = flatten(line);
        int length = glyphs.size();
        if (length == 0) {
            return line;
        }
        List<TextColor> source = new ArrayList<>(length);
        for (Glyph glyph : glyphs) {
            source.add(colourOf(glyph));
        }
        if (source.stream().distinct().count() == 1) {
            TextColor only = source.get(0);
            HSVLike hsv = only.asHSV();
            TextColor light = TextColor.color(HSVLike.hsvLike(hsv.h(), hsv.s() * 0.45f,
                    Math.min(1f, hsv.v() + 0.25f)));
            source.add(light);
        }
        int cycle = Math.max(1, 2 * source.size() - 2);
        double shift = seconds * speed;
        List<TextColor> colours = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            double position = ((i + shift) % cycle + cycle) % cycle;
            int low = (int) Math.floor(position);
            float part = (float) (position - low);
            colours.add(TextColor.lerp(part, mirrored(source, low, cycle),
                    mirrored(source, low + 1, cycle)));
        }
        return rebuild(glyphs, colours);
    }

    private static TextColor mirrored(List<TextColor> source, int index, int cycle) {
        int at = index % cycle;
        return source.get(at < source.size() ? at : cycle - at);
    }

    private static TextColor colourOf(Glyph glyph) {
        TextColor colour = glyph.style().color();
        return colour == null ? PLAIN : colour;
    }

    private static List<Glyph> flatten(Component line) {
        List<Glyph> glyphs = new ArrayList<>();
        flatten(line, Style.empty(), glyphs);
        return glyphs;
    }

    private static void flatten(Component component, Style inherited, List<Glyph> into) {
        Style style = inherited.merge(component.style());
        if (component instanceof TextComponent text) {
            text.content().codePoints().forEach(codePoint ->
                    into.add(new Glyph(Character.toString(codePoint), style, null)));
        } else {
            into.add(new Glyph(null, style, component.children(List.of()).style(style)));
        }
        for (Component child : component.children()) {
            flatten(child, style, into);
        }
    }

    /** The glyphs back into one component, each in its own style. */
    private static Component rebuild(List<Glyph> glyphs) {
        List<Component> parts = new ArrayList<>(glyphs.size());
        for (Glyph glyph : glyphs) {
            parts.add(glyph.other() != null ? glyph.other() : Component.text(glyph.text(), glyph.style()));
        }
        return Component.empty().children(parts);
    }

    private static Component rebuild(List<Glyph> glyphs, List<TextColor> colours) {
        List<Component> parts = new ArrayList<>(glyphs.size());
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph glyph = glyphs.get(i);
            parts.add(glyph.other() != null
                    ? glyph.other()
                    : Component.text(glyph.text(), glyph.style().color(colours.get(i))));
        }
        return Component.empty().children(parts);
    }
}
