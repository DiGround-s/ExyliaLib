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
}
