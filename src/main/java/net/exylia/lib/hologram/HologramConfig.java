package net.exylia.lib.hologram;

import net.exylia.lib.config.Comment;

import java.util.List;

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
 * @since 1.6.0
 */
public record HologramConfig(
        @Comment("Set to false to keep the section without showing the hologram.")
        boolean enabled,

        @Comment("TEXT, ITEM or BLOCK.")
        Kind type,

        @Comment("The lines, top first. Supports colours like {primary} and placeholders.")
        @Comment("Only used by TEXT holograms.")
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
        Refresh config) {

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
                new Properties(), new Refresh());
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
            boolean glowing) {

        /** The ExyliaCommons defaults, so an unwritten section looks the same. */
        public Properties() {
            this("CENTER", "CENTER", 1.0, 1.0, 1.0, true, false, 200, 0.25, 255,
                    false, "#00000000", -1, false);
        }

        public Properties {
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
            long updateInterval,

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
