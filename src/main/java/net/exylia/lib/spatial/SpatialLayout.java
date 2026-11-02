package net.exylia.lib.spatial;

import net.exylia.lib.config.Comment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where everything of one spatial menu stands, and how it looks: the file
 * {@code plugins/<Plugin>/spatial/<menu>.yml}. A plugin ships its own from the jar
 * ({@code spatial/<menu>.yml}); keys missing from the server's copy are added, its values are never
 * overwritten, and the web editor finds it by itself.
 *
 * <p>Coordinates are in blocks on the board, a plane standing {@code anchor.distance} ahead of the
 * eyes: {@code across} to the viewer's right, {@code up}, and {@code forward} towards the viewer.
 * Points are written {@code [across, up, forward]} and sizes {@code [width, height]}; {@code turn} is
 * how many degrees a piece is turned towards the viewer round its own vertical axis (positive for one
 * on the left, negative on the right).
 *
 * @since 1.406.0
 */
public record SpatialLayout(
        @Comment("The menu's name: the hologram over a placed spot reads it as %title%.")
        String title,
        @Comment("Where the menu stands. FRONT: in front of the player, wherever they are. PLACED: at the nearest")
        @Comment("spot set down for it (falls back to FRONT without one). distance: the board's distance from the")
        @Comment("eyes, in blocks. lift: how far above eye height its middle is. clear-view: the blocks standing")
        @Comment("between the viewer and the board (walls, trunks, leaves) are shown to them as air while it is")
        @Comment("open, the ground they stand on kept; off brings the board nearer to a wall instead.")
        Anchor anchor,
        @Comment("Hide other players from the viewer, and the viewer from them, while it is open.")
        boolean hideOthers,
        @Comment("Hold the viewer still while it is open. They can still look round and sneak to leave.")
        boolean freeze,
        @Comment("The look. Every key has the library's default; change only what you want different.")
        SpatialTheme theme,
        @Comment("The panels, by a name of your choosing. type: NONE, TEXT (lines), LIST (icon, name and")
        @Comment("subtitle rows), GRID (columns x rows of icons), BUTTON (label and action) or BUTTONS (a column of")
        @Comment("buttons, each a label and an action; one whose label reads blank is left out). source: the list")
        @Comment("the plugin fills it from. footer: an optional button along the bottom. footers: several buttons")
        @Comment("side by side along the bottom, each a label and an action (after footer, when both are given).")
        Map<String, Panel> panels,
        @Comment("The piece in the middle, on a slowly turning pedestal. turn-speed in degrees a second.")
        @Comment("vanish-on-play: an effect's mannequin disappears while the effect plays, so a kill reads as a death.")
        @Comment("effect-scale: how large an effect plays, as a miniature on the pedestal: its mannequin, every offset,")
        @Comment("movement, body and particle spread that many times its written size. 0.35 fits a typical kill effect")
        @Comment("between the carousel and the header. skins: whose skin an effect's mannequin and its body wear, the")
        @Comment("first source that has one: others (another online player, varied per play), viewer, defaults")
        @Comment("(vanilla's Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny and Zuri, slim and wide).")
        @Comment("facing: which way a body faces when it stands: FRONT, SIDE, BACK, OTHER_SIDE or degrees from the front.")
        @Comment("turn-controls: the viewer turns the body: a click on it turns it to the next side, the wheel over it a")
        @Comment("little, and the two arrows beside the pedestal either way; it holds that side for turn-pause seconds")
        @Comment("before turning on its own again. turn-hint: the short line under the pedestal saying so.")
        @Comment("effect-facing: the side an effect is seen from: AUTO (watched once, then turned so its motion runs across")
        @Comment("the view, never showing the victim's back), FRONT, SIDE or degrees. zone: [width, height] above the")
        @Comment("pedestal an effect must play inside; it is made smaller (never larger than effect-scale) to fit. 0: none.")
        Preview preview,
        @Comment("The row of plinths along the bottom: how many on a page, how far apart, the gap left in the")
        @Comment("middle for the preview, and how far the outermost come towards the viewer. Leave source empty")
        @Comment("for none. The mouse wheel pages it. rows: how many rows a page holds, row-gap blocks apart.")
        @Comment("style: PLINTHS (each item on a plinth, its name under it) or SHELF (icons on small tiles, no")
        @Comment("names: a card beside the preview describes what is looked at), with page arrows at the row ends.")
        Carousel carousel,
        @Comment("A short guided tour shown the first time a player opens this menu (stored per player), skippable")
        @Comment("by sneaking, never shown again unless a button with action 'intro' asks for it. Each step outlines")
        @Comment("one piece (target: a panel's name, carousel or preview) and writes its text at 'at'. Empty: no tour.")
        Intro intro,
        @Comment("The layout's version. A plugin update with a higher one replaces this file when it was never")
        @Comment("edited, and offers its new layout in /exylialib updates when it was. Do not edit.")
        int menuVersion,
        @Comment("A soft, translucent dark backdrop behind the whole board, so its words read on any sky or ground:")
        @Comment("size [width, height] at 'at', its edges fading out over 'feather' blocks in 'rings' steps. Size 0: none.")
        Backdrop backdrop) {

    /** A wardrobe: a header, a list on the left, a grid on the right, a caption, a preview and a carousel. */
    public SpatialLayout() {
        this("{primary}&lWARDROBE", new Anchor(), true, true, new SpatialTheme(), defaultPanels(), new Preview(),
                new Carousel(), new Intro(), 0, new Backdrop());
    }

    /** A layout without a guided tour: the shape layouts had before 1.452.0. */
    public SpatialLayout(String title, Anchor anchor, boolean hideOthers, boolean freeze, SpatialTheme theme,
                         Map<String, Panel> panels, Preview preview, Carousel carousel) {
        this(title, anchor, hideOthers, freeze, theme, panels, preview, carousel, new Intro(), 0, new Backdrop());
    }

    /** A layout without a backdrop: the shape layouts had in 1.452.0 and 1.453.0. */
    public SpatialLayout(String title, Anchor anchor, boolean hideOthers, boolean freeze, SpatialTheme theme,
                         Map<String, Panel> panels, Preview preview, Carousel carousel, Intro intro, int menuVersion) {
        this(title, anchor, hideOthers, freeze, theme, panels, preview, carousel, intro, menuVersion, new Backdrop());
    }

    public SpatialLayout {
        backdrop = backdrop == null ? new Backdrop() : backdrop;
        intro = intro == null ? new Intro() : intro;
        panels = panels == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(panels));
    }

    private static Map<String, Panel> defaultPanels() {
        Map<String, Panel> panels = new LinkedHashMap<>();
        panels.put("header", new Panel(List.of(0.0, 1.7, 0.0), 0, List.of(4.6, 0.34), "{primary}&lWARDROBE",
                "{muted}Sneak to leave", new Content(), new Footer()));
        panels.put("left", new Panel(List.of(-2.05, 0.3, 0.45), 24, List.of(1.35, 1.55), "{secondary}&lCATEGORIES", "",
                new Content(Content.Type.LIST, "categories", 1, 4, List.of(), "", ""), new Footer()));
        panels.put("right", new Panel(List.of(2.05, 0.3, 0.45), -24, List.of(1.35, 1.55), "{secondary}&lOPTIONS", "",
                new Content(Content.Type.GRID, "options", 3, 4, List.of(), "", ""),
                new Footer("{highlight}&lAPPLY", "apply")));
        panels.put("focus", new Panel(List.of(0.0, 1.05, 0.2), 0, List.of(1.6, 0.42), "", "",
                new Content(Content.Type.TEXT, "", 1, 1, List.of("{letters}&l%choice%", "{info}%detail%"), "", ""),
                new Footer()));
        return panels;
    }

    /** The named panel, or {@code null}. */
    public Panel panel(String name) {
        return panels.get(name);
    }

    /** The same layout with one panel replaced. */
    public SpatialLayout withPanel(String name, Panel panel) {
        Map<String, Panel> next = new LinkedHashMap<>(panels);
        next.put(name, panel);
        return new SpatialLayout(title, anchor, hideOthers, freeze, theme, next, preview, carousel, intro, menuVersion, backdrop);
    }

    public SpatialLayout withPreview(Preview preview) {
        return new SpatialLayout(title, anchor, hideOthers, freeze, theme, panels, preview, carousel, intro, menuVersion, backdrop);
    }

    public SpatialLayout withCarousel(Carousel carousel) {
        return new SpatialLayout(title, anchor, hideOthers, freeze, theme, panels, preview, carousel, intro, menuVersion, backdrop);
    }

    /**
     * How far below the board's middle its lowest piece reaches, in blocks (negative): every panel's bottom, the
     * preview's pedestal, the carousel with its labels, page line and arrows. The backdrop is left out: it may fade
     * into the floor. Since 1.454.0.
     */
    public double lowest() {
        double low = 0;
        for (Panel panel : panels.values()) {
            // An icon row writes the words of the icon looked at under it.
            double under = panel.content().type() == Content.Type.ICONS ? panel.height() * 0.95 : 0;
            low = Math.min(low, panel.up() - panel.height() / 2 - under);
        }
        low = Math.min(low, preview.at().get(1) - 0.14);
        if (carousel.present()) {
            double up = carousel.at().get(1);
            if (carousel.arc()) low = Math.min(low, up - carousel.spacing() * 0.9 * 0.95 - 0.1);
            else if (carousel.shelf()) low = Math.min(low, up - (carousel.rows() - 1) * carousel.rowGap()
                    - Math.min(carousel.spacing() * 0.8, carousel.rowGap() * 0.86) / 2 - 0.42);
            else low = Math.min(low, up - 0.47);
        }
        return low;
    }

    /**
     * The lift the board is drawn at for a viewer standing on flat ground with their eyes {@code eyes} above
     * their feet: the layout's own, or more when that would put its lowest piece less than {@code margin} above
     * the floor, where the ground (kept by clear-view) would hide it. Since 1.454.0.
     */
    public double liftOverFloor(double eyes, double margin) {
        return Math.max(anchor.lift(), margin - eyes - lowest());
    }

    /**
     * A soft backdrop behind the board (since 1.454.0): a dark sheet of {@code alpha} tinted towards {@code tint},
     * {@code size} wide and tall at {@code at}, ringed by {@code rings} fainter, larger sheets fading out over
     * {@code feather} blocks, so it has no hard edge. Size 0 (the default): none.
     */
    public record Backdrop(List<Double> at, List<Double> size, String tint, int alpha, double feather, int rings) {
        public Backdrop() {
            this(List.of(0.0, 0.0, -0.3), List.of(0.0, 0.0), "{primary}", 150, 0.9, 10);
        }

        public Backdrop {
            at = point(at);
            size = size == null || size.size() < 2 ? List.of(0.0, 0.0) : numbers(size, 2);
            tint = tint == null || tint.isBlank() ? "{primary}" : tint;
            alpha = Math.clamp(alpha, 0, 255);
            feather = Math.max(0, feather);
            rings = Math.clamp(rings, 0, 32);
        }

        public boolean present() {
            return size.get(0) > 0 && size.get(1) > 0 && alpha > 0;
        }
    }

    /**
     * Where the menu stands.
     *
     * @param clearView whether the blocks in the space the menu stands in are shown to its viewer as air
     *                  while it is open (the floor kept), so no wall or trunk cuts through a panel; off,
     *                  the board is brought nearer when a block is in the way, as before. Since 1.442.0.
     */
    public record Anchor(Mode mode, double distance, double lift, boolean clearView) {
        public enum Mode { FRONT, PLACED }

        public Anchor() {
            this(Mode.FRONT, 3.4, 0.0, true);
        }

        /** @deprecated since 1.442.0; use the constructor with {@code clearView}. */
        @Deprecated
        public Anchor(Mode mode, double distance, double lift) {
            this(mode, distance, lift, true);
        }
    }

    /**
     * One panel: a sheet of glass with a title, what it shows, and an optional footer button. {@code footers}
     * (since 1.423.0) puts several buttons side by side along the bottom (UNDO and CLEAR under a list), after
     * {@code footer} when both are given. {@code frame: false} (since 1.452.0) draws no glass, backing or
     * accent: its words and buttons stand bare in the air (a tab row, a hint line, a column of quiet buttons).
     */
    public record Panel(List<Double> at, double turn, List<Double> size, String title, String hint,
                        Content content, Footer footer, List<Footer> footers, boolean frame) {

        public Panel() {
            this(List.of(0.0, 0.0, 0.0), 0, List.of(1.4, 1.0), "", "", new Content(), new Footer(), List.of(), true);
        }

        /** A framed panel with a row of footers: the shape layouts had before 1.452.0. */
        public Panel(List<Double> at, double turn, List<Double> size, String title, String hint,
                     Content content, Footer footer, List<Footer> footers) {
            this(at, turn, size, title, hint, content, footer, footers, true);
        }

        /** A panel with one footer at most: the shape layouts had before 1.423.0. */
        public Panel(List<Double> at, double turn, List<Double> size, String title, String hint,
                     Content content, Footer footer) {
            this(at, turn, size, title, hint, content, footer, List.of());
        }

        public Panel {
            at = point(at);
            size = size == null || size.size() < 2 ? List.of(1.4, 1.0) : numbers(size, 2);
            title = title == null ? "" : title;
            hint = hint == null ? "" : hint;
            content = content == null ? new Content() : content;
            footer = footer == null ? new Footer() : footer;
            footers = footers == null ? List.of() : List.copyOf(footers);
        }

        /**
         * Every button along the bottom, left to right: {@code footer} first when it has a label, then
         * {@code footers}. Empty for a panel with none.
         *
         * @since 1.423.0
         */
        public List<Footer> bottomRow() {
            if (footers.isEmpty()) return footer.present() ? List.of(footer) : List.of();
            List<Footer> row = new java.util.ArrayList<>(footers.size() + 1);
            if (footer.present()) row.add(footer);
            row.addAll(footers);
            return row;
        }

        public double across() {
            return at.get(0);
        }

        public double up() {
            return at.get(1);
        }

        public double forward() {
            return at.get(2);
        }

        public double width() {
            return size.get(0);
        }

        public double height() {
            return size.get(1);
        }

        public Panel moved(double across, double up, double forward, double turn, double width, double height) {
            return new Panel(List.of(across, up, forward), turn, List.of(width, height), title, hint, content, footer,
                    footers, frame);
        }
    }

    /**
     * What a panel shows. {@code BUTTONS} (since 1.416.0) stacks {@code buttons} top to bottom in the
     * panel's content area; a button whose label reads blank once filled is left out and the rest close
     * up, so a plugin hides one (REVERT with nothing to revert) by giving its placeholder no words.
     */
    public record Content(Type type, String source, int columns, int rows, List<String> lines, String label,
                          String action, List<Footer> buttons) {

        /**
         * {@code TABS} (since 1.452.0): one row of tabs across the panel, one per entry of {@code source}, the
         * chosen one lit; a catalogue fills {@code catalog_tabs} with its filter steps and hides the row when it
         * has none. {@code ACTION} (since 1.452.0): the one primary button, filling the panel; {@code label}
         * defaults to {@code %catalog_action%} and {@code action} to {@code save}, lit while there is
         * something to do and dimmed (it still answers) while there is not. {@code ICONS} (since 1.453.0): a row of
         * glyphs, one per entry of {@code buttons}, no plates; a button's label is {@code "<glyph>|<words>"} or
         * words whose first glyph is the icon ({@code ☆ FAVOURITE}); the words show only under the crosshair, in
         * the hint line ({@code %catalog_hint%}). One whose words read blank is left out.
         */
        public enum Type { NONE, TEXT, LIST, GRID, BUTTON, BUTTONS, TABS, ACTION, ICONS }

        public Content() {
            this(Type.NONE, "", 1, 1, List.of(), "", "", List.of());
        }

        /** Every content type but {@code BUTTONS}: the shape layouts had before 1.416.0. */
        public Content(Type type, String source, int columns, int rows, List<String> lines, String label,
                       String action) {
            this(type, source, columns, rows, lines, label, action, List.of());
        }

        public Content {
            type = type == null ? Type.NONE : type;
            source = source == null ? "" : source;
            columns = Math.max(1, columns);
            rows = Math.max(1, rows);
            lines = lines == null ? List.of() : List.copyOf(lines);
            label = label == null ? "" : label;
            action = action == null ? "" : action;
            buttons = buttons == null ? List.of() : List.copyOf(buttons);
        }
    }

    public record Footer(String label, String action) {
        public Footer() {
            this("", "");
        }

        public Footer {
            label = label == null ? "" : label;
            action = action == null ? "" : action;
        }

        public boolean present() {
            return !label.isBlank();
        }
    }

    /**
     * The piece in the middle.
     *
     * @param vanishOnPlay whether an effect preview's mannequin disappears while the effect plays (its
     *                     length, or 3 s for an endless or very long one), so a kill effect reads as a
     *                     death; it is back before the effect plays again. Since 1.408.0.
     * @param effectScale  how large an effect preview plays, its mannequin included: the whole effect
     *                     as a miniature standing on the pedestal, every offset, movement, body and
     *                     spread that many times its written size ({@link
     *                     net.exylia.lib.util.sequence.SequenceTarget#scale}). Since 1.413.0.
     * @param skins        whose skin an effect preview's mannequin, its ragdoll and its {@code [NPC] {victim}}
     *                     wear when the plugin names nobody: {@code others}, {@code viewer}, {@code defaults},
     *                     the first that has a body ({@link net.exylia.lib.npc.PreviewSkins}). Since 1.416.0.
     * @param facing       which way a body on the pedestal faces when it stands: {@code FRONT} (towards the
     *                     viewer), {@code SIDE}, {@code BACK}, {@code OTHER_SIDE}, or a number of degrees turned
     *                     from the front. Since 1.442.0.
     * @param turnControls whether the viewer turns a body themselves: clicking it turns it to the next side
     *                     (front, side, back, other side), the wheel over it turns it a little, and two arrows
     *                     beside the pedestal turn it either way. A turn holds the side chosen, the body's own
     *                     turning stopped, for {@code turnPause} seconds. Since 1.442.0.
     * @param turnHint     the short line under the pedestal saying it can be turned, with {@code turnControls};
     *                     blank for none. Since 1.442.0.
     * @param turnPause    how long a chosen side is held before the body turns on its own again, in seconds.
     *                     Since 1.442.0.
     */
    public record Preview(List<Double> at, double turnSpeed, boolean pedestal, double scale, boolean vanishOnPlay,
                          double effectScale, List<String> skins, String facing, boolean turnControls, String turnHint,
                          double turnPause, String effectFacing, List<Double> zone) {

        /** The hint under the pedestal when the file does not say. */
        public static final String TURN_HINT = "{muted}Click the body to turn it";

        /** Whose skin an effect preview wears when the file does not say. */
        public static final List<String> SKINS = List.of("others", "defaults");

        /** What an effect preview plays at when the file does not say: a kill fits between the carousel and the header. */
        public static final double EFFECT_SCALE = 0.35;

        public Preview() {
            // Low and small enough that a body's head stays under the focus text, level with it, not nearer.
            this(List.of(0.0, -0.8, 0.2), 12, true, 0.8, true, EFFECT_SCALE, SKINS, "FRONT", false, TURN_HINT, 8,
                    "AUTO", List.of(0.0, 0.0));
        }

        /** Every key but the effect's facing and zone: the shape previews had before 1.454.0. */
        public Preview(List<Double> at, double turnSpeed, boolean pedestal, double scale, boolean vanishOnPlay,
                       double effectScale, List<String> skins, String facing, boolean turnControls, String turnHint,
                       double turnPause) {
            this(at, turnSpeed, pedestal, scale, vanishOnPlay, effectScale, skins, facing, turnControls, turnHint,
                    turnPause, "AUTO", List.of(0.0, 0.0));
        }

        /** @deprecated since 1.442.0; use the constructor with {@code facing} and the turn controls. */
        @Deprecated
        public Preview(List<Double> at, double turnSpeed, boolean pedestal, double scale, boolean vanishOnPlay,
                       double effectScale, List<String> skins) {
            this(at, turnSpeed, pedestal, scale, vanishOnPlay, effectScale, skins, "FRONT", false, TURN_HINT, 8);
        }

        /** @deprecated since 1.408.0; use the constructor with {@code vanishOnPlay} and {@code effectScale}. */
        @Deprecated
        public Preview(List<Double> at, double turnSpeed, boolean pedestal, double scale) {
            this(at, turnSpeed, pedestal, scale, true, EFFECT_SCALE, SKINS);
        }

        /** @deprecated since 1.413.0; use the constructor with {@code effectScale}. */
        @Deprecated
        public Preview(List<Double> at, double turnSpeed, boolean pedestal, double scale, boolean vanishOnPlay) {
            this(at, turnSpeed, pedestal, scale, vanishOnPlay, EFFECT_SCALE, SKINS);
        }

        /** @deprecated since 1.416.0; use the constructor with {@code skins}. */
        @Deprecated
        public Preview(List<Double> at, double turnSpeed, boolean pedestal, double scale, boolean vanishOnPlay,
                       double effectScale) {
            this(at, turnSpeed, pedestal, scale, vanishOnPlay, effectScale, SKINS);
        }

        public Preview {
            at = point(at);
            scale = scale <= 0 ? 1 : scale;
            effectScale = effectScale > 0 && effectScale <= 16 ? effectScale : EFFECT_SCALE;
            skins = skins == null || skins.isEmpty() ? SKINS : List.copyOf(skins);
            facing = facing == null || facing.isBlank() ? "FRONT" : facing;
            turnHint = turnHint == null ? "" : turnHint;
            turnPause = turnPause < 0 ? 8 : turnPause;
            effectFacing = effectFacing == null || effectFacing.isBlank() ? "AUTO" : effectFacing;
            zone = zone == null || zone.size() < 2 ? List.of(0.0, 0.0) : numbers(zone, 2);
        }

        /**
         * A facing as degrees turned from the front: {@code FRONT} 0, {@code SIDE} 90, {@code BACK} 180,
         * {@code OTHER_SIDE} 270, a number as written, {@code NaN} for {@code AUTO}; anything else the front.
         *
         * @since 1.454.0
         */
        public static double degreesOf(String facing) {
            String side = facing == null ? "" : facing.strip().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (side.equals("AUTO")) return Double.NaN;
            return switch (side) {
                case "FRONT", "" -> 0;
                case "SIDE", "LEFT", "LEFT_SIDE" -> 90;
                case "BACK" -> 180;
                case "OTHER_SIDE", "RIGHT", "RIGHT_SIDE" -> 270;
                default -> {
                    try {
                        yield Double.parseDouble(side);
                    } catch (NumberFormatException notANumber) {
                        yield 0;
                    }
                }
            };
        }

        /**
         * {@link #facing()} in degrees turned from the front: {@code FRONT} 0, {@code SIDE} 90, {@code BACK}
         * 180, {@code OTHER_SIDE} 270, a number as written; anything else reads as the front.
         */
        public double facingDegrees() {
            String side = facing.strip().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
            return switch (side) {
                case "FRONT" -> 0;
                case "SIDE", "LEFT", "LEFT_SIDE" -> 90;
                case "BACK" -> 180;
                case "OTHER_SIDE", "RIGHT", "RIGHT_SIDE" -> 270;
                default -> {
                    try {
                        yield Double.parseDouble(side);
                    } catch (NumberFormatException notANumber) {
                        yield 0;
                    }
                }
            };
        }

        /** {@link #skins()} as the policy that picks a body. */
        public net.exylia.lib.npc.PreviewSkins skinPolicy() {
            return net.exylia.lib.npc.PreviewSkins.of(skins);
        }

        public Preview moved(double across, double up, double forward) {
            return new Preview(List.of(across, up, forward), turnSpeed, pedestal, scale, vanishOnPlay, effectScale, skins,
                    facing, turnControls, turnHint, turnPause, effectFacing, zone);
        }
    }

    /**
     * The row of plinths along the bottom, or (since 1.452.0, {@code style: SHELF}) a shelf of {@code rows}
     * rows of icons on tiles, {@code rowGap} blocks apart, the top row at {@code at}. A page holds
     * {@code slots × rows} entries.
     */
    public record Carousel(String source, List<Double> at, int slots, double spacing, double centreGap, double curve,
                           int rows, double rowGap, Style style) {

        /** How a carousel draws its entries. */
        /**
         * {@code ARC} (since 1.453.0): one row of icons floating at eye level either side of the preview, bent round
         * the viewer like an orbit, each over a soft light disc, no tiles or names.
         */
        public enum Style { PLINTHS, SHELF, ARC }

        public Carousel() {
            this("items", List.of(0.0, -1.05, 0.8), 6, 0.62, 1.3, 0.15);
        }

        /** One row of plinths: the shape layouts had before 1.452.0. */
        public Carousel(String source, List<Double> at, int slots, double spacing, double centreGap, double curve) {
            this(source, at, slots, spacing, centreGap, curve, 1, 0.7, Style.PLINTHS);
        }

        public Carousel {
            source = source == null ? "" : source;
            at = point(at);
            slots = Math.max(1, slots);
            rows = Math.max(1, rows);
            rowGap = rowGap <= 0 ? 0.7 : rowGap;
            style = style == null ? Style.PLINTHS : style;
        }

        /** How many entries a page holds: every slot of every row of a shelf, a plinth row's or an arc's slots. */
        public int perPage() {
            return shelf() ? slots * rows : slots;
        }

        public boolean arc() {
            return style == Style.ARC;
        }

        public boolean shelf() {
            return style == Style.SHELF;
        }

        public boolean present() {
            return !source.isBlank();
        }

        public Carousel moved(double across, double up, double forward, double spacing, double curve) {
            return new Carousel(source, List.of(across, up, forward), slots, spacing, centreGap, curve, rows, rowGap, style);
        }
    }

    /**
     * The guided tour of a first open (since 1.452.0): {@code steps} shown one after another, a click moving
     * on, sneaking skipping the rest. Empty for none, which is what a layout written before has.
     */
    public record Intro(List<Step> steps) {
        public Intro() {
            this(List.of());
        }

        public Intro {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public boolean present() {
            return !steps.isEmpty();
        }
    }

    /**
     * One step of the tour: the piece it points at ({@code target}: a panel's name, {@code carousel} or
     * {@code preview}), outlined while it shows, and its {@code text} (several lines with {@code \n}) written
     * at {@code at} on the board, with the theme's continue line under it.
     */
    public record Step(String target, List<Double> at, String text) {
        public Step() {
            this("", List.of(0.0, 0.0, 0.0), "");
        }

        public Step {
            target = target == null ? "" : target;
            at = point(at);
            text = text == null ? "" : text;
        }
    }

    private static List<Double> point(List<Double> at) {
        return at == null ? List.of(0.0, 0.0, 0.0) : numbers(at, 3);
    }

    /** The first {@code count} values as doubles, zeros for what is missing: YAML hands {@code 0} over as an int. */
    private static List<Double> numbers(List<?> values, int count) {
        Double[] out = new Double[count];
        for (int i = 0; i < count; i++) {
            Object value = i < values.size() ? values.get(i) : null;
            out[i] = value instanceof Number number ? number.doubleValue() : 0.0;
        }
        return List.of(out);
    }
}
