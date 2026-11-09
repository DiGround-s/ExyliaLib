package net.exylia.lib.stage;

import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.PluginDisplays;
import net.exylia.lib.text.Values;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Two panels at a seated player's sides, a quarter turn away so they never stand over the board: a
 * title over a block of lines on each, the left one for how a game is played, the right one for how
 * it is won or what it pays. Since 1.362.0.
 *
 * <p>Put up on the player's thread when they are seated and taken down with {@link #close}.
 */
public final class StageGuide {

    /** One line of a panel, with the item it is about drawn left of it, or {@code null}. */
    public record Row(@Nullable ItemStack icon, @NotNull Component text) {
    }

    /**
     * Where the panels stand and how big they are drawn.
     *
     * @param distance how far from the eyes they stand, in blocks
     * @param lift     how far above eye level their middle is, in blocks; negative is below
     * @param scale    how big their text is drawn; the titles are drawn larger
     */
    public record Look(double distance, double lift, double scale) {

        public Look() {
            this(1.9, 0.05, 0.42);
        }
    }

    private static final double EYE_HEIGHT = 1.62;
    /** As long as a seat can last: the panels are taken down by {@link #close}, never by their life. */
    private static final long LIFE = 6L * 60 * 60 * 1000;
    private static final double TITLE_SCALE = 1.7;
    /** One pixel of text at scale 1, in blocks; the font's glyphs are a pixel narrower than bold ones. */
    private static final double PIXEL = 0.025;
    private static final double ICON = 1.7;
    private static final int BACKDROP = 0;
    /** How much of its half of an {@link #ahead} backing a page may cover. */
    private static final double FILL = 0.9;

    private final List<Stage> sides = new ArrayList<>(2);
    /** Whose outlines it holds off while it stands in front of their board, or {@code null}. */
    private @Nullable Player covering;

    /**
     * @param viewer   the one player who sees it
     * @param seat     where they sit, facing the board
     * @param displays the plugin's displays
     * @param theme    the plugin's stage look
     * @param look     where the panels stand
     * @param left     the left panel's title, or {@code null} for none
     * @param leftRows the left panel's lines; empty shows no left panel
     * @param right    the right panel's title, or {@code null} for none
     * @param rightRows the right panel's lines; empty shows no right panel
     */
    public StageGuide(@NotNull Player viewer, @NotNull Location seat, @NotNull PluginDisplays displays,
                      @NotNull StageTheme theme, @NotNull Look look,
                      @Nullable Component left, @NotNull List<Row> leftRows,
                      @Nullable Component right, @NotNull List<Row> rightRows) {
        if (!leftRows.isEmpty()) page(side(viewer, seat, displays, theme, look, -90), look, left, leftRows);
        if (!rightRows.isEmpty()) page(side(viewer, seat, displays, theme, look, 90), look, right, rightRows);
    }

    private StageGuide() {
    }

    /**
     * The same two pages side by side straight ahead, flat towards the seat, on a dark backing: rules brought up on
     * demand in front of the board, instead of standing at the player's sides. While it stands, the outlines of
     * the player's other stages are {@linkplain Stage#outlines held off} (1.550.0), since they would draw through it.
     *
     * @param look    where they stand; pass a distance nearer than what they should cover
     * @param width   the whole backing across, in blocks
     * @param height  the whole backing down, in blocks
     * @since 1.535.0
     */
    public static @NotNull StageGuide ahead(@NotNull Player viewer, @NotNull Location seat, @NotNull PluginDisplays displays,
                                            @NotNull StageTheme theme, @NotNull Look look, double width, double height,
                                            @Nullable Component left, @NotNull List<Row> leftRows,
                                            @Nullable Component right, @NotNull List<Row> rightRows) {
        StageGuide guide = new StageGuide();
        // Glow outlines draw through everything: the board's would cross the page.
        Stage.outlines(viewer, false);
        guide.covering = viewer;
        Stage back = guide.onWall(viewer, seat, displays, theme, look, 0);
        back.block(org.bukkit.Material.BLACK_CONCRETE.createBlockData(), back.plane().point(0, 0, -0.02), width, height, 0.02);
        boolean both = !leftRows.isEmpty() && !rightRows.isEmpty();
        double shift = both ? width / 4 : 0;
        // Both halves at one size, the largest at which each fits its half of the backing (1.550.0): long lines
        // otherwise ran into the other half and off the backing.
        double column = (both ? width / 2 : width) * FILL;
        double scale = Math.min(look.scale(), Math.min(fit(left, leftRows, column, height * FILL),
                fit(right, rightRows, column, height * FILL)));
        Look fitted = new Look(look.distance(), look.lift(), scale);
        if (!leftRows.isEmpty()) page(guide.onWall(viewer, seat, displays, theme, look, -shift), fitted, left, leftRows);
        if (!rightRows.isEmpty()) page(guide.onWall(viewer, seat, displays, theme, look, shift), fitted, right, rightRows);
        return guide;
    }

    private Stage onWall(Player viewer, Location seat, PluginDisplays displays, StageTheme theme, Look look, double across) {
        double yaw = Math.toRadians(seat.getYaw());
        Location at = seat.clone().add(Math.cos(yaw) * -across, 0, Math.sin(yaw) * -across);
        at.setPitch(0);
        Stage view = new Stage(viewer, StagePlane.inFrontOf(at, EYE_HEIGHT, look.distance(), look.lift(), 1, 1, 1, 1),
                displays, LIFE, theme);
        sides.add(view);
        return view;
    }

    /**
     * The largest text scale at which a page (its title over its rows, laid out by {@link #page}) fits a box
     * {@code width} by {@code height} blocks centred on it; unbounded for no rows.
     */
    static double fit(@Nullable Component title, List<Row> rows, double width, double height) {
        if (rows.isEmpty()) return Double.MAX_VALUE;
        boolean icons = rows.stream().anyMatch(row -> row.icon() != null);
        double widest = 0;
        for (Row row : rows) widest = Math.max(widest, width(row.text(), false));
        // An item stands left of its line, and the block is centred: count its room on both sides.
        double across = widest + (icons ? 2 * Stage.TEXT_HEIGHT * (ICON + 0.4) : 0);
        if (title != null) across = Math.max(across, width(title, false) * TITLE_SCALE);
        int lines = (rows.size() - 1) * (icons ? 2 : 1) + 1;
        // Rows centred on the middle, the title half a line over them: the top half is the taller.
        double up = Stage.TEXT_HEIGHT * (lines / 2.0 + (title != null ? 0.5 + TITLE_SCALE : 0));
        return Math.min(across > 0 ? width / across : Double.MAX_VALUE, height / 2 / up);
    }

    /** Message lines as rows without an item, placeholders filled. */
    public static @NotNull List<Row> rows(@NotNull List<String> lines, @Nullable Values values) {
        return lines.stream().map(line -> new Row(null, StageHud.line(line, values))).toList();
    }

    /** Lines as rows without an item. */
    public static @NotNull List<Row> rows(@NotNull List<Component> lines) {
        return lines.stream().map(line -> new Row(null, line)).toList();
    }

    /** Takes both panels down, and gives back the outlines an {@link #ahead} page held off. Safe to call twice. */
    public void close() {
        sides.forEach(Stage::close);
        sides.clear();
        if (covering != null) Stage.outlines(covering, true);
        covering = null;
    }

    /** A panel a quarter turn to one side of the seat ({@code -90} the left, {@code 90} the right), facing it. */
    private Stage side(Player viewer, Location seat, PluginDisplays displays, StageTheme theme, Look look, float turn) {
        Location at = seat.clone();
        at.setYaw(seat.getYaw() + turn);
        at.setPitch(0);
        Stage view = new Stage(viewer, StagePlane.inFrontOf(at, EYE_HEIGHT, look.distance(), look.lift(), 1, 1, 1, 1),
                displays, LIFE, theme);
        sides.add(view);
        return view;
    }

    /**
     * A title over one block of lines centred on the panel. Rows with an item
     * are spaced a line apart, the item drawn left of its line.
     */
    private static void page(Stage view, Look look, @Nullable Component title, List<Row> rows) {
        double scale = look.scale();
        boolean icons = rows.stream().anyMatch(row -> row.icon() != null);
        int step = icons ? 2 : 1;
        var body = Component.text();
        double widest = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) body.append(Component.newline());
            if (i > 0 && icons) body.append(Component.newline());
            body.append(rows.get(i).text());
            widest = Math.max(widest, width(rows.get(i).text(), false));
        }
        double line = Stage.TEXT_HEIGHT * scale;
        double height = ((rows.size() - 1) * step + 1) * line;
        // A text display stands on its bottom edge: the block is centred on the panel's middle.
        double bottom = -height / 2;
        view.show(Stage.textModel(body.build()).align(DisplayModel.Align.LEFT).lineWidth(1000)
                .background(BACKDROP).textShadow(false), view.still(scale), view.plane().point(0, bottom, 0));
        if (title != null) {
            view.show(Stage.textModel(title).lineWidth(1000).background(0).textShadow(false),
                    view.still(scale * TITLE_SCALE), view.plane().point(0, bottom + height + line * 0.5, 0));
        }
        if (!icons) return;
        double size = line * ICON;
        double across = -widest * scale / 2 - size / 2 - line * 0.4;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).icon() == null) continue;
            double up = bottom + height - (i * step + 0.5) * line;
            view.item(rows.get(i).icon(), view.plane().point(across, up, 0.01), view.still(size));
        }
    }

    /** How wide a line is at scale 1, in blocks, bold read off each part of it. */
    private static double width(Component component, boolean bold) {
        TextDecoration.State state = component.decoration(TextDecoration.BOLD);
        boolean thick = state == TextDecoration.State.NOT_SET ? bold : state == TextDecoration.State.TRUE;
        double width = 0;
        if (component instanceof TextComponent text) {
            String content = text.content();
            width = StageLook.width(content) - (thick ? 0 : content.length() * PIXEL);
        }
        for (Component child : component.children()) width += width(child, thick);
        return width;
    }
}
