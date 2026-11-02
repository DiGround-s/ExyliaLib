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
        assertEquals(1, layout.menuVersion());
        assertEquals(6.0, layout.anchor().distance(), 1e-9);
        assertTrue(layout.carousel().shelf());
        assertEquals(16, layout.carousel().perPage());
        assertEquals(3, layout.intro().steps().size());
        assertEquals(SpatialLayout.Content.Type.TABS, layout.panel("tabs").content().type());
        assertEquals(SpatialLayout.Content.Type.ACTION, layout.panel("action").content().type());
        assertTrue(!layout.panel("hint").frame() && layout.panel("card").frame());
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
            boxes.put(name, project(d, rect(p.across(), p.up(), p.forward(), p.turn(), p.width() + grow, p.height() + grow)));
        });
        SpatialLayout.Preview preview = layout.preview();
        double s = Math.max(preview.scale(), preview.effectScale());
        List<double[]> standing = new ArrayList<>();
        double px = preview.at().get(0), py = preview.at().get(1), pz = preview.at().get(2);
        standing.addAll(rect(px, py - 0.06, pz + 0.39, 0, 0.78, 0.12));
        standing.addAll(rect(px, py + 0.9 * s, pz + 0.3 * s, 0, 0.6 * s, 1.8 * s));
        boxes.put("preview", project(d, standing));

        SpatialLayout.Carousel shelf = layout.carousel();
        double tile = shelf.spacing() * 0.8;
        double tall = Math.min(tile, shelf.rowGap() * 0.86);
        double[] at = {shelf.at().get(0), shelf.at().get(1), shelf.at().get(2)};
        for (int i = 0; i < shelf.perPage(); i++) {
            double[] slot = Geometry.shelf(i, shelf.slots(), shelf.spacing(), shelf.rowGap(), shelf.curve());
            double turn = Geometry.facingTurn(at[0] + slot[0], d - at[2] - slot[2]);
            // A hovered tile's icon comes 0.12 nearer and grows ×1.12: its largest outline.
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
    void theWholeBoardFitsSixtyDegrees() throws Exception {
        SpatialLayout layout = read(plugin, folder, SpatialMenus.CENTRE_STAGE);
        double half = layout.anchor().distance() * Math.tan(Math.toRadians(30));
        // A little margin inside the 60°, both ways.
        boxes(layout).forEach((name, box) -> assertTrue(box.x0() > -half * 0.95 && box.x1() < half * 0.95
                && box.y0() > -half * 0.95 && box.y1() < half * 0.95, name + " " + box + " leaves ±" + half));
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
        SpatialLayout.Panel leave = layout.panel("leave");
        assertTrue(floorWidth(words.leave(), d, layout.theme()) <= leave.width() * 0.86, words.leave());
        SpatialLayout.Panel hint = layout.panel("hint");
        for (String line : List.of(words.hintIdle(), words.hintHoverLocked().replace("%unlock%", "Not for sale"),
                words.hintBuy().replace("%price%", "$12,500.00"), words.hintEquip().replace("%name%", "Lightning Strike"))) {
            assertTrue(floorWidth(line, d - hint.forward(), layout.theme()) <= hint.width() * 0.9, line);
        }
        // Seven tabs, as a plugin with five categories, every offer and the favourites has, all in one row.
        SpatialLayout.Panel tabs = layout.panel("tabs");
        // Sized, as the row is, for its far end.
        double far = Math.sqrt(Math.pow(d - tabs.forward(), 2) + Math.pow(Math.abs(tabs.across()) + tabs.width() / 2, 2)
                + tabs.up() * tabs.up());
        double[] widths = new double[7];
        String[] names = {"All", "Meme", "Void", "Cataclysm", "Lovestruck", "Cosmic", words.tabFavourites()};
        for (int i = 0; i < names.length; i++) {
            widths[i] = floorWidth(words.tabActive().replace("%tab%", names[i]), far, layout.theme()) + 0.2;
        }
        assertArrayEquals(new int[]{0, 7}, Geometry.tabWindow(widths, 0.05, layout.panel("tabs").width(), 3));
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
}
