package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the motion properties of a screen's CSS: {@code transform}, {@code transition}, {@code enter},
 * {@code leave} and {@code animation} with its {@code @keyframes}. Pure, so it is tested on its own.
 *
 * <p>Internal: free to change without notice.
 */
public final class MotionCss {

    private static final Pattern FUNCTION = Pattern.compile("([a-zA-Z]+)\\(([^)]*)\\)");
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(t|ms|s)?");

    private MotionCss() {
    }

    /**
     * {@code translate(x, y)}, {@code translateX(x)}, {@code translateY(y)}, {@code scale(s)}, {@code scaleX(s)} in any
     * order and number, pixels; {@code none} or nothing it can read is {@link Motion.Transform#NONE}.
     */
    public static Motion.Transform transform(String value) {
        if (value == null) return Motion.Transform.NONE;
        Motion.Transform out = Motion.Transform.NONE;
        Matcher m = FUNCTION.matcher(value);
        while (m.find()) {
            String[] args = m.group(2).trim().split("\\s*,\\s*|\\s+");
            double a = number(args[0]);
            double b = args.length > 1 ? number(args[1]) : 0;
            out = out.with(switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "translate" -> Motion.Transform.translate(a, b);
                case "translatex" -> Motion.Transform.translate(a, 0);
                case "translatey" -> Motion.Transform.translate(0, a);
                case "scale" -> Motion.Transform.scale(args[0].isEmpty() ? 1 : a);
                case "scalex" -> Motion.Transform.squeeze(args[0].isEmpty() ? 1 : a);
                default -> Motion.Transform.NONE;
            });
        }
        return out;
    }

    /** Ticks: {@code 4t}, {@code 4} (ticks), {@code 200ms}, {@code 0.3s}; -1 when it is not a duration. */
    public static int ticks(String word) {
        Matcher m = DURATION.matcher(word.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) return -1;
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "t" : m.group(2);
        return (int) Math.round(switch (unit) {
            case "ms" -> n / 50;
            case "s" -> n * 20;
            default -> n;
        });
    }

    /**
     * {@code transition: [transform|background|color|all] <duration> [easing]}, several separated by commas: the
     * longest wins, and colours step along when any part names {@code background}, {@code color} or {@code all}.
     */
    public static Motion.Transition transition(String value) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("none")) return null;
        int ticks = 0;
        Motion.Ease ease = Motion.Ease.OUT;
        boolean colours = false;
        for (String part : value.split(",")) {
            int t = -1;
            Motion.Ease e = null;
            for (String word : part.trim().split("\\s+")) {
                String w = word.toLowerCase(Locale.ROOT);
                if (w.equals("background") || w.equals("color") || w.equals("all") || w.equals("outline")) colours = true;
                int d = ticks(w);
                if (d >= 0 && t < 0) t = d;
                Motion.Ease parsed = Motion.Ease.parse(w);
                if (parsed != null) e = parsed;
            }
            if (t > ticks) {
                ticks = t;
                if (e != null) ease = e;
            } else if (e != null && t == ticks) {
                ease = e;
            }
        }
        return new Motion.Transition(ticks, ease, colours);
    }

    /** {@code enter} or {@code leave}: {@code <transform functions> <duration> [easing]}, or {@code none}. */
    public static Motion.Tween tween(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().equalsIgnoreCase("none")) return Motion.Tween.NONE;
        Motion.Transform transform = transform(value);
        String rest = FUNCTION.matcher(value).replaceAll(" ");
        int ticks = 4;
        Motion.Ease ease = Motion.Ease.OUT;
        for (String word : rest.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            int d = ticks(word);
            if (d >= 0) ticks = d;
            Motion.Ease parsed = Motion.Ease.parse(word);
            if (parsed != null) ease = parsed;
        }
        return new Motion.Tween(transform, ticks, ease);
    }

    /**
     * {@code animation: <name> <duration> [easing] [delay] [count|infinite] [alternate]} with the frames of
     * {@code @keyframes name}: the first duration is how long one play lasts, a second one the delay before it starts,
     * a bare number how many times it plays. {@code null} when it names no keyframes the sheets declare.
     */
    public static Motion.Keyframes animation(String value, Css css) {
        return animation(value, null, null, null, css);
    }

    /**
     * The same with the longhands an element may also carry, which win over the shorthand's words:
     * {@code animation-delay}, {@code animation-iteration-count} ({@code infinite} or a number) and
     * {@code animation-direction} ({@code alternate} or {@code normal}). Since 1.565.0.
     */
    public static Motion.Keyframes animation(Map<String, String> style, Css css) {
        return animation(style.get("animation"), style.get("animation-delay"), style.get("animation-iteration-count"),
                style.get("animation-direction"), css);
    }

    private static Motion.Keyframes animation(String value, String delayed, String count, String direction, Css css) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("none")) return null;
        String name = null;
        int ticks = -1;
        int delay = 0;
        int iterations = 1;
        Motion.Ease ease = Motion.Ease.IN_OUT;
        boolean infinite = false;
        boolean alternate = false;
        for (String word : value.trim().split("\\s+")) {
            String w = word.toLowerCase(Locale.ROOT);
            Motion.Ease parsed = Motion.Ease.parse(w);
            if (w.matches("\\d+") && ticks >= 0) iterations = Integer.parseInt(w);
            else if (ticks(w) >= 0 && ticks < 0) ticks = ticks(w);
            else if (ticks(w) >= 0) delay = ticks(w);
            else if (parsed != null) ease = parsed;
            else if (w.equals("infinite")) infinite = true;
            else if (w.equals("alternate")) alternate = true;
            else if (!w.equals("normal") && name == null) name = word;
        }
        if (delayed != null && ticks(delayed) >= 0) delay = ticks(delayed);
        if (count != null) {
            String c = count.trim().toLowerCase(Locale.ROOT);
            if (c.equals("infinite")) infinite = true;
            else if (c.matches("\\d+")) iterations = Integer.parseInt(c);
        }
        if (direction != null) alternate = direction.trim().toLowerCase(Locale.ROOT).startsWith("alternate");
        List<Map.Entry<Double, Map<String, String>>> frames = name == null ? null : css.keyframes(name);
        if (frames == null || frames.isEmpty()) return null;
        List<Motion.Frame> out = new ArrayList<>();
        for (Map.Entry<Double, Map<String, String>> frame : frames) {
            out.add(new Motion.Frame(frame.getKey(), transform(frame.getValue().get("transform"))));
        }
        return new Motion.Keyframes(out, Math.max(1, ticks < 0 ? 20 : ticks), ease, infinite, delay,
                Math.min(iterations, 1000), alternate);
    }

    /**
     * A one-shot play of {@code @keyframes name} for the {@code animate} action: {@code duration} and {@code ease} as
     * written after the name, or 10 ticks eased in and out. {@code null} when no sheet declares it. Since 1.565.0.
     */
    public static Motion.Keyframes play(String name, List<String> words, Css css) {
        StringBuilder value = new StringBuilder(name);
        for (String word : words) value.append(' ').append(word);
        if (words.stream().noneMatch(w -> ticks(w) >= 0)) value.append(" 10t");
        Motion.Keyframes read = animation(value.toString(), css);
        return read == null ? null : new Motion.Keyframes(read.frames(), read.ticks(), read.ease(), false, read.delay(),
                read.iterations(), read.alternate());
    }

    private static final Pattern SPAN = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|t|s|m|h|d)");

    /** A length of time: {@code 500ms}, {@code 20t}, {@code 5s}, {@code 10m}, {@code 24h}, {@code 7d}, or several ({@code 1h30m}); -1 when unreadable. Since 1.565.0. */
    public static long millis(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        Matcher m = SPAN.matcher(t);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            double n = Double.parseDouble(m.group(1));
            total += (long) (n * switch (m.group(2)) {
                case "ms" -> 1;
                case "t" -> 50;
                case "s" -> 1000;
                case "m" -> 60_000;
                case "h" -> 3_600_000;
                default -> 86_400_000;
            });
            end = m.end();
        }
        return end == t.length() && end > 0 ? total : -1;
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text.trim().replace("px", ""));
        } catch (NumberFormatException bad) {
            return 0;
        }
    }
}
