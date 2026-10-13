package net.exylia.lib.hologram;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * One line of a text hologram as it is written: the tags at its start and the
 * text after them.
 *
 * <p>The tags are what {@link HologramConfig#lines()} reads to style a line on
 * its own ({@code <size:2>}, {@code <spin>}, {@code <range:8>}...). This is the
 * same reading, public, so an editor can show a line's style as fields and
 * write it back without knowing the notation:
 *
 * <pre>{@code
 * HologramLine line = HologramLine.parse("<size:2><spin>{primary}&lSHOP");
 * line.size();                                  // 2.0
 * line.toBuilder().size(null).pulse(0.06).build().raw();
 * // "<pulse:0.06><spin:90>{primary}&lSHOP"
 * }</pre>
 *
 * <p>Every tag is {@code null} when the line does not write it, which is what
 * keeps a round trip from adding tags nobody asked for. A tag written bare
 * ({@code <spin>}) reads as its default value and is written back with it.
 * Values are kept as written; the hologram clamps them when it draws.
 *
 * @param text       the text after the tags, as the text parser reads it
 * @param item       the material of an item line, or {@code null}
 * @param block      the material of a block line, or {@code null}
 * @param size       how much bigger than the hologram's scale
 * @param opacity    the text opacity, 0 to 255
 * @param background the background, {@code #AARRGGBB}
 * @param shadow     whether the text has a shadow
 * @param glow       the outline of an item or block line: {@code #RRGGBB}, or
 *                   empty for the team colour
 * @param spin       degrees a second it turns
 * @param bob        blocks it floats up and down
 * @param pulse      the fraction of its size it breathes by
 * @param typewriter characters a second it is typed in at
 * @param condition  a comparison that must hold for the viewer
 * @param permission a permission the viewer must have, {@code !} first to
 *                   require lacking it
 * @param range      the furthest a viewer still sees it, in blocks
 * @param shine      the colour of the band of light that sweeps over it: a
 *                   palette name or {@code #RRGGBB}, empty for the default
 * @param flow       characters a second its colours flow along it
 * @since 1.289.1
 */
public record HologramLine(@NotNull String text, @Nullable String item, @Nullable String block,
                           @Nullable Double size, @Nullable Integer opacity,
                           @Nullable String background, @Nullable Boolean shadow,
                           @Nullable String glow, @Nullable Double spin, @Nullable Double bob,
                           @Nullable Double pulse, @Nullable Double typewriter,
                           @Nullable String condition, @Nullable String permission,
                           @Nullable Double range, @Nullable String shine, @Nullable Double flow) {

    /** What a bare tag means. */
    public static final double DEFAULT_SPIN = 90;
    public static final double DEFAULT_BOB = 0.08;
    public static final double DEFAULT_PULSE = 0.06;
    public static final double DEFAULT_TYPEWRITER = 24;
    public static final double DEFAULT_FLOW = 4;

    public HologramLine {
        text = text == null ? "" : text;
    }

    /**
     * A line with no tags.
     *
     * @param text the text
     * @return the line
     */
    public static @NotNull HologramLine of(@Nullable String text) {
        return new Builder().text(text).build();
    }

    /**
     * Reads a line as a hologram does.
     *
     * <p>Only the known tags, and only at the start, are read; anything else,
     * {@code <gradient:...>} included, stays in {@link #text}. A tag whose value
     * does not parse ends the run and stays in the text too, exactly as it
     * would be drawn.
     *
     * @param raw the line as written
     * @return the line, never {@code null}
     */
    public static @NotNull HologramLine parse(@Nullable String raw) {
        Builder line = new Builder();
        String rest = raw == null ? "" : raw;
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
            if (!line.accept(key, value)) {
                break;
            }
            rest = rest.substring(close + 1);
        }
        return line.text(rest).build();
    }

    /**
     * The line as written: its tags, then its text.
     *
     * @return what {@link #parse} reads back into this line
     */
    public @NotNull String raw() {
        StringBuilder out = new StringBuilder();
        tag(out, "item", item);
        tag(out, "block", block);
        tag(out, "size", size);
        tag(out, "opacity", opacity);
        tag(out, "background", background);
        tag(out, "shadow", shadow);
        tag(out, "glow", glow);
        tag(out, "spin", spin);
        tag(out, "bob", bob);
        tag(out, "pulse", pulse);
        tag(out, "shine", shine);
        tag(out, "flow", flow);
        tag(out, "typewriter", typewriter);
        tag(out, "range", range);
        tag(out, "perm", permission);
        if (condition != null) {
            out.append("<if:\"").append(condition).append("\">");
        }
        return out.append(text).toString();
    }

    /** Whether the line is an item or a block rather than text. */
    public boolean isModel() {
        return item != null || block != null;
    }

    /** Whether any tag is written. */
    public boolean hasTags() {
        return !raw().equals(text);
    }

    /**
     * A builder holding this line, to change some of it.
     *
     * @return the builder
     */
    public @NotNull Builder toBuilder() {
        return new Builder().text(text).item(item).block(block).size(size).opacity(opacity)
                .background(background).shadow(shadow).glow(glow).spin(spin).bob(bob).pulse(pulse)
                .typewriter(typewriter).condition(condition).permission(permission).range(range)
                .shine(shine).flow(flow);
    }

    /** @return a builder for a new line */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    private static void tag(StringBuilder out, String key, Object value) {
        if (value == null) {
            return;
        }
        String written = value instanceof Double number
                ? BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
                : String.valueOf(value);
        out.append('<').append(key);
        if (!written.isEmpty()) {
            out.append(':').append(written.indexOf('>') >= 0 ? "\"" + written + "\"" : written);
        }
        out.append('>');
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    /**
     * Builds a line. Every tag starts unwritten; {@code null} clears one, and a
     * blank string clears a text tag too.
     */
    public static final class Builder {
        private String text = "";
        private String item;
        private String block;
        private Double size;
        private Integer opacity;
        private String background;
        private Boolean shadow;
        private String glow;
        private Double spin;
        private Double bob;
        private Double pulse;
        private Double typewriter;
        private String condition;
        private String permission;
        private Double range;
        private String shine;
        private Double flow;

        private Builder() {
        }

        public @NotNull Builder text(@Nullable String text) {
            this.text = text == null ? "" : text;
            return this;
        }

        /** An item line; clears a block. */
        public @NotNull Builder item(@Nullable String material) {
            this.item = blank(material) ? null : material.toUpperCase(Locale.ROOT);
            if (item != null) {
                this.block = null;
            }
            return this;
        }

        /** A block line; clears an item. */
        public @NotNull Builder block(@Nullable String material) {
            this.block = blank(material) ? null : material.toUpperCase(Locale.ROOT);
            if (block != null) {
                this.item = null;
            }
            return this;
        }

        public @NotNull Builder size(@Nullable Double size) {
            this.size = size;
            return this;
        }

        public @NotNull Builder opacity(@Nullable Integer opacity) {
            this.opacity = opacity;
            return this;
        }

        public @NotNull Builder background(@Nullable String background) {
            this.background = blank(background) ? null : background.trim();
            return this;
        }

        public @NotNull Builder shadow(@Nullable Boolean shadow) {
            this.shadow = shadow;
            return this;
        }

        /** {@code null} for no glow, empty for the team colour. */
        public @NotNull Builder glow(@Nullable String glow) {
            this.glow = glow == null ? null : glow.trim();
            return this;
        }

        public @NotNull Builder spin(@Nullable Double spin) {
            this.spin = spin;
            return this;
        }

        public @NotNull Builder bob(@Nullable Double bob) {
            this.bob = bob;
            return this;
        }

        public @NotNull Builder pulse(@Nullable Double pulse) {
            this.pulse = pulse;
            return this;
        }

        public @NotNull Builder typewriter(@Nullable Double typewriter) {
            this.typewriter = typewriter;
            return this;
        }

        public @NotNull Builder condition(@Nullable String condition) {
            this.condition = blank(condition) ? null : condition.trim();
            return this;
        }

        public @NotNull Builder permission(@Nullable String permission) {
            this.permission = blank(permission) ? null : permission.trim();
            return this;
        }

        public @NotNull Builder range(@Nullable Double range) {
            this.range = range;
            return this;
        }

        /** {@code null} for no shine, empty for the default colour. */
        public @NotNull Builder shine(@Nullable String shine) {
            this.shine = shine == null ? null : shine.trim();
            return this;
        }

        public @NotNull Builder flow(@Nullable Double flow) {
            this.flow = flow;
            return this;
        }

        public @NotNull HologramLine build() {
            return new HologramLine(text, item, block, size, opacity, background, shadow, glow,
                    spin, bob, pulse, typewriter, condition, permission, range, shine, flow);
        }

        /** Takes one tag as written, or answers {@code false} when it is not one. */
        private boolean accept(String key, String value) {
            try {
                switch (key) {
                    case "size" -> size = Double.parseDouble(value);
                    case "opacity" -> opacity = Integer.parseInt(value);
                    case "background" -> background = value;
                    case "shadow" -> shadow = value.isEmpty() || Boolean.parseBoolean(value);
                    case "item", "block" -> {
                        if (value.isEmpty()) {
                            return false;
                        }
                        if (key.equals("item")) {
                            item(value);
                        } else {
                            block(value);
                        }
                    }
                    case "glow" -> glow = value;
                    case "spin" -> spin = number(value, DEFAULT_SPIN);
                    case "bob" -> bob = number(value, DEFAULT_BOB);
                    case "pulse" -> pulse = number(value, DEFAULT_PULSE);
                    case "typewriter" -> typewriter = number(value, DEFAULT_TYPEWRITER);
                    case "if" -> condition = value.isEmpty() ? null : value;
                    case "perm" -> permission = value.isEmpty() ? null : value;
                    case "range" -> range = Double.parseDouble(value);
                    case "shine" -> shine = value;
                    case "flow" -> flow = number(value, DEFAULT_FLOW);
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

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
