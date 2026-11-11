package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Node;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudLayersTest {

    /** A client draws a HUD's layers in the order it gets them: a panel must come before the words on it. */
    @Test
    void panelsComeBeforeWhatStandsOnThem() {
        Node card = Node.column(
                Node.text(Component.text("Health")),
                Node.row(Node.box().grow(1).background("#ff00ff00")).height(3).background("#40ffffff"))
                .padding(6).background("#c0101010").outline("#ff8a51c4", 1);
        HudLayers.Drawn drawn = HudLayers.of(card);
        List<HudLayers.Layer> layers = drawn.layers();
        // Four outline edges, the card, the words, the bar's track and its fill.
        assertEquals(8, layers.size());
        for (int i = 0; i < 5; i++) assertEquals(HudLayers.Kind.RECT, layers.get(i).kind());
        assertEquals(0xc0101010, layers.get(4).argb());
        assertEquals(HudLayers.Kind.TEXT, layers.get(5).kind());
        assertEquals(0x40ffffff, layers.get(6).argb());
        assertEquals(0xff00ff00, layers.get(7).argb());
        assertTrue(drawn.width() > 12 && drawn.height() > 12);
    }

    /** See-through bands must meet edge to edge: an overlap draws a darker seam. */
    @Test
    void gradientBandsDoNotOverlap() {
        Node box = Node.box().width(50).height(10).background("linear-gradient(to right, #80000000, #80ffffff)");
        List<HudLayers.Layer> bands = HudLayers.of(box).layers();
        assertTrue(bands.size() > 1);
        double right = 0;
        for (HudLayers.Layer band : bands) {
            assertEquals(right, band.x() - band.w() / 2, 1e-9);
            right = band.x() + band.w() / 2;
        }
        assertEquals(50, right, 1e-9);
    }

    /** Rounded corners: the rects cover exactly the rounded shape, none overlapping (a see-through overlap is a seam). */
    @Test
    void roundedCornersCoverTheShapeOnce() {
        for (int radius = 1; radius <= 4; radius++) {
            for (boolean outlined : new boolean[]{false, true}) {
                Node box = Node.box().width(20).height(12).background("#80112233").radius(radius);
                if (outlined) box.outline("#ff8a51c4", 1);
                List<HudLayers.Layer> rects = HudLayers.of(box).layers();
                int t = outlined ? 1 : 0, w = 20 + 2 * t, h = 12 + 2 * t;
                int[] hits = new int[w * h];
                for (HudLayers.Layer r : rects) {
                    int x0 = (int) Math.round(r.x() - r.w() / 2) + t, y0 = (int) Math.round(r.y() - r.h() / 2) + t;
                    for (int y = y0; y < y0 + (int) Math.round(r.h()); y++) {
                        for (int x = x0; x < x0 + (int) Math.round(r.w()); x++) hits[y * w + x]++;
                    }
                }
                int cut = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        assertTrue(hits[y * w + x] <= 1, "overlap at " + x + "," + y + " radius " + radius);
                        if (hits[y * w + x] == 0) cut++;
                    }
                }
                int corner = 0;
                for (int j = 0; j < radius; j++) corner += HudLayers.inset(radius, j);
                assertEquals(4 * corner, cut, "radius " + radius + " outlined " + outlined);
                assertTrue(hits[0] == 0 && hits[(h / 2) * w] == 1);
            }
        }
    }

    @Test
    void cornersAreSteps() {
        assertEquals(1, HudLayers.inset(1, 0));
        assertEquals(2, HudLayers.inset(2, 0));
        assertEquals(1, HudLayers.inset(2, 1));
        assertEquals(4, HudLayers.inset(4, 0));
        assertEquals(2, HudLayers.inset(4, 1));
    }

    /** A rounded gradient is still bands edge to edge, only cut at the corners. */
    @Test
    void roundedGradientKeepsItsColours() {
        Node box = Node.box().width(40).height(8).radius(2)
                .background("linear-gradient(to right, #ff000000, #ffffffff)");
        List<HudLayers.Layer> rects = HudLayers.of(box).layers();
        double area = 0;
        for (HudLayers.Layer r : rects) area += r.w() * r.h();
        assertEquals(40 * 8 - 4 * 3, area, 1e-9);
        assertTrue(rects.stream().map(HudLayers.Layer::argb).distinct().count() > 2);
    }
}
