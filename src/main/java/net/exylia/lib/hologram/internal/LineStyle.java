package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.HologramConfig;

import java.util.Locale;

/**
 * How one line of a text hologram is drawn, read from tags at its start.
 *
 * <pre>
 * lines:
 *   - '&lt;size:0.8&gt;{highlight}⚓ — FULLPVP — ⚓'
 *   - '&lt;size:2.2&gt;{primary}&amp;lMINERALES'
 *   - '&lt;opacity:160&gt;{letters}+14 trades available'
 *   - '&lt;item:DIAMOND&gt;'
 *   - '&lt;background:#66000000&gt;{warning}▸ Click to trade ◂'
 * </pre>
 *
 * <p>Only these tags, and only at the start of a line, are read: anything else,
 * {@code <gradient:...>} included, is left for the text parser. A tag whose
 * value does not parse ends the run and stays in the text, so a typo shows up
 * on screen instead of vanishing.
 *
 * @param item       the material an item line shows, or {@code null} for text
 * @param size       how much bigger than the hologram's own scale, which also
 *                   stretches the room the line takes
 * @param opacity    the line's text opacity, or {@code null} to inherit
 * @param background the line's background, or {@code null} to inherit
 * @param shadow     the line's shadow, or {@code null} to inherit
 */
record LineStyle(String item, double size, Integer opacity, String background, Boolean shadow) {

    static final LineStyle PLAIN = new LineStyle(null, 1.0, null, null, null);

    /** An item at size 1 is half a block, a little taller than a text line. */
    private static final double ITEM_HEIGHT = 0.5;

    /** A line split into its style and the text left to parse. */
    record Parsed(LineStyle style, String body) {
    }

    static Parsed parse(String line) {
        String item = null;
        double size = 1.0;
        Integer opacity = null;
        String background = null;
        Boolean shadow = null;
        String rest = line == null ? "" : line;

        while (rest.startsWith("<")) {
            int close = rest.indexOf('>');
            if (close < 0) {
                break;
            }
            String tag = rest.substring(1, close);
            int colon = tag.indexOf(':');
            String key = (colon < 0 ? tag : tag.substring(0, colon)).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : tag.substring(colon + 1).trim();
            try {
                switch (key) {
                    case "size" -> size = Math.clamp(Double.parseDouble(value), 0.1, 10.0);
                    case "opacity" -> opacity = Math.clamp(Integer.parseInt(value), 0, 255);
                    case "background" -> background = value;
                    case "shadow" -> shadow = value.isEmpty() || Boolean.parseBoolean(value);
                    case "item" -> item = value.toUpperCase(Locale.ROOT);
                    default -> {
                        return new Parsed(new LineStyle(item, size, opacity, background, shadow), rest);
                    }
                }
            } catch (NumberFormatException ignored) {
                break;
            }
            rest = rest.substring(close + 1);
        }
        return new Parsed(new LineStyle(item, size, opacity, background, shadow), rest);
    }

    boolean isItem() {
        return item != null;
    }

    /** The hologram's properties with this line's overrides and size applied. */
    HologramConfig.Properties apply(HologramConfig.Properties base) {
        double scale = isItem() ? size * ITEM_HEIGHT : size;
        return new HologramConfig.Properties(base.billboard(), base.alignment(),
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
        return isItem() ? ITEM_HEIGHT * size * base.scaleY() : base.lineSpacing() * size;
    }
}
