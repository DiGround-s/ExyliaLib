package net.exylia.lib.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.exylia.lib.screen.ScreenMarkupTest.find;
import static net.exylia.lib.screen.ScreenMarkupTest.texts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 1.678.0 kit: stack numbers, tinted pictures, the pager's page as state, hint tooltips of items. */
class ScreenKit4MarkupTest {

    @Test
    void aPictureTintMultipliesItsPixels() {
        ScreenMarkup plain = ScreenMarkup.parse("p", "<screen><img id=\"a\" face=\"\"/></screen>");
        ScreenMarkup dark = ScreenMarkup.parse("d", "<screen><img id=\"a\" face=\"\" tint=\"#000000\"/></screen>");
        assertTrue(dark.problems().isEmpty(), dark.problems().toString());
        int[] lit = find(new ScreenMarkupTest.Fake(plain.screen()).render(), "a").pixels();
        int[] shape = find(new ScreenMarkupTest.Fake(dark.screen()).render(), "a").pixels();
        assertEquals(lit.length, shape.length);
        for (int i = 0; i < lit.length; i++) {
            assertEquals(lit[i] & 0xff000000, shape[i] & 0xff000000, "alpha kept");
            assertEquals(0, shape[i] & 0xffffff, "colour multiplied to black");
        }
    }

    @Test
    void theNodeCountIsNeverNegative() {
        assertEquals(0, Node.box().count(-3).count());
        assertEquals(12, Node.box().count(12).count());
    }

    @Test
    void checkFlagsCountAndTintMisuse() {
        List<String> problems = ScreenMarkup.parse("bad", "<screen><box count=\"3\"/><img face=\"x\" tint=\"red\"/></screen>").problems();
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(ScreenMarkup.parse("ok", "<screen><item material=\"stone\" count=\"3\"/><icon count=\"2\">stone</icon></screen>")
                .problems().isEmpty());
    }

    @Test
    void thePagerKeepsItsPageAsStateAndAJumpMovesIt() {
        ScreenMarkup markup = ScreenMarkup.parse("grid", """
                <screen>
                  <grid source="kits" columns="2" rows="2"><card><b>%playing%</b></card></grid>
                  <pager for="kits"/>
                </screen>""");
        markup.source("kits", s -> ScreenMarkupTest.kits(10));
        ScreenMarkupTest.Fake session = new ScreenMarkupTest.Fake(markup.screen());
        session.render();
        assertEquals(1, ((Number) session.state.get("page.kits")).intValue());
        session.click("kits.next");
        assertTrue(texts(session.render()).contains("2 / 3"));
        assertEquals(2, ((Number) session.state.get("page.kits")).intValue());
        // A jump from outside wins over the scroll it finds.
        session.set("page.kits", 3);
        assertTrue(texts(session.render()).contains("3 / 3"), texts(session.render()).toString());
        // Setting the scroll offset directly still works.
        session.set("scroll.kits", 0);
        assertTrue(texts(session.render()).contains("1 / 3"), texts(session.render()).toString());
        assertEquals(1, ((Number) session.state.get("page.kits")).intValue());
    }
}
