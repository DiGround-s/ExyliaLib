package net.exylia.lib.spatial.internal;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Words written for an inventory read on a 3D board: a colour too dark to read over the backdrop (under 3:1 over
 * the sky and over deepslate, seen through it, as {@code CentreStageLayoutTest} measures) gives way to the line's own
 * readable colour before it, else {@code {letters}}. A description written {@code {letters}A ... {neutral}.} in a
 * plugin's catalogue then reads whole in a 3D menu, with no server's file edited. Since 1.467.0.
 *
 * <p>Only words: a colour written on symbols alone (a dye's {@code ■} swatch, an icon's {@code ↶}, a {@code ✔}
 * marker) carries meaning and is left as written, and so does a hue ({@code {error}}, {@code {primary}}, a rarity's
 * blue): only a grey (an inventory's {@code &8}, {@code {neutral}}) gives way. Since 1.470.1.
 *
 * <p>Internal: free to change without notice.
 */
public final class Legible {

    /** What a board's words are read against: the sky, and deepslate. */
    public static final int SKY = 0x8AB4FF;
    public static final int GROUND = 0x4D4D50;
    public static final double MIN_CONTRAST = 3;

    /** A palette token, a hex tag or a legacy colour code. */
    private static final Pattern COLOUR = Pattern.compile("\\{([a-z_]+)}|<#([0-9a-fA-F]{6})>|&#([0-9a-fA-F]{6})|[&§]([0-9a-fA-F])");
    private static final int[] LEGACY = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private Legible() {
        throw new AssertionError("No instances.");
    }

    /**
     * {@code raw} with every colour that reads under 3:1 over a backdrop of {@code alpha} replaced by the first
     * readable one before it (the line's body colour), else {@code {letters}}. {@code token} answers a palette token's colour, or
     * {@code null} for one it does not know (left as written).
     */
    public static String over(String raw, int alpha, Function<String, Integer> token) {
        if (raw == null || raw.isEmpty() || alpha <= 0) return raw;
        Matcher m = COLOUR.matcher(raw);
        StringBuilder out = new StringBuilder();
        // The line's body colour: the first readable one it is written in (an emphasis later is not it).
        String base = null;
        while (m.find()) {
            Integer rgb = colourOf(m, token);
            String written = m.group();
            if (rgb == null || readable(rgb, alpha) || hued(rgb) || symbolsOnly(raw, m.end())) {
                if (rgb != null && base == null && readable(rgb, alpha)) base = written;
                m.appendReplacement(out, Matcher.quoteReplacement(written));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(base == null ? "{letters}" : base));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A swatch: a colour, and the words after it. */
    public record Swatch(int rgb, String words) {
    }

    private static final Pattern SWATCH = Pattern.compile("^((?:[&§][k-orK-OR])*(?:\\{[a-z_]+}|<#[0-9a-fA-F]{6}>|&#[0-9a-fA-F]{6}|[&§][0-9a-fA-F])(?:[&§][k-orK-OR])*)■\\s*(.*)$", Pattern.DOTALL);

    /**
     * The swatch {@code raw} starts with, a colour written on a {@code ■}, with the words after it; {@code null}
     * when it starts with none, or with a token {@code token} does not know. Since 1.470.1.
     */
    public static Swatch swatch(String raw, Function<String, Integer> token) {
        if (raw == null) return null;
        Matcher m = SWATCH.matcher(raw.strip());
        if (!m.matches()) return null;
        Matcher colour = COLOUR.matcher(m.group(1));
        Integer rgb = null;
        while (colour.find()) rgb = colourOf(colour, token);
        return rgb == null ? null : new Swatch(rgb, m.group(2));
    }

    /** Whether {@code rgb} is a colour rather than a grey: an HSV saturation of a quarter or more. */
    static boolean hued(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int max = Math.max(r, Math.max(g, b));
        return max > 0 && (max - Math.min(r, Math.min(g, b))) / (double) max >= 0.25;
    }

    /** Whether the run from {@code from} to the next colour holds a symbol and no letter or digit. */
    static boolean symbolsOnly(String raw, int from) {
        Matcher next = COLOUR.matcher(raw);
        int end = next.find(from) ? next.start() : raw.length();
        // Format codes (&l) and tags (<bold>) take no room: their letters are not words.
        String run = raw.substring(from, end).replaceAll("[&§][k-orK-OR]|<[^>]*>", "");
        boolean symbol = false;
        for (int i = 0; i < run.length(); ) {
            int c = run.codePointAt(i);
            if (Character.isLetterOrDigit(c)) return false;
            int type = Character.getType(c);
            if (type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL) symbol = true;
            i += Character.charCount(c);
        }
        return symbol;
    }

    private static Integer colourOf(Matcher m, Function<String, Integer> token) {
        if (m.group(1) != null) return token.apply(m.group(1));
        if (m.group(2) != null) return Integer.parseInt(m.group(2), 16);
        if (m.group(3) != null) return Integer.parseInt(m.group(3), 16);
        return LEGACY[Character.digit(m.group(4).charAt(0), 16)];
    }

    /** Whether {@code rgb} reads at 3:1 over the sky and over deepslate, both through a backdrop of {@code alpha}. */
    public static boolean readable(int rgb, int alpha) {
        return contrast(rgb, SKY, alpha) >= MIN_CONTRAST && contrast(rgb, GROUND, alpha) >= MIN_CONTRAST;
    }

    /** WCAG contrast of {@code text} over {@code ground} darkened by a backdrop of {@code alpha}. */
    public static double contrast(int text, int ground, int alpha) {
        double a = alpha / 255.0;
        int r = (int) (((ground >> 16) & 255) * (1 - a));
        int g = (int) (((ground >> 8) & 255) * (1 - a));
        int b = (int) ((ground & 255) * (1 - a));
        double behind = luminance((r << 16) | (g << 8) | b);
        double front = luminance(text);
        return (Math.max(front, behind) + 0.05) / (Math.min(front, behind) + 0.05);
    }

    private static double luminance(int rgb) {
        double[] c = {((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
