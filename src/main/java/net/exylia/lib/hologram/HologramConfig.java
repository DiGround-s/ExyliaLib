package net.exylia.lib.hologram;

import net.exylia.lib.config.Comment;

import java.util.List;
import java.util.Map;
import net.exylia.lib.config.Time;

/**
 * A hologram declared in a config file.
 *
 * <p>The plugin says <em>where</em> a hologram belongs; the server owner decides
 * what it looks like:
 *
 * <pre>{@code
 * Holograms.show(this, "koth-" + arena.id(), arena.centre(), config.get().koth());
 * }</pre>
 *
 * <p>Written in YAML, that same hologram is:
 *
 * <pre>
 * koth:
 *   enabled: true
 *   lines:
 *     - '{warning}⚔ {highlight}&amp;l%event_name%'
 *     - ' '
 *     - '{letters}Time: {info}%event_time%'
 *   view-distance: 32.0
 *   offset-y: 2.0
 *   properties:
 *     billboard: CENTER
 *     scale-x: 1.2
 *     scale-y: 1.2
 *     scale-z: 1.2
 *     background-color: '#00000000'
 *   config:
 *     update-interval: 20
 *     auto-update: true
 * </pre>
 *
 * <h2>Keys kept from ExyliaCommons</h2>
 * The section reads exactly the keys ExyliaCommons wrote, so a server moving to
 * a plugin built on ExyliaLib keeps its file untouched: {@code lines},
 * {@code enabled}, {@code per-player}, {@code view-distance},
 * {@code offset-x/y/z}, everything under {@code properties}, and
 * {@code config.update-interval} / {@code config.auto-update}.
 *
 * <p>As in the scoreboard module, {@code update-interval} stays in <em>ticks</em>
 * rather than the seconds the effect module uses, because an existing
 * {@code update-interval: 20} has to keep meaning one second.
 *
 * <p>Two ExyliaCommons keys are read and ignored, because this module has no
 * state for them to control: {@code persistent} (nothing is written back to
 * disk by the library) and {@code config.spawn-on-chunk-load} /
 * {@code config.remove-on-chunk-unload} (a hologram is packets, so it exists
 * for whoever can see it and costs nothing when nobody can).
 *
 * @param enabled      whether the hologram shows at all
 * @param type         {@code TEXT}, {@code ITEM} or {@code BLOCK}
 * @param lines        the text lines, top first; only for {@code TEXT}
 * @param item         the item to show, for {@code ITEM}
 * @param block        the block to show, for {@code BLOCK}
 * @param perPlayer    whether every viewer gets their own copy, which is what
 *                     lets placeholders differ per player
 * @param viewDistance how far away a player still sees it, in blocks
 * @param offsetX      shift from the location it is shown at
 * @param offsetY      shift from the location it is shown at
 * @param offsetZ      shift from the location it is shown at
 * @param properties   how it is drawn
 * @param config       how it refreshes
 * @param click        what clicking it does; since 1.287.0
 * @param pages        the pages after the first, which is {@link #lines()};
 *                     each viewer sees one page at a time. Since 1.297.0
 * @param pageInterval ticks before each viewer is turned to the next page on
 *                     their own, 0 to only turn on a click or from code. Since
 *                     1.297.0
 * @since 1.6.0
 */
public record HologramConfig(
        @Comment("Set to false to keep the section without showing the hologram.")
        boolean enabled,

        @Comment("TEXT, ITEM or BLOCK.")
        Kind type,

        @Comment("The lines, top first. Supports colours like {primary} and placeholders.")
        @Comment("Only used by TEXT holograms.")
        @Comment("Start a line with tags to style it alone:")
        @Comment("  look: <size:2> <opacity:160> <background:#66000000> <shadow:false>")
        @Comment("  models: <item:DIAMOND> <block:BEACON> <glow> <glow:#8a51c4>")
        @Comment("  layout: <gap:0.3> <align:left> <width:150> <billboard:fixed> <seethrough> <brightness:15> <autoscale>")
        @Comment("  motion: <spin> <spin:180> <sway:20> <bob> <bob:0.1,period=3> <pulse> <typewriter> <scramble>")
        @Comment("  colour: <shine> <shine:highlight,speed=15,pause=1,width=4> <flow> <flow:8> <rainbow>")
        @Comment("  text: <frames:2> First||Second, <marquee:20> <fade> <blink>")
        @Comment("  clicks: <button:join> runs click.buttons.join when that line is clicked")
        @Comment("  who sees it: <range:8> <perm:vip.rank> <perm:!vip.rank> <if:\"%player_level% >= 10\">")
        @Comment("  heads: <head:Notch> <head:<uuid|base64|textures.minecraft.net url>>")
        @Comment("These lines are page 1; more pages go under 'pages'.")
        List<String> lines,

        @Comment("The item to show, such as DIAMOND_SWORD. Only used by ITEM holograms.")
        String item,

        @Comment("The block to show, such as BEACON. Only used by BLOCK holograms.")
        String block,

        @Comment("Give every player their own copy, so placeholders can differ per viewer.")
        @Comment("Costs one set of packets per viewer instead of one for everybody.")
        boolean perPlayer,

        @Comment("How far away a player still sees it, in blocks.")
        double viewDistance,

        @Comment("Shift from the location the plugin puts it at.")
        double offsetX,

        @Comment("Shift from the location the plugin puts it at.")
        double offsetY,

        @Comment("Shift from the location the plugin puts it at.")
        double offsetZ,

        @Comment("How the hologram is drawn.")
        Properties properties,

        @Comment("How the hologram refreshes.")
        Refresh config,

        @Comment("What clicking the hologram does. Without commands it cannot be clicked.")
        @Comment("On a hologram with pages, these run on page 1; '[PAGE] next', '[PAGE] previous' or '[PAGE] 2' turns it.")
        Click click,

        @Comment("More pages after the lines above, each with its own 'lines' and 'click' lines.")
        @Comment("Every player sees one page at a time and turns it with a '[PAGE] next' click line.")
        List<Page> pages,

        @Comment("Ticks before each player is turned to the next page on their own. 0 turns it off.")
        @Comment("Reads a written duration too: 5s, 1m.")
        @Time(Time.Unit.TICKS) long pageInterval) {

    /** What a hologram is made of. */
    public enum Kind {
        /** Floating text, one display per line. */
        TEXT,
        /** A floating item. */
        ITEM,
        /** A floating block. */
        BLOCK
    }

    /**
     * A disabled, empty hologram, which is what an unwritten section means.
     *
     * <p>Handing this to {@link Holograms#show} shows nothing rather than
     * making every caller check first.
     */
    public HologramConfig() {
        this(false, Kind.TEXT, List.of(), "", "", false, 48.0, 0, 0, 0,
                new Properties(), new Refresh(), new Click(), List.of(), 0);
    }

    /**
     * A hologram with one page, as written before {@code pages} existed.
     *
     * @param enabled      whether the hologram shows at all
     * @param type         {@code TEXT}, {@code ITEM} or {@code BLOCK}
     * @param lines        the text lines, top first
     * @param item         the item to show, for {@code ITEM}
     * @param block        the block to show, for {@code BLOCK}
     * @param perPlayer    whether every viewer gets their own copy
     * @param viewDistance how far away a player still sees it
     * @param offsetX      shift from the location it is shown at
     * @param offsetY      shift from the location it is shown at
     * @param offsetZ      shift from the location it is shown at
     * @param properties   how it is drawn
     * @param config       how it refreshes
     * @param click        what clicking it does
     */
    public HologramConfig(boolean enabled, Kind type, List<String> lines, String item, String block,
                          boolean perPlayer, double viewDistance, double offsetX, double offsetY,
                          double offsetZ, Properties properties, Refresh config, Click click) {
        this(enabled, type, lines, item, block, perPlayer, viewDistance, offsetX, offsetY, offsetZ,
                properties, config, click, List.of(), 0);
    }

    /**
     * A hologram nobody can click, as written before {@code click} existed.
     *
     * @param enabled      whether the hologram shows at all
     * @param type         {@code TEXT}, {@code ITEM} or {@code BLOCK}
     * @param lines        the text lines, top first
     * @param item         the item to show, for {@code ITEM}
     * @param block        the block to show, for {@code BLOCK}
     * @param perPlayer    whether every viewer gets their own copy
     * @param viewDistance how far away a player still sees it
     * @param offsetX      shift from the location it is shown at
     * @param offsetY      shift from the location it is shown at
     * @param offsetZ      shift from the location it is shown at
     * @param properties   how it is drawn
     * @param config       how it refreshes
     */
    public HologramConfig(boolean enabled, Kind type, List<String> lines, String item, String block,
                          boolean perPlayer, double viewDistance, double offsetX, double offsetY,
                          double offsetZ, Properties properties, Refresh config) {
        this(enabled, type, lines, item, block, perPlayer, viewDistance, offsetX, offsetY, offsetZ,
                properties, config, new Click());
    }

    public HologramConfig {
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (type == null) {
            type = Kind.TEXT;
        }
        if (item == null) {
            item = "";
        }
        if (block == null) {
            block = "";
        }
        if (properties == null) {
            properties = new Properties();
        }
        if (config == null) {
            config = new Refresh();
        }
        if (click == null) {
            click = new Click();
        }
        pages = pages == null ? List.of() : List.copyOf(pages);
        pageInterval = Math.max(0, pageInterval);
        if (viewDistance <= 0) {
            viewDistance = 48.0;
        }
    }

    /**
     * How a hologram is drawn, as written in config.
     *
     * @param billboard         how it turns towards the player: {@code CENTER},
     *                          {@code VERTICAL}, {@code HORIZONTAL} or
     *                          {@code FIXED}
     * @param alignment         text alignment: {@code CENTER}, {@code LEFT} or
     *                          {@code RIGHT}
     * @param scaleX            size along each axis
     * @param scaleY            size along each axis
     * @param scaleZ            size along each axis
     * @param shadow            whether the text is drawn with a shadow
     * @param seeThrough        whether it is visible through blocks
     * @param lineWidth         where the client wraps a long line, in pixels
     * @param lineSpacing       blocks between one line and the next
     * @param textOpacity       0 to 255
     * @param defaultBackground whether the client's own background is used
     * @param backgroundColor   background as {@code #AARRGGBB}, so
     *                          {@code '#00000000'} is fully transparent
     * @param brightness        fixed light level from 0 to 15, or -1 to use the
     *                          light at its location
     * @param glowing           whether it glows
     * @param distanceScale     how it grows with the viewer's distance so it
     *                          stays readable far away; off by default. Since
     *                          1.301.0
     */
    public record Properties(
            @Comment("How it turns towards the player: CENTER, VERTICAL, HORIZONTAL or FIXED.")
            String billboard,

            @Comment("Text alignment: CENTER, LEFT or RIGHT.")
            String alignment,

            @Comment("Size along each axis.")
            double scaleX,

            @Comment("Size along each axis.")
            double scaleY,

            @Comment("Size along each axis.")
            double scaleZ,

            @Comment("Whether the text is drawn with a shadow.")
            boolean shadow,

            @Comment("Whether it can be seen through blocks.")
            boolean seeThrough,

            @Comment("Where the client wraps a long line, in pixels.")
            int lineWidth,

            @Comment("Blocks between one line and the next.")
            double lineSpacing,

            @Comment("Text opacity, from 0 to 255.")
            int textOpacity,

            @Comment("Use the client's own text background instead of the colour below.")
            boolean defaultBackground,

            @Comment("Background colour as #AARRGGBB. '#00000000' is fully transparent.")
            String backgroundColor,

            @Comment("Fixed light level from 0 to 15, or -1 to use the light where it stands.")
            int brightness,

            @Comment("Whether it glows.")
            boolean glowing,

            @Comment("Grow it with the viewer's distance, so it stays readable from far away.")
            @Comment("A line can follow its own curve with <autoscale:start,end,max>.")
            DistanceScale distanceScale) {

        /** The ExyliaCommons defaults, so an unwritten section looks the same. */
        public Properties() {
            this("CENTER", "CENTER", 1.0, 1.0, 1.0, true, false, 200, 0.25, 255,
                    false, "#00000000", -1, false);
        }

        /**
         * Properties as written before {@code distance-scale} existed, which
         * leaves it off.
         */
        public Properties(String billboard, String alignment, double scaleX, double scaleY,
                          double scaleZ, boolean shadow, boolean seeThrough, int lineWidth,
                          double lineSpacing, int textOpacity, boolean defaultBackground,
                          String backgroundColor, int brightness, boolean glowing) {
            this(billboard, alignment, scaleX, scaleY, scaleZ, shadow, seeThrough, lineWidth,
                    lineSpacing, textOpacity, defaultBackground, backgroundColor, brightness,
                    glowing, new DistanceScale());
        }

        /**
         * These properties with another distance curve.
         *
         * @param distanceScale the curve
         * @return the changed copy
         * @since 1.301.0
         */
        public Properties withDistanceScale(DistanceScale distanceScale) {
            return new Properties(billboard, alignment, scaleX, scaleY, scaleZ, shadow, seeThrough,
                    lineWidth, lineSpacing, textOpacity, defaultBackground, backgroundColor,
                    brightness, glowing, distanceScale);
        }

        public Properties {
            if (distanceScale == null) {
                distanceScale = new DistanceScale();
            }
            if (billboard == null) {
                billboard = "CENTER";
            }
            if (alignment == null) {
                alignment = "CENTER";
            }
            if (backgroundColor == null) {
                backgroundColor = "#00000000";
            }
            textOpacity = Math.clamp(textOpacity, 0, 255);
            if (lineWidth < 1) {
                lineWidth = 200;
            }
        }
    }

    /**
     * How a hologram grows with the distance of the player looking at it, so
     * it stays readable from far away.
     *
     * <p>Up to {@code start} blocks away it is drawn at {@code min} times its
     * size, from {@code end} on at {@code max}, and in between the size grows
     * in a straight line, which is what keeps its apparent size steady. Each
     * viewer is shown their own size, re-sent only when it moves by a step.
     *
     * <pre>
     * properties:
     *   distance-scale:
     *     enabled: true
     *     start: 8.0
     *     end: 32.0
     *     min: 1.0
     *     max: 2.5
     * </pre>
     *
     * @param enabled whether the whole hologram follows the curve; a line
     *                tagged {@code <autoscale>} follows it either way
     * @param start   blocks away where it starts growing
     * @param end     blocks away where it reaches {@code max}
     * @param min     how big it is up to {@code start}, 0.1 to 10
     * @param max     how big it is from {@code end} on, 0.1 to 10
     * @since 1.301.0
     */
    public record DistanceScale(
            @Comment("Whether the whole hologram grows with the viewer's distance.")
            boolean enabled,

            @Comment("Blocks away where it starts growing.")
            double start,

            @Comment("Blocks away where it reaches its largest size.")
            double end,

            @Comment("How big it is up close, until 'start'. 1 is its own size.")
            double min,

            @Comment("How big it is from 'end' on.")
            double max) {

        /** Off, with a curve that doubles and a half by 32 blocks for the lines that ask for it. */
        public DistanceScale() {
            this(false, 8, 32, 1, 2.5);
        }

        public DistanceScale {
            start = Math.max(0, start);
            if (end <= start) {
                end = start + 1;
            }
            min = Math.clamp(min, 0.1, 10);
            max = Math.clamp(max, 0.1, 10);
        }

        /**
         * How big it is drawn for a viewer this far away.
         *
         * @param distance blocks between the viewer and the hologram
         * @return the factor its size is multiplied by
         */
        public double scaleAt(double distance) {
            double along = Math.clamp((distance - start) / (end - start), 0, 1);
            return min + (max - min) * along;
        }
    }

    /**
     * What clicking a hologram does, as written in config.
     *
     * <p>A hologram with click lines gets an invisible box over its lines; a
     * left or right click on it runs the lines that answer that click, in
     * order, for the player who clicked. A plugin can react in code too, with
     * {@link Hologram#onClick}.
     *
     * <pre>
     * click:
     *   commands:
     *     - 'arena join'
     *     - 'console: playsound ui.button.click master %player_name%'
     *     - 'shift_right: [SERVER] lobby'
     *   width: 1.5
     *   cooldown: 500ms
     * </pre>
     *
     * @param commands what runs, as interaction lines (since 1.291.0): every
     *                 command line keeps its meaning ({@code console:},
     *                 {@code player:}, a bare line runs as the player), and a
     *                 line may also start with a click filter such as
     *                 {@code left:} or be a token such as {@code [MESSAGE]};
     *                 see {@link net.exylia.lib.interaction.Interactions}
     * @param width    how wide the box is, in blocks; it is as tall as the
     *                 lines
     * @param cooldown   milliseconds before the same player's next click counts
     * @param hoverScale how much bigger the hologram grows for a player looking
     *                   at it, so they know it can be clicked; 1 turns it off.
     *                   Since 1.288.0
     * @param style      {@code BUTTON} draws a panel behind what can be
     *                   clicked, lit while a player looks at it and flashing
     *                   when pressed; {@code NONE} draws nothing. Only holograms
     *                   with click lines or buttons get one. Since 1.301.0
     * @param color      the panel: a palette name such as {@code {primary}}, or
     *                   {@code #RRGGBB} / {@code #AARRGGBB}. Since 1.301.0
     * @param hoverColor the panel while a player looks at it. Since 1.301.0
     * @param sound      a sound played to the player on every click, as a
     *                   {@code [SOUND]} line takes it
     *                   ({@code UI_BUTTON_CLICK;0.6;1.4}); empty for none. Since
     *                   1.301.0
     * @param buttons    click lines by button name: a line tagged
     *                   {@code <button:name>} runs these instead of
     *                   {@code commands} when that line is clicked. Since 1.301.0
     * @since 1.287.0
     */
    public record Click(
            @Comment("What clicking it does, one line each, in order. Leave empty for a hologram nobody can click.")
            @Comment("Commands: 'console: ...' runs from the console, a bare line runs as the player.")
            @Comment("Tokens: [MESSAGE] [SOUND] [TITLE] [ACTION_BAR] [SERVER] lobby, [TELEPORT] world;x;y;z,")
            @Comment("  [ACTION] namespace:id, [DELAY] 1s and every other effect token.")
            @Comment("Start a line with left:, right:, shift_left:, shift_right: or shift: to limit it to that click.")
            List<String> commands,

            @Comment("How wide the clickable box is, in blocks. It is as tall as the lines.")
            double width,

            @Comment("How long before the same player's next click counts. Reads 500ms, 1s.")
            @Time(Time.Unit.MILLIS) long cooldown,

            @Comment("How much bigger it grows while a player looks at it. 1 turns it off.")
            double hoverScale,

            @Comment("BUTTON draws what can be clicked as a button, lit on hover and flashing on a click:")
            @Comment("each <button:...> line gets a background of its own, sized to its text; a hologram")
            @Comment("without button lines whose whole body is clickable gets one slim panel behind its lines.")
            @Comment("NONE draws nothing. Holograms without click lines or buttons never get a panel.")
            String style,

            @Comment("The colour at rest: a palette name like {primary}, or #RRGGBB / #AARRGGBB.")
            @Comment("Without alpha a button rests at 35% and a panel at 25%.")
            String color,

            @Comment("The colour while a player aims at it. Without alpha a button lights at 80%, a panel at 45%.")
            String hoverColor,

            @Comment("A sound for every click, as [SOUND] takes it: UI_BUTTON_CLICK;0.6;1.4. Empty for none.")
            String sound,

            @Comment("Click lines for single lines: a line starting with <button:join> runs 'join' here")
            @Comment("instead of the commands above, and lights up on its own.")
            Map<String, List<String>> buttons,

            @Comment("How far away, in blocks, a player can aim at it: it lights up and takes clicks.")
            @Comment("A left-click, or a right-click holding an item, reaches this far. A right-click")
            @Comment("with an empty hand only reaches as far as the game lets a hand touch an entity")
            @Comment("(3 blocks in survival), because the client sends nothing for it further away.")
            double range) {

        /** How far a player can aim at a hologram unless {@link #range} says otherwise. */
        public static final double DEFAULT_RANGE = 8.0;

        /** {@link #style} drawing a panel. */
        public static final String STYLE_BUTTON = "BUTTON";
        /** {@link #style} drawing nothing. */
        public static final String STYLE_NONE = "NONE";

        /** Not clickable. */
        public Click() {
            this(List.of(), 1.5, 500, 1.08);
        }

        /**
         * A click section as written before 1.301.0 added the button look and
         * buttons: a panel in the palette's colours, no sound, no buttons.
         *
         * @param commands   what runs
         * @param width      how wide the box is
         * @param cooldown   milliseconds between two clicks of the same player
         * @param hoverScale how much bigger it grows while looked at
         */
        public Click(List<String> commands, double width, long cooldown, double hoverScale) {
            this(commands, width, cooldown, hoverScale, STYLE_BUTTON, "{primary}", "{secondary}",
                    "", Map.of());
        }

        /**
         * A click section as written before 1.303.0 added {@code range}: it
         * reaches {@value #DEFAULT_RANGE} blocks.
         *
         * @param commands   what runs
         * @param width      how wide the box is
         * @param cooldown   milliseconds between two clicks of the same player
         * @param hoverScale how much bigger it grows while looked at
         * @param style      {@code BUTTON} or {@code NONE}
         * @param color      the panel colour at rest
         * @param hoverColor the panel colour while looked at
         * @param sound      a sound for every click
         * @param buttons    click lines by button name
         */
        public Click(List<String> commands, double width, long cooldown, double hoverScale,
                     String style, String color, String hoverColor, String sound,
                     Map<String, List<String>> buttons) {
            this(commands, width, cooldown, hoverScale, style, color, hoverColor, sound, buttons,
                    DEFAULT_RANGE);
        }

        /**
         * This section with another reach.
         *
         * @param range how far away it can be aimed at, in blocks
         * @return the changed copy
         * @since 1.303.0
         */
        public Click withRange(double range) {
            return new Click(commands, width, cooldown, hoverScale, style, color, hoverColor, sound,
                    buttons, range);
        }

        /**
         * Whether the button look is on.
         *
         * @return {@code false} for {@code NONE}, {@code OFF} or {@code FALSE}
         * @since 1.301.0
         */
        public boolean buttonStyle() {
            return switch (style.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "NONE", "OFF", "FALSE" -> false;
                default -> true;
            };
        }

        /**
         * This section with other buttons.
         *
         * @param buttons click lines by button name
         * @return the changed copy
         * @since 1.301.0
         */
        public Click withButtons(Map<String, List<String>> buttons) {
            return new Click(commands, width, cooldown, hoverScale, style, color, hoverColor, sound,
                    buttons, range);
        }

        /**
         * A click section as written before {@code hover-scale} existed.
         *
         * @param commands what runs
         * @param width    how wide the box is
         * @param cooldown milliseconds between two clicks of the same player
         */
        public Click(List<String> commands, double width, long cooldown) {
            this(commands, width, cooldown, 1.08);
        }

        public Click {
            commands = commands == null ? List.of() : List.copyOf(commands);
            if (width <= 0) {
                width = 1.5;
            }
            cooldown = Math.max(0, cooldown);
            if (hoverScale <= 0) {
                hoverScale = 1;
            }
            style = style == null || style.isBlank() ? STYLE_BUTTON : style.trim();
            color = color == null || color.isBlank() ? "{primary}" : color.trim();
            hoverColor = hoverColor == null || hoverColor.isBlank() ? "{secondary}" : hoverColor.trim();
            sound = sound == null ? "" : sound.trim();
            if (!(range > 0)) {
                range = DEFAULT_RANGE;
            }
            range = Math.min(range, 32);
            if (buttons == null || buttons.isEmpty()) {
                buttons = Map.of();
            } else {
                Map<String, List<String>> kept = new java.util.LinkedHashMap<>();
                buttons.forEach((name, lines) -> {
                    if (name != null) {
                        kept.put(name, lines == null ? List.of() : List.copyOf(lines));
                    }
                });
                buttons = java.util.Collections.unmodifiableMap(kept);
            }
        }
    }

    /**
     * Every page, the first being {@link #lines()}.
     *
     * @return one list of lines per page, never empty
     * @since 1.297.0
     */
    public List<List<String>> allPages() {
        List<List<String>> all = new java.util.ArrayList<>(pages.size() + 1);
        all.add(lines);
        for (Page page : pages) {
            all.add(page.lines());
        }
        return all;
    }

    /**
     * One page after the first, as written in config.
     *
     * <pre>
     * pages:
     *   - lines:
     *       - '{primary}&amp;lRULES'
     *       - '{letters}No griefing.'
     *     click:
     *       - '[PAGE] next'
     * </pre>
     *
     * @param lines the page's lines, top first, with the same tags as
     *              {@link HologramConfig#lines()}
     * @param click what clicking the hologram does while a player is on this
     *              page, as interaction lines; {@code [PAGE] next},
     *              {@code [PAGE] previous} and {@code [PAGE] <n>} turn it
     * @since 1.297.0
     */
    public record Page(
            @Comment("The page's lines, top first, with the same tags as the first page.")
            List<String> lines,

            @Comment("What clicking does on this page. '[PAGE] next', '[PAGE] previous' or '[PAGE] 2' turns it.")
            List<String> click) {

        /** An empty page. */
        public Page() {
            this(List.of(), List.of());
        }

        /**
         * A page nobody clicks.
         *
         * @param lines the page's lines
         */
        public Page(List<String> lines) {
            this(lines, List.of());
        }

        public Page {
            lines = lines == null ? List.of() : List.copyOf(lines);
            click = click == null ? List.of() : List.copyOf(click);
        }
    }

    /**
     * How a hologram refreshes, as written in config.
     *
     * @param updateInterval ticks between refreshes; 20 ticks are one second.
     *                       Kept in ticks for ExyliaCommons file compatibility
     * @param autoUpdate     whether it refreshes on its own. A hologram whose
     *                       lines have no placeholders never schedules anything
     *                       either way
     */
    public record Refresh(
            @Comment("Ticks between refreshes. 20 ticks are one second.")
            @Comment("Reads a written duration too: 1s, 500ms, 2m.")
            @Time(Time.Unit.TICKS) long updateInterval,

            @Comment("Whether the lines refresh on their own.")
            @Comment("Lines without placeholders are drawn once and never refresh regardless.")
            boolean autoUpdate) {

        /** The ExyliaCommons defaults: refresh every second. */
        public Refresh() {
            this(20, true);
        }

        public Refresh {
            if (updateInterval < 1) {
                updateInterval = 1;
            }
        }
    }
}
