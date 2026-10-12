package net.exylia.lib.hologram.internal;

import net.exylia.lib.hologram.HologramConfig;

import java.util.Locale;

/**
 * How one line of a text hologram is drawn, read from tags at its start.
 *
 * <pre>
 * lines:
 *   - '&lt;size:0.8&gt;{highlight}⚓ — FULLPVP — ⚓'
 *   - '&lt;size:2.2&gt;&lt;pulse&gt;{primary}&amp;lMINERALES'
 *   - '&lt;typewriter&gt;{letters}+14 trades available'
 *   - '&lt;block:BEACON&gt;&lt;spin&gt;&lt;bob&gt;'
 *   - '&lt;range:8&gt;&lt;background:#66000000&gt;{warning}▸ Click to trade ◂'
 *   - '&lt;perm:shop.vip&gt;{highlight}✦ VIP discount active'
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
 */
record LineStyle(String model, boolean block, double size, Integer opacity, String background,
                 Boolean shadow, String glow, double spin, double bob, double pulse,
                 double typewriter, String condition, String permission, double range) {

    static final LineStyle PLAIN = new LineStyle(null, false, 1.0, null, null, null, null,
            0, 0, 0, 0, null, null, 0);

    /** An item or block at size 1 is half a block, a little taller than a text line. */
    static final double MODEL_HEIGHT = 0.5;

    /** The fastest spin that still reads as one direction between two keyframes. */
    private static final double MAX_SPIN = 300;

    /** A line split into its style and the text left to parse. */
    record Parsed(LineStyle style, String body) {
    }

    static Parsed parse(String line) {
        Builder style = new Builder();
        String rest = line == null ? "" : line;

        while (rest.startsWith("<")) {
            int colon = -1;
            int close = -1;
            for (int i = 1; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == ':' && colon < 0) {
                    colon = i;
                    if (i + 1 < rest.length() && rest.charAt(i + 1) == '"') {
                        int quote = rest.indexOf('"', i + 2);
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
            String key = rest.substring(1, colon < 0 ? close : colon).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : unquote(rest.substring(colon + 1, close).trim());
            if (!style.accept(key, value)) {
                break;
            }
            rest = rest.substring(close + 1);
        }
        return new Parsed(style.build(), rest);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    boolean isModel() {
        return model != null;
    }

    boolean animated() {
        return spin != 0 || bob != 0 || pulse != 0;
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

    /** Collects tags; one place knows every key and what its value means. */
    private static final class Builder {
        private String model;
        private boolean block;
        private double size = 1.0;
        private Integer opacity;
        private String background;
        private Boolean shadow;
        private String glow;
        private double spin;
        private double bob;
        private double pulse;
        private double typewriter;
        private String condition;
        private String permission;
        private double range;

        /** Takes one tag, or answers {@code false} when it is not one of ours. */
        boolean accept(String key, String value) {
            try {
                switch (key) {
                    case "size" -> size = Math.clamp(Double.parseDouble(value), 0.1, 10.0);
                    case "opacity" -> opacity = Math.clamp(Integer.parseInt(value), 0, 255);
                    case "background" -> background = value;
                    case "shadow" -> shadow = value.isEmpty() || Boolean.parseBoolean(value);
                    case "item", "block" -> {
                        if (value.isEmpty()) {
                            return false;
                        }
                        model = value.toUpperCase(Locale.ROOT);
                        block = key.equals("block");
                    }
                    case "glow" -> glow = value;
                    case "spin" -> spin = Math.clamp(number(value, 90), -MAX_SPIN, MAX_SPIN);
                    case "bob" -> bob = Math.clamp(number(value, 0.08), 0, 2);
                    case "pulse" -> pulse = Math.clamp(number(value, 0.06), 0, 0.9);
                    case "typewriter" -> typewriter = Math.clamp(number(value, 24), 1, 1000);
                    case "if" -> condition = value.isEmpty() ? null : value;
                    case "perm" -> permission = value.isEmpty() ? null : value;
                    case "range" -> range = Math.max(0, Double.parseDouble(value));
                    default -> {
                        return false;
                    }
                }
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private static double number(String value, double fallback) {
            return value.isEmpty() ? fallback : Double.parseDouble(value);
        }

        LineStyle build() {
            return new LineStyle(model, block, size, opacity, background, shadow, glow,
                    spin, bob, pulse, typewriter, condition, permission, range);
        }
    }
}
