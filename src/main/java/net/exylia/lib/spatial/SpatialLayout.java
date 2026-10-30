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
        @Comment("eyes, in blocks. lift: how far above eye height its middle is.")
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
        @Comment("the plugin fills it from. footer: an optional button along the bottom.")
        Map<String, Panel> panels,
        @Comment("The piece in the middle, on a slowly turning pedestal. turn-speed in degrees a second.")
        @Comment("vanish-on-play: an effect's mannequin disappears while the effect plays, so a kill reads as a death.")
        @Comment("effect-scale: how large an effect plays, as a miniature on the pedestal: its mannequin, every offset,")
        @Comment("movement, body and particle spread that many times its written size. 0.35 fits a typical kill effect")
        @Comment("between the carousel and the header. skins: whose skin an effect's mannequin and its body wear, the")
        @Comment("first source that has one: others (another online player, varied per play), viewer, defaults")
        @Comment("(vanilla's Steve, Alex, Ari, Efe, Kai, Makena, Noor, Sunny and Zuri, slim and wide).")
        Preview preview,
        @Comment("The row of plinths along the bottom: how many on a page, how far apart, the gap left in the")
        @Comment("middle for the preview, and how far the outermost come towards the viewer. Leave source empty")
        @Comment("for none. The mouse wheel pages it.")
        Carousel carousel) {

    /** A wardrobe: a header, a list on the left, a grid on the right, a caption, a preview and a carousel. */
    public SpatialLayout() {
        this("{primary}&lWARDROBE", new Anchor(), true, true, new SpatialTheme(), defaultPanels(), new Preview(),
                new Carousel());
    }

    public SpatialLayout {
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
        return new SpatialLayout(title, anchor, hideOthers, freeze, theme, next, preview, carousel);
    }

    public SpatialLayout withPreview(Preview preview) {
        return new SpatialLayout(title, anchor, hideOthers, freeze, theme, panels, preview, carousel);
    }

    public SpatialLayout withCarousel(Carousel carousel) {
        return new SpatialLayout(title, anchor, hideOthers, freeze, theme, panels, preview, carousel);
    }

    /** Where the menu stands. */
    public record Anchor(Mode mode, double distance, double lift) {
        public enum Mode { FRONT, PLACED }

        public Anchor() {
            this(Mode.FRONT, 3.4, 0.0);
        }
    }

    /** One panel: a sheet of glass with a title, what it shows, and an optional footer button. */
    public record Panel(List<Double> at, double turn, List<Double> size, String title, String hint,
                        Content content, Footer footer) {

        public Panel() {
            this(List.of(0.0, 0.0, 0.0), 0, List.of(1.4, 1.0), "", "", new Content(), new Footer());
        }

        public Panel {
            at = point(at);
            size = size == null || size.size() < 2 ? List.of(1.4, 1.0) : numbers(size, 2);
            title = title == null ? "" : title;
            hint = hint == null ? "" : hint;
            content = content == null ? new Content() : content;
            footer = footer == null ? new Footer() : footer;
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
            return new Panel(List.of(across, up, forward), turn, List.of(width, height), title, hint, content, footer);
        }
    }

    /**
     * What a panel shows. {@code BUTTONS} (since 1.416.0) stacks {@code buttons} top to bottom in the
     * panel's content area; a button whose label reads blank once filled is left out and the rest close
     * up, so a plugin hides one (REVERT with nothing to revert) by giving its placeholder no words.
     */
    public record Content(Type type, String source, int columns, int rows, List<String> lines, String label,
                          String action, List<Footer> buttons) {

        public enum Type { NONE, TEXT, LIST, GRID, BUTTON, BUTTONS }

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
     */
    public record Preview(List<Double> at, double turnSpeed, boolean pedestal, double scale, boolean vanishOnPlay,
                          double effectScale, List<String> skins) {

        /** Whose skin an effect preview wears when the file does not say. */
        public static final List<String> SKINS = List.of("others", "defaults");

        /** What an effect preview plays at when the file does not say: a kill fits between the carousel and the header. */
        public static final double EFFECT_SCALE = 0.35;

        public Preview() {
            // Low and small enough that a body's head stays under the focus text, level with it, not nearer.
            this(List.of(0.0, -0.8, 0.2), 12, true, 0.8, true, EFFECT_SCALE, SKINS);
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
        }

        /** {@link #skins()} as the policy that picks a body. */
        public net.exylia.lib.npc.PreviewSkins skinPolicy() {
            return net.exylia.lib.npc.PreviewSkins.of(skins);
        }

        public Preview moved(double across, double up, double forward) {
            return new Preview(List.of(across, up, forward), turnSpeed, pedestal, scale, vanishOnPlay, effectScale, skins);
        }
    }

    /** The row of plinths along the bottom. */
    public record Carousel(String source, List<Double> at, int slots, double spacing, double centreGap, double curve) {
        public Carousel() {
            this("items", List.of(0.0, -1.05, 0.8), 6, 0.62, 1.3, 0.15);
        }

        public Carousel {
            source = source == null ? "" : source;
            at = point(at);
            slots = Math.max(1, slots);
        }

        public boolean present() {
            return !source.isBlank();
        }

        public Carousel moved(double across, double up, double forward, double spacing, double curve) {
            return new Carousel(source, List.of(across, up, forward), slots, spacing, centreGap, curve);
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
