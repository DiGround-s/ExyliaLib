package net.exylia.lib.text.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.HSVLike;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/**
 * Text that moves with the clock, in any text the library parses: a band of
 * light that sweeps over it ({@code <shine>}), its own colours flowing along it
 * ({@code <flow>}), a rainbow running through it ({@code <rainbow>}), frames
 * that take turns ({@code <frames>}), a window scrolling through it
 * ({@code <marquee>}) and a blink ({@code <blink>}). Holograms read the same
 * tags, and add the ones only a display can draw.
 *
 * <p>The tags are read from the start of the text and move all of it:
 * {@code <flow><gradient:{primary}:{secondary_light}>&l✦ EXYLIA ✦</gradient>}.
 * A leading {@code <rainbow>} closed later by {@code </rainbow>} is
 * MiniMessage's still rainbow, and is left to it.
 *
 * <p>Works on the parsed component, one character at a time, so it moves over
 * whatever the text already is: a gradient, a {@code {primary}} title, a
 * placeholder's value. Every style but the colour is kept. Anything that is
 * not plain text, a sprite for instance, is left as it is. The frame comes from
 * the clock, never from how often the text is drawn, so a redraw shows where
 * the effect is now; text drawn once (chat, lore) shows one frame.
 */
public final class TextEffects {

    public static final double DEFAULT_FLOW = 4;
    public static final double DEFAULT_FRAMES = 2;
    public static final double DEFAULT_RAINBOW = 6;
    public static final double DEFAULT_BLINK = 1;
    public static final int DEFAULT_MARQUEE = 24;

    /** Characters a second the shine band travels, unless the line says. */
    public static final double SHINE_SPEED = 10;
    /** Half the width of the band, in characters. */
    public static final double SHINE_HALF_WIDTH = 3;
    /** Seconds of rest between two sweeps. */
    public static final double SHINE_PAUSE = 2.5;

    /** Characters one full turn of the rainbow spans. */
    private static final double RAINBOW_SPAN = 24;
    /** Soft enough to sit next to the palette rather than shout over it. */
    private static final float RAINBOW_SATURATION = 0.55f;

    /** Spaces between the end of a scrolling line and its start coming round again. */
    private static final int MARQUEE_GAP = 4;

    /** The part of a blink the line is shown for. */
    private static final double BLINK_SHOWN = 0.65;

    /** The faintest a fade goes; 0 to 3 are read as opaque by some clients. */
    public static final int FADE_FLOOR = 4;

    /** What a glyph is drawn as before it settles. */
    private static final String SCRAMBLE_GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#$%&*+=?<>";

    /** {@link #key} for a frame that differs from the last one every time. */
    public static final long MOVING = Long.MIN_VALUE;

    private static final TextColor PLAIN = TextColor.color(0xFFFFFF);

    private static volatile LongSupplier clock = System::currentTimeMillis;

    private TextEffects() {
    }

    /** Swaps the clock the text side reads; {@code null} for the real one. For tests. */
    public static void clock(@Nullable LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }

    /** Seconds on the clock text effects are drawn at. */
    public static double seconds() {
        return clock.getAsLong() / 1000.0;
    }

    /**
     * The moving effects of one text, clamped to what draws well.
     *
     * @param shine        the colour of the sweeping band, or {@code null}
     * @param shineSpeed   characters a second the band travels
     * @param shinePause   seconds between two sweeps
     * @param shineWidth   half the band, in characters
     * @param flow         characters a second the colours flow, 0 for none
     * @param rainbow      characters a second the rainbow runs, 0 for none
     * @param frames       seconds each frame shows, 0 for no frames
     * @param blink        seconds a blink takes, 0 for none
     * @param marquee      characters shown at once while scrolling, 0 for none
     * @param marqueeSpeed characters a second it scrolls
     */
    public record Effects(String shine, double shineSpeed, double shinePause, double shineWidth,
                          double flow, double rainbow, double frames, double blink, int marquee,
                          double marqueeSpeed) {

        /** No effect at all. */
        public static final Effects NONE = new Written().effects();

        /** Whether what the text says, or its colours, change with the clock. */
        public boolean moves() {
            return shine != null || flow != 0 || rainbow != 0 || frames > 0 || marquee > 0 || blink > 0;
        }
    }

    /**
     * The effect tags as written, before any clamping: {@code null} for a tag
     * that is not there. Holograms keep these on their lines, so an editor
     * reads back exactly what was written.
     */
    public static final class Written {
        public String shine;
        public Double shineSpeed;
        public Double shinePause;
        public Double shineWidth;
        public Double flow;
        public Double rainbow;
        public Double frames;
        public Double blink;
        public Integer marquee;
        public Double marqueeSpeed;

        /** Takes one effect tag, or answers {@code false} when it is not one or does not parse. */
        public boolean accept(String key, String value) {
            try {
                switch (key) {
                    case "shine" -> {
                        Map<String, Double> options = new HashMap<>();
                        String colour = options(value, options, "speed", "pause", "width");
                        shine = colour;
                        shineSpeed = options.get("speed");
                        shinePause = options.get("pause");
                        shineWidth = options.get("width");
                    }
                    case "flow" -> flow = number(value, DEFAULT_FLOW);
                    case "frames" -> frames = value.isEmpty() ? DEFAULT_FRAMES : seconds(value);
                    case "rainbow" -> rainbow = number(value, DEFAULT_RAINBOW);
                    case "blink" -> blink = value.isEmpty() ? DEFAULT_BLINK : seconds(value);
                    case "marquee" -> {
                        Map<String, Double> options = new HashMap<>();
                        String shown = options(value, options, "speed");
                        marquee = shown.isEmpty() ? DEFAULT_MARQUEE : Integer.parseInt(shown);
                        marqueeSpeed = options.get("speed");
                    }
                    default -> {
                        return false;
                    }
                }
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        /** The effects with the defaults filled in and every value clamped. */
        public Effects effects() {
            return new Effects(shine == null ? null : shine.isEmpty() ? "letters" : shine,
                    or(shineSpeed, SHINE_SPEED, 1, 100),
                    or(shinePause, SHINE_PAUSE, 0, 60),
                    or(shineWidth, SHINE_HALF_WIDTH, 0.5, 20),
                    flow == null ? 0 : Math.clamp(flow, -40, 40),
                    rainbow == null ? 0 : Math.clamp(rainbow, -40, 40),
                    frames == null ? 0 : Math.clamp(frames, 0.2, 3600),
                    blink == null ? 0 : Math.clamp(blink, 0.2, 60),
                    marquee == null ? 0 : Math.clamp(marquee, 1, 200),
                    or(marqueeSpeed, 6, 0.5, 40));
        }

        private static double or(Double value, double fallback, double min, double max) {
            return value == null ? fallback : Math.clamp(value, min, max);
        }
    }

    /** A text split into its leading effects and the rest, still to parse. */
    public record Split(Effects effects, String body) {

        /** The parsed body as it shows now. */
        public Component dress(Component parsed) {
            return TextEffects.dress(effects, parsed, seconds());
        }
    }

    /**
     * The effect tags at the start of a text, or {@code null} when it starts
     * with none, which is what nearly every text does and costs one character
     * check.
     */
    public static @Nullable Split split(String text) {
        if (!startsWithEffect(text)) {
            return null;
        }
        Written written = new Written();
        int at = leadingTags(text, (key, value) ->
                !(key.equals("rainbow") && text.contains("</rainbow>")) && written.accept(key, value));
        if (at == 0) {
            return null;
        }
        Effects effects = written.effects();
        return effects.moves() ? new Split(effects, text.substring(at)) : null;
    }

    private static final String[] KEYS = {"shine", "flow", "rainbow", "frames", "blink", "marquee"};

    /** A cheap first look, since every parse asks: does the text open with an effect's name? */
    private static boolean startsWithEffect(String text) {
        if (text.isEmpty() || text.charAt(0) != '<') {
            return false;
        }
        for (String key : KEYS) {
            if (text.regionMatches(true, 1, key, 0, key.length())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a text starts with an effect that moves, so it has to be redrawn to show it. */
    public static boolean moves(String text) {
        return split(text) != null;
    }

    /**
     * Reads the tags at the start of a line, {@code <key>} or
     * {@code <key:value>}, handing each to {@code accept} until it refuses one.
     * A value holding {@code >} is written in double quotes:
     * {@code <if:"%player_level% > 10">}.
     *
     * @return where the text after the accepted tags starts
     */
    public static int leadingTags(String raw, BiPredicate<String, String> accept) {
        int start = 0;
        while (start < raw.length() && raw.charAt(start) == '<') {
            int colon = -1;
            int close = -1;
            for (int i = start + 1; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == ':' && colon < 0) {
                    colon = i;
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        int quote = raw.indexOf('"', i + 2);
                        if (quote < 0) {
                            break;
                        }
                        i = quote;
                    }
                } else if (c == '>') {
                    close = i;
                    break;
                }
            }
            if (close < 0) {
                break;
            }
            String key = raw.substring(start + 1, colon < 0 ? close : colon).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : unquote(raw.substring(colon + 1, close).trim());
            if (!accept.test(key, value)) {
                break;
            }
            start = close + 1;
        }
        return start;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    /**
     * Splits {@code value,key=number,...} into the value, returned, and
     * its options, put in {@code into}.
     *
     * @throws NumberFormatException for an option that is not one of
     *                               {@code allowed} or not a number, so the
     *                               tag stays in the text
     */
    public static String options(String value, Map<String, Double> into, String... allowed) {
        String[] parts = value.split(",", -1);
        int first = 0;
        String main = "";
        if (parts.length > 0 && parts[0].indexOf('=') < 0) {
            main = parts[0].trim();
            first = 1;
        }
        for (int i = first; i < parts.length; i++) {
            String part = parts[i];
            int equals = part.indexOf('=');
            String name = equals < 0 ? "" : part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (equals < 0 || !Arrays.asList(allowed).contains(name)) {
                throw new NumberFormatException("not an option: " + part);
            }
            into.put(name, seconds(part.substring(equals + 1).trim()));
        }
        return main;
    }

    /** A number of seconds, written bare, with {@code s} or with {@code ms}. */
    public static double seconds(String value) {
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.endsWith("ms")) {
            return Double.parseDouble(trimmed.substring(0, trimmed.length() - 2)) / 1000;
        }
        if (trimmed.endsWith("s")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Double.parseDouble(trimmed);
    }

    /** A number, or {@code fallback} when nothing is written. */
    public static double number(String value, double fallback) {
        return value.isEmpty() ? fallback : Double.parseDouble(value);
    }

    /** One character, or one non-text piece, with the style it is drawn in. */
    private record Glyph(String text, Style style, Component other) {
    }

    /**
     * The line with the shine band where it stands at {@code seconds}.
     *
     * @return the lit line, or {@code null} while the band is resting off it
     */
    public static Component shine(Component line, TextColor light, double seconds) {
        return shine(line, light, seconds, SHINE_SPEED, SHINE_HALF_WIDTH, SHINE_PAUSE);
    }

    /**
     * The line with a shine band of the given speed, half width and pause
     * where it stands at {@code seconds}.
     *
     * @return the lit line, or {@code null} while the band is resting off it
     */
    public static Component shine(Component line, TextColor light, double seconds, double speed,
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
    public static boolean resting(Component line, double seconds) {
        return resting(line, seconds, SHINE_SPEED, SHINE_HALF_WIDTH, SHINE_PAUSE);
    }

    public static boolean resting(Component line, double seconds, double speed, double halfWidth,
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
    public static Component dress(Effects style, Component line, double seconds) {
        if (!style.moves()) {
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
    public static long key(Effects style, Component line, double seconds) {
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
    private static Component content(Effects style, Component line, double seconds) {
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
    public static TextColor colour(String value) {
        String token = value.replace("{", "").replace("}", "").trim();
        TextColor parsed = token.startsWith("#") ? TextColor.fromHexString(token)
                : net.exylia.lib.text.Colors.get(token);
        return parsed == null ? TextColor.color(0xFFFFFF) : parsed;
    }

    /**
     * One frame of a line whose frames are split by
     * {@code ||}, counting
     * round: frame 3 of a two-frame line is its second. A style set before a
     * separator carries on into the next frame, as it would on one line.
     */
    public static Component frame(Component line, long index) {
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
    public static Component rainbow(Component line, double speed, double seconds) {
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
    public static Component marquee(Component line, int width, double speed, double seconds) {
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
    public static Component scramble(Component line, double elapsed, double speed, long frame) {
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
    public static int length(Component line) {
        return flatten(line).size();
    }

    /** Whether a blink of {@code period} seconds shows the line at {@code seconds}. */
    public static boolean blinkShown(double period, double seconds) {
        return seconds % period < period * BLINK_SHOWN;
    }

    /**
     * The text opacity of a fading line at {@code seconds}: from {@code max}
     * down to almost nothing and back, once every {@code period} seconds, in
     * steps of 8 so it is only re-sent when it visibly changes.
     */
    public static int fadeOpacity(int max, double period, double seconds) {
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
    public static Component flow(Component line, double speed, double seconds) {
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
