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
            if (rgb == null || readable(rgb, alpha)) {
                if (rgb != null && base == null) base = written;
                m.appendReplacement(out, Matcher.quoteReplacement(written));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(base == null ? "{letters}" : base));
            }
        }
        m.appendTail(out);
        return out.toString();
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
