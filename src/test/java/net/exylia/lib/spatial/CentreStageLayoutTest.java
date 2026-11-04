package net.exylia.lib.spatial;

import net.exylia.lib.FakeServer;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.spatial.internal.Geometry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The centre-stage catalogue layout the library ships: it reads back clean, and seen from the eyes nothing
 * stands in front of anything else (a hovered shelf tile included), everything fits a 60° view, and every
 * word fits its room at the size the readability floor draws it. Also the shelf and tab-row geometry.
 */
class CentreStageLayoutTest {

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Configs.releaseAll();
        plugin = FakeServer.newPlugin("CentreStagePlugin", folder.toFile());
    }

    /** Reads a layout shipped as a classpath resource the way a server reads its file. */
    static SpatialLayout read(Plugin plugin, Path folder, String resource) throws Exception {
        Path file = folder.resolve("spatial/stage.yml");
        Files.createDirectories(file.getParent());
        try (InputStream in = CentreStageLayoutTest.class.getClassLoader().getResourceAsStream(resource)) {
            Files.write(file, in.readAllBytes());
        }
        ConfigFile<SpatialLayout> loaded = Configs.define(plugin, "spatial/stage", SpatialLayout.class).load();
        // Keys left out take the record's defaults on purpose; none may be unknown or unreadable.
        var issues = loaded.issues().stream().filter(issue -> issue.type() != net.exylia.lib.config.ConfigIssue.Type.MISSING_KEY).toList();
        assertTrue(issues.isEmpty(), "every key of " + resource + " is the record's: " + issues);
        return loaded.get();
    }

    @Test
    void theTemplateReadsBackClean() throws Exception {
        SpatialLayout layout = read(plugin, folder, SpatialMenus.CENTRE_STAGE);
        assertEquals(4, layout.menuVersion());
        assertEquals(4.5, layout.anchor().distance(), 1e-9);
        assertTrue(layout.carousel().arc());
        assertEquals(8, layout.carousel().perPage());
        assertTrue(layout.backdrop().present());
        assertEquals(4, layout.intro().steps().size());
        assertEquals(SpatialLayout.Content.Type.TABS, layout.panel("tabs").content().type());
        assertEquals(SpatialLayout.Content.Type.ACTION, layout.panel("action").content().type());
        assertEquals(SpatialLayout.Content.Type.ICONS, layout.panel("icons").content().type());
        assertTrue(layout.theme().rises());
        layout.panels().forEach((name, panel) -> assertTrue(!panel.frame(), name + " stands bare: no glass anywhere"));
    }

    // ── Seen from the eyes ────────────────────────────────────────────────

    /** {minAcross, minUp, maxAcross, maxUp} projected onto the board plane from the eyes. */
    record Box(double x0, double y0, double x1, double y1) {
        boolean overlaps(Box o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }
    }

    static Box project(double distance, List<double[]> points) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (double[] p : points) {
            double k = distance / (distance - p[2]);
            x0 = Math.min(x0, p[0] * k);
            x1 = Math.max(x1, p[0] * k);
            y0 = Math.min(y0, p[1] * k);
            y1 = Math.max(y1, p[1] * k);
        }
        return new Box(x0, y0, x1, y1);
    }

    static List<double[]> rect(double across, double up, double forward, double turn, double width, double height) {
        double t = Math.toRadians(turn);
        List<double[]> corners = new ArrayList<>();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                double x = sx * width / 2;
                corners.add(new double[]{across + x * Math.cos(t), up + sy * height / 2, forward - x * Math.sin(t)});
            }
        }
        return corners;
    }

    /** Every piece of a centre-stage layout as the eyes see it. */
    static Map<String, Box> boxes(SpatialLayout layout) {
        double d = layout.anchor().distance();
        double reach = layout.theme().backingReach();
        Map<String, Box> boxes = new LinkedHashMap<>();
        layout.panels().forEach((name, p) -> {
            double grow = p.frame() ? reach * 2 : 0;
            // An icon row also writes the words of the icon looked at under it.
            double under = p.content().type() == SpatialLayout.Content.Type.ICONS ? p.height() * 0.95 : 0;
            boxes.put(name, project(d, rect(p.across(), p.up() - under / 2, p.forward(), p.turn(), p.width() + grow,
                    p.height() + grow + under)));
        });
        SpatialLayout.Preview preview = layout.preview();
        double s = Math.max(preview.scale(), preview.effectScale());
        List<double[]> standing = new ArrayList<>();
        double px = preview.at().get(0), py = preview.at().get(1), pz = preview.at().get(2);
        standing.addAll(rect(px, py - 0.06, pz + 0.39, 0, 0.78, 0.12));
        standing.addAll(rect(px, py + 0.9 * s, pz + 0.3 * s, 0, 0.6 * s, 1.8 * s));
        boxes.put("preview", project(d, standing));

        SpatialLayout.Carousel row = layout.carousel();
        double[] at = {row.at().get(0), row.at().get(1), row.at().get(2)};
        if (row.arc()) {
            double icon = row.spacing() * 0.74;
            for (int i = 0; i < row.perPage(); i++) {
                double[] slot = Geometry.carousel(i, row.perPage(), row.spacing(), row.centreGap(), row.curve());
                // The icon at rest, and the soft disc under it.
                boxes.put("arc/" + i, project(d, rect(at[0] + slot[0], at[1] - icon * 0.2, at[2] + slot[1], 0, icon, icon * 1.4)));
            }
            double outer = Geometry.outermost(row.perPage(), row.spacing(), row.centreGap()) + row.spacing() * 0.9;
            for (int side = -1; side <= 1; side += 2) {
                boxes.put("arc/arrow" + side, project(d, rect(at[0] + side * outer, at[1], at[2] + row.curve(), 0, 0.2, 0.3)));
            }
            boxes.put("arc/page", project(d, rect(at[0] + outer - row.spacing() * 0.5, at[1] - row.spacing() * 0.9 * 0.95, at[2] + row.curve(), 0, 0.5, 0.12)));
            return boxes;
        }
        SpatialLayout.Carousel shelf = row;
        double tile = shelf.spacing() * 0.8;
        double tall = Math.min(tile, shelf.rowGap() * 0.86);
        for (int i = 0; i < shelf.perPage(); i++) {
            double[] slot = Geometry.shelf(i, shelf.slots(), shelf.spacing(), shelf.rowGap(), shelf.curve());
            double turn = Geometry.facingTurn(at[0] + slot[0], d - at[2] - slot[2]);
            boxes.put("shelf/" + i, project(d, rect(at[0] + slot[0], at[1] + slot[1], at[2] + slot[2] + 0.12, -turn,
                    tile * 1.12, tall * 1.12)));
        }
        double bottom = -(shelf.rows() - 1) * shelf.rowGap();
        double outer = Geometry.outermost(shelf.slots(), shelf.spacing(), 0) + shelf.spacing() * 0.5 + 0.18;
        for (int side = -1; side <= 1; side += 2) {
            boxes.put("shelf/arrow" + side, project(d, rect(at[0] + side * outer, at[1] + bottom / 2, at[2] + shelf.curve(),
                    0, 0.4, tall * 1.2)));
        }
        double pageUp = bottom - tall / 2 - 0.26;
        boxes.put("shelf/page", project(d, rect(at[0], at[1] + pageUp, at[2], 0, 1.6, 0.16)));
        return boxes;
    }

    @Test
    void nothingStandsInFrontOfAnythingElse() throws Exception {
        Map<String, Box> boxes = boxes(read(plugin, folder, SpatialMenus.CENTRE_STAGE));
        List<String> names = List.copyOf(boxes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                Box a = boxes.get(names.get(i));
                Box b = boxes.get(names.get(j));
                assertTrue(!a.overlaps(b), names.get(i) + " " + a + " overlaps " + names.get(j) + " " + b);
            }
        }
    }

    @Test
    void theBoardFitsTheViewAndStaysAboveTheFloor() throws Exception {
        SpatialLayout layout = read(plugin, folder, SpatialMenus.CENTRE_STAGE);
        double d = layout.anchor().distance();
        double lift = layout.liftOverFloor(1.62, MenuSession.FLOOR_MARGIN);
        double x0 = 0, x1 = 0, y0 = 0, y1 = 0;
        for (Box box : boxes(layout).values()) {
            x0 = Math.min(x0, box.x0());
            x1 = Math.max(x1, box.x1());
            y0 = Math.min(y0, box.y0() + lift);
            y1 = Math.max(y1, box.y1() + lift);
        }
        double across = Math.toDegrees(Math.atan(x1 / d) - Math.atan(x0 / d));
        double tall = Math.toDegrees(Math.atan(y1 / d) - Math.atan(y0 / d));
        assertTrue(across <= 80 && tall <= 47, "about 78° × 45° (a 16:9 window at 70° FOV sees ~102° × 70°): " + across + "° × " + tall + "°");
        // Standing on flat ground, the lowest piece stays above the floor (eyes 1.62 above the feet).
        assertTrue(layout.lowest() + lift >= -1.62 + MenuSession.FLOOR_MARGIN - 1e-9,
                "lowest " + (layout.lowest() + lift + 1.62) + " above the floor");
    }

    @Test
    void aLayoutThatWouldSinkIsRaisedAboveTheFloor() {
        SpatialLayout low = new SpatialLayout().withPanel("deep", new SpatialLayout.Panel(List.of(0.0, -2.5, 0.0), 0,
                List.of(1.0, 0.3), "", "", new SpatialLayout.Content(), new SpatialLayout.Footer()));
        double lift = low.liftOverFloor(1.62, 0.15);
        assertEquals(-1.62 + 0.15, low.lowest() + lift, 1e-9, "its bottom 0.15 over the floor");
        assertTrue(new SpatialLayout().liftOverFloor(1.62, 0.15) - new SpatialLayout().anchor().lift() < 0.06,
                "the library's wardrobe, its labels at the floor, rises a few centimetres at most");
    }

    /** WCAG contrast of a colour over the sky and over deepslate, both seen through the backdrop. */
    private static double contrast(int text, int ground, int backdropAlpha) {
        double a = backdropAlpha / 255.0;
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

    @Test
    void everyWordColourReadsOnSkyAndGround() throws Exception {
        SpatialLayout layout = read(plugin, folder, SpatialMenus.CENTRE_STAGE);
        int alpha = layout.backdrop().alpha();
        // The palette colours the board writes with: letters, letters_black, secondary_light, success, warning,
        // accent, info, highlight. Not muted (#868e96) or error (#a33b53): too dark over the backdrop.
        int[] used = {0xE7CFFF, 0xA89AB5, 0xB48FD9, 0x8FFFC1, 0xFF9500, 0xFF6B9D, 0x59A4FF, 0xFFD700, 0x7DB7FF};
        int sky = 0x8AB4FF;
        int deepslate = 0x4D4D50;
        for (int colour : used) {
            assertTrue(contrast(colour, sky, alpha) >= 3, Integer.toHexString(colour) + " on sky " + contrast(colour, sky, alpha));
            assertTrue(contrast(colour, deepslate, alpha) >= 3, Integer.toHexString(colour) + " on deepslate " + contrast(colour, deepslate, alpha));
        }
    }

    // ── Readable and whole ────────────────────────────────────────────────

    /** How wide a line is drawn at the readability floor, standing {@code away} blocks from the eyes. */
    static double floorWidth(String line, double away, SpatialTheme theme) {
        return Geometry.visibleWidth(line) * Geometry.readableScale(away, theme.minGlyphDegrees(), Geometry.CAPITAL_PIXELS);
    }

    @Test
    void theEnglishWordsFitTheirRoomsUncut() throws Exception {
        SpatialLayout layout = read(plugin, folder, SpatialMenus.CENTRE_STAGE);
        SpatialCatalog.Words words = new SpatialCatalog.Words();
        double d = layout.anchor().distance();
        SpatialLayout.Panel action = layout.panel("action");
        for (String label : List.of(words.actionEquip(), words.actionEquipped(), words.actionPick(), words.actionLocked(),
                words.actionBuy().replace("%price%", "$12,500.00"), words.actionConfirm().replace("%price%", "$12,500.00"))) {
            assertTrue(floorWidth(label, d - action.forward(), layout.theme()) <= action.width() * 0.86, label);
        }
        SpatialLayout.Panel hint = layout.panel("hint");
        for (String line : List.of(words.hintIdle(), words.hintHoverLocked().replace("%unlock%", "Not for sale"),
                words.hintBuy().replace("%price%", "$12,500.00"), words.hintEquip().replace("%name%", "Lightning Strike"))) {
            assertTrue(floorWidth(line, d - hint.forward(), layout.theme()) <= hint.width() * 0.9, line);
        }
        SpatialLayout.Panel tabs = layout.panel("tabs");
        double far = Math.sqrt(Math.pow(d - tabs.forward(), 2) + Math.pow(Math.abs(tabs.across()) + tabs.width() / 2, 2)
                + tabs.up() * tabs.up());
        double[] widths = new double[7];
        String[] names = {"All", "Meme", "Void", "Cataclysm", "Lovestruck", "Cosmic", words.tabFavourites()};
        for (int i = 0; i < names.length; i++) {
            widths[i] = floorWidth(words.tabActive().replace("%tab%", names[i]), far, layout.theme()) + 0.16;
        }
        assertArrayEquals(new int[]{0, 7}, Geometry.tabWindow(widths, 0.04, tabs.width(), 3));
    }

    // ── Geometry ──────────────────────────────────────────────────────────

    @Test
    void shelfRowsAreAlikeAndSymmetric() {
        int slots = 8;
        for (int i = 0; i < slots; i++) {
            double[] top = Geometry.shelf(i, slots, 0.62, 0.62, 0.35);
            double[] under = Geometry.shelf(i + slots, slots, 0.62, 0.62, 0.35);
            double[] mirror = Geometry.shelf(slots - 1 - i, slots, 0.62, 0.62, 0.35);
            assertEquals(top[0], under[0], 1e-9, "the same column in both rows");
            assertEquals(top[2], under[2], 1e-9, "both rows curve alike");
            assertEquals(-0.62, under[1] - top[1], 1e-9, "the second row a row-gap lower");
            assertEquals(-top[0], mirror[0], 1e-9, "mirrored about the middle");
            assertEquals(top[2], mirror[2], 1e-9);
        }
        assertEquals(0.35, Geometry.shelf(0, slots, 0.62, 0.62, 0.35)[2], 1e-9, "the outermost come curve nearer");
        assertTrue(Geometry.shelf(3, slots, 0.62, 0.62, 0.35)[2] < 0.05, "the middle stays back");
        // Adjacent tiles never touch, even hovered.
        double gap = Geometry.shelf(1, slots, 0.62, 0.62, 0.35)[0] - Geometry.shelf(0, slots, 0.62, 0.62, 0.35)[0];
        assertTrue(gap > 0.62 * 0.8 * 1.12, "tiles stand apart: " + gap);
    }

    @Test
    void shelfPagesHoldEveryRow() {
        SpatialLayout.Carousel shelf = new SpatialLayout.Carousel("x", List.of(0.0, 0.0, 0.0), 8, 0.62, 0, 0.35, 2, 0.62,
                SpatialLayout.Carousel.Style.SHELF);
        assertEquals(16, shelf.perPage());
        assertEquals(6, new SpatialLayout.Carousel().perPage(), "a plinth row keeps its slots");
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 37; i++) all.add(i);
        assertEquals(3, Geometry.pages(all.size(), shelf.perPage()));
        assertEquals(List.of(32, 33, 34, 35, 36), Geometry.page(all, 2, shelf.perPage()));
        assertEquals(Geometry.page(all, 0, 16), Geometry.page(all, 3, 16), "paging wraps round");
    }

    @Test
    void tabsShowAllWhenTheyFitAndNeverCutOne() {
        double[] widths = {0.6, 0.8, 0.8, 1.2, 1.3, 0.9, 0.4};
        assertArrayEquals(new int[]{0, 7}, Geometry.tabWindow(widths, 0.07, 10, 4));
        int[] window = Geometry.tabWindow(widths, 0.07, 3.0, 5);
        assertTrue(window[0] <= 5 && window[1] > 5, "the open tab is among them");
        assertTrue(Geometry.span(widths, 0.07, window[0], window[1]) <= 3.0, "whole tabs only");
        assertArrayEquals(new int[]{0, 3}, Geometry.tabWindow(widths, 0.07, 3.0, 0), "from the first when it can");
        double[] centres = Geometry.tabCentres(widths, 0.07, 0, 2);
        assertEquals(-centres[1] + (0.8 - 0.6) / 2, centres[0] + 0, 0.5);
        assertEquals(0, (centres[0] - 0.3 + centres[1] + 0.4) / 2, 1e-9, "the row is centred");
    }

    @Test
    void wrappedLinesAreBalanced() {
        String text = "The blast lifts them into a backflip, bounces them twice, and leaves them in pieces.";
        double width = Geometry.visibleWidth("The blast lifts them into a backflip,") + 0.01;
        List<String> lines = Geometry.balanced(text, width, 3);
        for (String line : lines) assertTrue(line.trim().split(" ").length > 1, "no lone word: " + lines);
        double longest = lines.stream().mapToDouble(Geometry::visibleWidth).max().orElse(0);
        double shortest = lines.stream().mapToDouble(Geometry::visibleWidth).min().orElse(0);
        assertTrue(shortest > longest * 0.6, "even lines: " + lines);
        assertEquals(String.join(" ", lines), text, "every word kept, in order");
    }

    /**
     * The words under an icon looked at never touch its glyph, grown under the crosshair: the bot read "?" over its
     * "Help" in every menu, because the words stood 0.78 of the row's height down whatever size the theme drew the
     * glyph and the readable floor drew the words. Boxes as the client draws a line: from its origin (half a line
     * under where it is written) up ten pixels and one more.
     */
    @Test
    void anIconsWordsStandClearOfItsGrownGlyph() throws Exception {
        SpatialLayout layout = read(plugin, folder, SpatialMenus.CENTRE_STAGE);
        SpatialLayout.Panel row = layout.panel("icons");
        double far = Math.hypot(layout.anchor().distance() - row.forward(), row.up());
        double floor = Geometry.readableScale(far, layout.theme().minGlyphDegrees(), Geometry.CAPITAL_PIXELS);
        double glyph = layout.theme().titleScale() * 1.5;
        double words = Math.max(floor, layout.theme().smallScale());
        double before = clearance(row.height() * 0.78, glyph, words);
        assertTrue(before < 0, "the shipped row overlapped before: " + before);
        for (double height : new double[]{0.12, 0.246, 0.4}) {
            for (double title : new double[]{0.6, 0.9, 1.2}) {
                for (double small : new double[]{0.3, words, 0.7}) {
                    double drop = Geometry.iconWordsDrop(height, title * 1.5, small);
                    assertTrue(clearance(drop, title * 1.5, small) >= 0.02,
                            "row " + height + ", glyph " + title * 1.5 + ", words " + small + ": " + clearance(drop, title * 1.5, small));
                    assertTrue(drop >= height * 0.78 - 1e-9, "never higher than before");
                }
            }
        }
    }

    /** Between the bottom of a glyph's box written at 0 and the top of its words' box written {@code drop} under it. */
    private static double clearance(double drop, double glyph, double words) {
        double line = Geometry.PIXEL * 10;
        double glyphBottom = -line * glyph / 2;
        double wordsTop = -drop - line * words / 2 + (line + Geometry.PIXEL) * words;
        return glyphBottom - wordsTop;
    }

    @Test
    void anEffectIsTurnedToShowItsMotionAcrossTheView() {
        // A body thrown straight away from the viewer when played facing them: seen end-on, it shows nothing.
        float board = 0;
        double[] base = {0, 64, 0};
        java.util.List<double[]> samples = new java.util.ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            // Facing the viewer means facing yaw 180 on a board of yaw 0: its own forward is -z here.
            samples.add(new double[]{0, 64 + 0.1 * i, -0.15 * i, 0.1});
        }
        var tuning = net.exylia.lib.spatial.internal.EffectView.choose(samples, base, board, 0, true, 0, new double[]{0, 0});
        assertEquals(90, Math.abs(tuning.turn()), 1e-9, "turned side-on: " + tuning.turn());
        // Turned, the throw runs across the view.
        double[] end = net.exylia.lib.spatial.internal.EffectView.place(samples.getLast(), base, board, 0, tuning.turn());
        assertTrue(Math.abs(end[0]) > 1.4 && Math.abs(end[2]) < 1e-6, java.util.Arrays.toString(end));
        // Something already moving across is left facing the viewer.
        java.util.List<double[]> across = new java.util.ArrayList<>();
        for (int i = 0; i <= 10; i++) across.add(new double[]{0.15 * i, 64, 0, 0.1});
        assertEquals(0, net.exylia.lib.spatial.internal.EffectView.choose(across, base, board, 0, true, 0, new double[]{0, 0}).turn(), 1e-9);
        // Too tall for its zone: played smaller, never under 60 % of its size.
        java.util.List<double[]> tall = new java.util.ArrayList<>();
        for (int i = 0; i <= 20; i++) tall.add(new double[]{0, 64 + 0.1 * i, 0, 0.05, 1});
        var fitted = net.exylia.lib.spatial.internal.EffectView.choose(tall, base, board, 0, true, 0, new double[]{1.3, 0.85});
        assertEquals(net.exylia.lib.spatial.internal.EffectView.MIN_SCALE, fitted.scale(), 1e-9);
        // A body that fits, and one far outlier (a crowd member, a part thrown away): not shrunk for it.
        java.util.List<double[]> body = new java.util.ArrayList<>();
        for (int i = 0; i <= 30; i++) body.add(new double[]{0, 64 + 0.02 * i, 0, 0.05, 1});
        body.add(new double[]{0, 70, 0, 0.05, 1});
        body.add(new double[]{3, 64.5, 0, 0.3, 0});
        assertEquals(1, net.exylia.lib.spatial.internal.EffectView.choose(body, base, board, 0, true, 0, new double[]{1.3, 0.85}).scale(), 1e-9);
    }
}
