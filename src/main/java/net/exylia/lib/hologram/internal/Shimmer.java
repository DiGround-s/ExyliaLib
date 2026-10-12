package net.exylia.lib.hologram.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.HSVLike;

import java.util.ArrayList;
import java.util.List;

/**
 * Colour that moves across a line: a band of light that sweeps over it
 * ({@code <shine>}), or the line's own colours flowing along it
 * ({@code <flow>}).
 *
 * <p>Works on the parsed component, one character at a time, so it moves over
 * whatever the line already is: a gradient, a {@code {primary}} title, a
 * placeholder's value. Every style but the colour is kept. Anything that is
 * not plain text, a sprite for instance, is left as it is.
 */
final class Shimmer {

    /** Characters a second the shine band travels. */
    static final double SHINE_SPEED = 10;
    /** Half the width of the band, in characters. */
    private static final double SHINE_HALF_WIDTH = 3;
    /** Seconds of rest between two sweeps. */
    private static final double SHINE_PAUSE = 2.5;

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
        List<Glyph> glyphs = flatten(line);
        int length = glyphs.size();
        double sweep = (length + 2 * SHINE_HALF_WIDTH) / SHINE_SPEED;
        double at = seconds % (sweep + SHINE_PAUSE);
        if (at > sweep) {
            return null;
        }
        double centre = at * SHINE_SPEED - SHINE_HALF_WIDTH;
        List<TextColor> colours = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            TextColor base = colourOf(glyphs.get(i));
            double strength = 1 - Math.abs(i - centre) / SHINE_HALF_WIDTH;
            colours.add(strength <= 0 ? base : TextColor.lerp((float) strength, base, light));
        }
        return rebuild(glyphs, colours);
    }

    /** Whether a shine at {@code seconds} is resting, so the plain line is what shows. */
    static boolean resting(Component line, double seconds) {
        int length = flatten(line).size();
        double sweep = (length + 2 * SHINE_HALF_WIDTH) / SHINE_SPEED;
        return seconds % (sweep + SHINE_PAUSE) > sweep;
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
