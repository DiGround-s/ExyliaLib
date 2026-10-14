package net.exylia.lib.hologram;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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
 * @param head       the player head of a head line: a player name, a UUID, a
 *                   base64 texture or a {@code textures.minecraft.net} URL, or
 *                   {@code null}; since 1.297.0
 * @param gap          extra blocks above the line, negative to pull the lines
 *                     above it closer; since 1.301.0
 * @param align        the line's own text alignment: {@code left},
 *                     {@code center} or {@code right}; since 1.301.0
 * @param width        where the line wraps, in pixels; since 1.301.0
 * @param seeThrough   whether the line shows through blocks; since 1.301.0
 * @param brightness   the line's fixed light level, 0 to 15, or -1 for the
 *                     light where it stands; since 1.301.0
 * @param billboard    how the line turns towards the player: {@code fixed},
 *                     {@code center}, {@code vertical} or {@code horizontal};
 *                     since 1.301.0
 * @param autoscale    how the line grows with the viewer's distance: empty for
 *                     the hologram's curve, or {@code start,end,max}; since
 *                     1.301.0
 * @param shineSpeed   characters a second the shine band travels; since 1.301.0
 * @param shinePause   seconds between two sweeps of the shine; since 1.301.0
 * @param shineWidth   half the width of the shine band, in characters; since
 *                     1.301.0
 * @param bobPeriod    seconds one bob takes, up and back; since 1.301.0
 * @param pulsePeriod  seconds one pulse takes; since 1.301.0
 * @param sway         degrees an item or block line turns back and forth;
 *                     since 1.301.0
 * @param swayPeriod   seconds one sway takes, there and back; since 1.301.0
 * @param frames       seconds each frame of the line shows; frames are split
 *                     by {@code ||} in the text. Since 1.301.0
 * @param rainbow      characters a second the rainbow runs along the line;
 *                     since 1.301.0
 * @param fade         seconds one fade out and back in takes; since 1.301.0
 * @param blink        seconds one blink takes, shown then hidden; since 1.301.0
 * @param marquee      how many characters of the line show at once while it
 *                     scrolls through them; since 1.301.0
 * @param marqueeSpeed characters a second the marquee scrolls; since 1.301.0
 * @param scramble     characters a second settle out of random glyphs when a
 *                     player first sees the line; since 1.301.0
 * @param button       the name of the line's own click actions in
 *                     {@code click.buttons}, which makes the line a button of
 *                     its own; since 1.301.0
 * @since 1.289.1
 */
public record HologramLine(@NotNull String text, @Nullable String item, @Nullable String block,
                           @Nullable Double size, @Nullable Integer opacity,
                           @Nullable String background, @Nullable Boolean shadow,
                           @Nullable String glow, @Nullable Double spin, @Nullable Double bob,
                           @Nullable Double pulse, @Nullable Double typewriter,
                           @Nullable String condition, @Nullable String permission,
                           @Nullable Double range, @Nullable String shine, @Nullable Double flow,
                           @Nullable String head, @Nullable Double gap, @Nullable String align,
                           @Nullable Integer width, @Nullable Boolean seeThrough,
                           @Nullable Integer brightness, @Nullable String billboard,
                           @Nullable String autoscale, @Nullable Double shineSpeed,
                           @Nullable Double shinePause, @Nullable Double shineWidth,
                           @Nullable Double bobPeriod, @Nullable Double pulsePeriod,
                           @Nullable Double sway, @Nullable Double swayPeriod,
                           @Nullable Double frames, @Nullable Double rainbow, @Nullable Double fade,
                           @Nullable Double blink, @Nullable Integer marquee,
                           @Nullable Double marqueeSpeed, @Nullable Double scramble,
                           @Nullable String button) {

    /** What a bare tag means. */
    public static final double DEFAULT_SPIN = 90;
    public static final double DEFAULT_BOB = 0.08;
    public static final double DEFAULT_PULSE = 0.06;
    public static final double DEFAULT_TYPEWRITER = 24;
    public static final double DEFAULT_FLOW = 4;
    /** @since 1.301.0 */
    public static final double DEFAULT_SWAY = 15;
    /** @since 1.301.0 */
    public static final double DEFAULT_FRAMES = 2;
    /** @since 1.301.0 */
    public static final double DEFAULT_RAINBOW = 6;
    /** @since 1.301.0 */
    public static final double DEFAULT_FADE = 3;
    /** @since 1.301.0 */
    public static final double DEFAULT_BLINK = 1;
    /** @since 1.301.0 */
    public static final int DEFAULT_MARQUEE = 24;
    /** @since 1.301.0 */
    public static final double DEFAULT_SCRAMBLE = 20;

    /** What splits the frames of a {@code <frames>} line. Since 1.301.0. */
    public static final String FRAME_SEPARATOR = "||";

    public HologramLine {
        text = text == null ? "" : text;
        // An option belongs to its tag: without the tag it is never written,
        // so it is not kept either, and a round trip stays equal.
        if (shine == null) {
            shineSpeed = null;
            shinePause = null;
            shineWidth = null;
        }
        if (bob == null) {
            bobPeriod = null;
        }
        if (pulse == null) {
            pulsePeriod = null;
        }
        if (sway == null) {
            swayPeriod = null;
        }
        if (marquee == null) {
            marqueeSpeed = null;
        }
    }

    /**
     * A line as written before 1.301.0 added layout, motion options, new
     * animations and buttons.
     *
     * @deprecated use {@link #builder()}
     */
    @Deprecated(since = "1.301.0")
    public HologramLine(@NotNull String text, @Nullable String item, @Nullable String block,
                        @Nullable Double size, @Nullable Integer opacity,
                        @Nullable String background, @Nullable Boolean shadow,
                        @Nullable String glow, @Nullable Double spin, @Nullable Double bob,
                        @Nullable Double pulse, @Nullable Double typewriter,
                        @Nullable String condition, @Nullable String permission,
                        @Nullable Double range, @Nullable String shine, @Nullable Double flow,
                        @Nullable String head) {
        this(text, item, block, size, opacity, background, shadow, glow, spin, bob, pulse,
                typewriter, condition, permission, range, shine, flow, head,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * A line as written before head lines existed.
     *
     * @deprecated use {@link #builder()}, which also takes {@link Builder#head}
     */
    @Deprecated(since = "1.297.0")
    public HologramLine(@NotNull String text, @Nullable String item, @Nullable String block,
                        @Nullable Double size, @Nullable Integer opacity,
                        @Nullable String background, @Nullable Boolean shadow,
                        @Nullable String glow, @Nullable Double spin, @Nullable Double bob,
                        @Nullable Double pulse, @Nullable Double typewriter,
                        @Nullable String condition, @Nullable String permission,
                        @Nullable Double range, @Nullable String shine, @Nullable Double flow) {
        this(text, item, block, size, opacity, background, shadow, glow, spin, bob, pulse,
                typewriter, condition, permission, range, shine, flow, (String) null);
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
        tag(out, "head", head);
        tag(out, "size", size);
        tag(out, "opacity", opacity);
        tag(out, "background", background);
        tag(out, "shadow", shadow);
        tag(out, "glow", glow);
        tag(out, "align", align);
        tag(out, "width", width);
        tag(out, "billboard", billboard);
        tag(out, "seethrough", seeThrough);
        tag(out, "brightness", brightness);
        tag(out, "gap", gap);
        tag(out, "autoscale", autoscale);
        tag(out, "spin", spin);
        tag(out, "sway", sway == null ? null : options(sway, "period", swayPeriod));
        tag(out, "bob", bob == null ? null : options(bob, "period", bobPeriod));
        tag(out, "pulse", pulse == null ? null : options(pulse, "period", pulsePeriod));
        tag(out, "shine", shine == null ? null
                : options(shine, "speed", shineSpeed, "pause", shinePause, "width", shineWidth));
        tag(out, "flow", flow);
        tag(out, "rainbow", rainbow);
        tag(out, "frames", frames);
        tag(out, "marquee", marquee == null ? null : options(marquee, "speed", marqueeSpeed));
        tag(out, "fade", fade);
        tag(out, "blink", blink);
        tag(out, "typewriter", typewriter);
        tag(out, "scramble", scramble);
        tag(out, "range", range);
        tag(out, "perm", permission);
        tag(out, "button", button);
        if (condition != null) {
            out.append("<if:\"").append(condition).append("\">");
        }
        return out.append(text).toString();
    }

    /** Whether the line is an item, a block or a head rather than text. */
    public boolean isModel() {
        return item != null || block != null || head != null;
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
                .shine(shine).flow(flow).head(head).gap(gap).align(align).width(width)
                .seeThrough(seeThrough).brightness(brightness).billboard(billboard)
                .autoscale(autoscale).shineSpeed(shineSpeed).shinePause(shinePause)
                .shineWidth(shineWidth).bobPeriod(bobPeriod).pulsePeriod(pulsePeriod).sway(sway)
                .swayPeriod(swayPeriod).frames(frames).rainbow(rainbow).fade(fade).blink(blink)
                .marquee(marquee).marqueeSpeed(marqueeSpeed).scramble(scramble).button(button);
    }


    /** @return a builder for a new line */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    private static void tag(StringBuilder out, String key, Object value) {
        if (value == null) {
            return;
        }
        String written = format(value);
        out.append('<').append(key);
        if (!written.isEmpty()) {
            out.append(':').append(written.indexOf('>') >= 0 ? "\"" + written + "\"" : written);
        }
        out.append('>');
    }

    private static String format(Object value) {
        return value instanceof Double number
                ? BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
                : String.valueOf(value);
    }

    /** A tag's value with its {@code key=value} options after it, the unset ones left out. */
    private static String options(Object main, Object... pairs) {
        StringBuilder out = new StringBuilder(format(main));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                if (!out.isEmpty()) {
                    out.append(',');
                }
                out.append(pairs[i]).append('=').append(format(pairs[i + 1]));
            }
        }
        return out.toString();
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
        private String head;
        private Double gap;
        private String align;
        private Integer width;
        private Boolean seeThrough;
        private Integer brightness;
        private String billboard;
        private String autoscale;
        private Double shineSpeed;
        private Double shinePause;
        private Double shineWidth;
        private Double bobPeriod;
        private Double pulsePeriod;
        private Double sway;
        private Double swayPeriod;
        private Double frames;
        private Double rainbow;
        private Double fade;
        private Double blink;
        private Integer marquee;
        private Double marqueeSpeed;
        private Double scramble;
        private String button;

        private Builder() {
        }

        public @NotNull Builder text(@Nullable String text) {
            this.text = text == null ? "" : text;
            return this;
        }

        /** An item line; clears a block and a head. */
        public @NotNull Builder item(@Nullable String material) {
            this.item = blank(material) ? null : material.toUpperCase(Locale.ROOT);
            if (item != null) {
                this.block = null;
                this.head = null;
            }
            return this;
        }

        /** A block line; clears an item and a head. */
        public @NotNull Builder block(@Nullable String material) {
            this.block = blank(material) ? null : material.toUpperCase(Locale.ROOT);
            if (block != null) {
                this.item = null;
                this.head = null;
            }
            return this;
        }

        /**
         * A player head line, drawn like an item line; clears an item and a
         * block.
         *
         * @param head a player name, a UUID, a base64 texture or a
         *             {@code textures.minecraft.net} URL
         * @return this builder
         * @since 1.297.0
         */
        public @NotNull Builder head(@Nullable String head) {
            this.head = blank(head) ? null : head.trim();
            if (this.head != null) {
                this.item = null;
                this.block = null;
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

        /** Extra blocks above the line, negative to pull the lines above closer. Since 1.301.0. */
        public @NotNull Builder gap(@Nullable Double gap) {
            this.gap = gap;
            return this;
        }

        /** {@code left}, {@code center} or {@code right}. Since 1.301.0. */
        public @NotNull Builder align(@Nullable String align) {
            this.align = blank(align) ? null : align.trim().toLowerCase(Locale.ROOT);
            return this;
        }

        /** Where the line wraps, in pixels. Since 1.301.0. */
        public @NotNull Builder width(@Nullable Integer width) {
            this.width = width;
            return this;
        }

        /** Since 1.301.0. */
        public @NotNull Builder seeThrough(@Nullable Boolean seeThrough) {
            this.seeThrough = seeThrough;
            return this;
        }

        /** 0 to 15, or -1 for the light where it stands. Since 1.301.0. */
        public @NotNull Builder brightness(@Nullable Integer brightness) {
            this.brightness = brightness;
            return this;
        }

        /** {@code fixed}, {@code center}, {@code vertical} or {@code horizontal}. Since 1.301.0. */
        public @NotNull Builder billboard(@Nullable String billboard) {
            this.billboard = blank(billboard) ? null : billboard.trim().toLowerCase(Locale.ROOT);
            return this;
        }

        /**
         * Empty for the hologram's distance curve, {@code start,end,max} for the
         * line's own, {@code null} for none. Since 1.301.0.
         */
        public @NotNull Builder autoscale(@Nullable String autoscale) {
            this.autoscale = autoscale == null ? null : autoscale.replace(" ", "");
            return this;
        }

        /** Characters a second; kept only with {@link #shine}. Since 1.301.0. */
        public @NotNull Builder shineSpeed(@Nullable Double shineSpeed) {
            this.shineSpeed = shineSpeed;
            return this;
        }

        /** Seconds between sweeps; kept only with {@link #shine}. Since 1.301.0. */
        public @NotNull Builder shinePause(@Nullable Double shinePause) {
            this.shinePause = shinePause;
            return this;
        }

        /** Half the band, in characters; kept only with {@link #shine}. Since 1.301.0. */
        public @NotNull Builder shineWidth(@Nullable Double shineWidth) {
            this.shineWidth = shineWidth;
            return this;
        }

        /** Seconds; kept only with {@link #bob}. Since 1.301.0. */
        public @NotNull Builder bobPeriod(@Nullable Double bobPeriod) {
            this.bobPeriod = bobPeriod;
            return this;
        }

        /** Seconds; kept only with {@link #pulse}. Since 1.301.0. */
        public @NotNull Builder pulsePeriod(@Nullable Double pulsePeriod) {
            this.pulsePeriod = pulsePeriod;
            return this;
        }

        /** Degrees either way. Since 1.301.0. */
        public @NotNull Builder sway(@Nullable Double sway) {
            this.sway = sway;
            return this;
        }

        /** Seconds; kept only with {@link #sway}. Since 1.301.0. */
        public @NotNull Builder swayPeriod(@Nullable Double swayPeriod) {
            this.swayPeriod = swayPeriod;
            return this;
        }

        /** Seconds a frame. Since 1.301.0. */
        public @NotNull Builder frames(@Nullable Double frames) {
            this.frames = frames;
            return this;
        }

        /** Characters a second. Since 1.301.0. */
        public @NotNull Builder rainbow(@Nullable Double rainbow) {
            this.rainbow = rainbow;
            return this;
        }

        /** Seconds a fade out and in. Since 1.301.0. */
        public @NotNull Builder fade(@Nullable Double fade) {
            this.fade = fade;
            return this;
        }

        /** Seconds a blink. Since 1.301.0. */
        public @NotNull Builder blink(@Nullable Double blink) {
            this.blink = blink;
            return this;
        }

        /** Characters shown at once. Since 1.301.0. */
        public @NotNull Builder marquee(@Nullable Integer marquee) {
            this.marquee = marquee;
            return this;
        }

        /** Characters a second; kept only with {@link #marquee}. Since 1.301.0. */
        public @NotNull Builder marqueeSpeed(@Nullable Double marqueeSpeed) {
            this.marqueeSpeed = marqueeSpeed;
            return this;
        }

        /** Characters a second. Since 1.301.0. */
        public @NotNull Builder scramble(@Nullable Double scramble) {
            this.scramble = scramble;
            return this;
        }

        /** The name of the line's click actions in {@code click.buttons}. Since 1.301.0. */
        public @NotNull Builder button(@Nullable String button) {
            this.button = blank(button) ? null : button.trim();
            return this;
        }

        public @NotNull HologramLine build() {
            return new HologramLine(text, item, block, size, opacity, background, shadow, glow,
                    spin, bob, pulse, typewriter, condition, permission, range, shine, flow, head,
                    gap, align, width, seeThrough, brightness, billboard, autoscale, shineSpeed,
                    shinePause, shineWidth, bobPeriod, pulsePeriod, sway, swayPeriod, frames,
                    rainbow, fade, blink, marquee, marqueeSpeed, scramble, button);
        }

        /** Takes one tag as written, or answers {@code false} when it is not one. */
        private boolean accept(String key, String value) {
            try {
                switch (key) {
                    case "size" -> size = Double.parseDouble(value);
                    case "opacity" -> opacity = Integer.parseInt(value);
                    case "background" -> background = value;
                    case "shadow" -> shadow = value.isEmpty() || Boolean.parseBoolean(value);
                    case "item", "block", "head" -> {
                        if (value.isEmpty()) {
                            return false;
                        }
                        switch (key) {
                            case "item" -> item(value);
                            case "block" -> block(value);
                            default -> head(value);
                        }
                    }
                    case "glow" -> glow = value;
                    case "spin" -> spin = number(value, DEFAULT_SPIN);
                    case "typewriter" -> typewriter = number(value, DEFAULT_TYPEWRITER);
                    case "if" -> condition = value.isEmpty() ? null : value;
                    case "perm" -> permission = value.isEmpty() ? null : value;
                    case "range" -> range = Double.parseDouble(value);
                    case "shine" -> {
                        Map<String, Double> options = new HashMap<>();
                        String colour = options(value, options, "speed", "pause", "width");
                        shine = colour;
                        shineSpeed = options.get("speed");
                        shinePause = options.get("pause");
                        shineWidth = options.get("width");
                    }
                    case "flow" -> flow = number(value, DEFAULT_FLOW);
                    case "bob", "pulse", "sway" -> {
                        Map<String, Double> options = new HashMap<>();
                        String amount = options(value, options, "period");
                        double parsed = number(amount, switch (key) {
                            case "bob" -> DEFAULT_BOB;
                            case "pulse" -> DEFAULT_PULSE;
                            default -> DEFAULT_SWAY;
                        });
                        Double period = options.get("period");
                        switch (key) {
                            case "bob" -> {
                                bob = parsed;
                                bobPeriod = period;
                            }
                            case "pulse" -> {
                                pulse = parsed;
                                pulsePeriod = period;
                            }
                            default -> {
                                sway = parsed;
                                swayPeriod = period;
                            }
                        }
                    }
                    case "gap" -> gap = Double.parseDouble(value);
                    case "align" -> {
                        if (!ALIGNMENTS.contains(value.toLowerCase(Locale.ROOT))) {
                            return false;
                        }
                        align(value);
                    }
                    case "width" -> width = Integer.parseInt(value);
                    case "seethrough" -> seeThrough = value.isEmpty() || Boolean.parseBoolean(value);
                    case "brightness" -> brightness = Integer.parseInt(value);
                    case "billboard" -> {
                        if (!BILLBOARDS.contains(value.toLowerCase(Locale.ROOT))) {
                            return false;
                        }
                        billboard(value);
                    }
                    case "autoscale" -> {
                        String curve = value.replace(" ", "");
                        if (!curve.isEmpty()) {
                            String[] parts = curve.split(",", -1);
                            if (parts.length != 3) {
                                return false;
                            }
                            for (String part : parts) {
                                Double.parseDouble(part);
                            }
                        }
                        autoscale = curve;
                    }
                    case "frames" -> frames = value.isEmpty() ? DEFAULT_FRAMES : seconds(value);
                    case "rainbow" -> rainbow = number(value, DEFAULT_RAINBOW);
                    case "fade" -> fade = value.isEmpty() ? DEFAULT_FADE : seconds(value);
                    case "blink" -> blink = value.isEmpty() ? DEFAULT_BLINK : seconds(value);
                    case "marquee" -> {
                        Map<String, Double> options = new HashMap<>();
                        String shown = options(value, options, "speed");
                        marquee = shown.isEmpty() ? DEFAULT_MARQUEE : Integer.parseInt(shown);
                        marqueeSpeed = options.get("speed");
                    }
                    case "scramble" -> scramble = number(value, DEFAULT_SCRAMBLE);
                    case "button" -> {
                        if (value.isEmpty()) {
                            return false;
                        }
                        button(value);
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

        private static final java.util.Set<String> ALIGNMENTS = java.util.Set.of("left", "center", "right");
        private static final java.util.Set<String> BILLBOARDS =
                java.util.Set.of("fixed", "center", "vertical", "horizontal");

        /**
         * Splits {@code value,key=number,...} into the value, returned, and
         * its options, put in {@code into}.
         *
         * @throws NumberFormatException for an option that is not one of
         *                               {@code allowed} or not a number, so the
         *                               tag stays in the text
         */
        private static String options(String value, Map<String, Double> into, String... allowed) {
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
                if (equals < 0 || !java.util.Arrays.asList(allowed).contains(name)) {
                    throw new NumberFormatException("not an option: " + part);
                }
                into.put(name, seconds(part.substring(equals + 1).trim()));
            }
            return main;
        }

        /** A number of seconds, written bare, with {@code s} or with {@code ms}. */
        private static double seconds(String value) {
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            if (trimmed.endsWith("ms")) {
                return Double.parseDouble(trimmed.substring(0, trimmed.length() - 2)) / 1000;
            }
            if (trimmed.endsWith("s")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return Double.parseDouble(trimmed);
        }

        private static double number(String value, double fallback) {
            return value.isEmpty() ? fallback : Double.parseDouble(value);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
