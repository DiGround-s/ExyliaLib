package net.exylia.lib.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.exylia.lib.screen.ScreenMarkupTest.find;
import static net.exylia.lib.screen.ScreenMarkupTest.kits;
import static net.exylia.lib.screen.ScreenMarkupTest.texts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 1.671.0 kit: detail, skip, progress shapes. */
class ScreenKitMarkupTest {

    private static ScreenMarkupTest.Fake session(String markup, int kits) {
        ScreenMarkup parsed = ScreenMarkup.parse("kit", markup);
        parsed.source("kits", s -> kits(kits));
        assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
        return new ScreenMarkupTest.Fake(parsed.screen());
    }

    private static final String DETAIL = """
            <screen>
              <grid source="kits">
                <card id="c" action="set pick_kit %kit_id%" selected="${pick('pick_kit') == kit_id}"><b>%kit_name%</b></card>
              </grid>
              <column id="panel">
                <detail source="kits" key="kit_id" state="pick_kit" prefer="playing >= 20">
                  <b>%kit_name% ${playing}</b>
                  <button id="buy" action="set bought %kit_id%">Buy</button>
                  <empty><small>Nothing</small></empty>
                </detail>
              </column>
            </screen>""";

    @Test
    void detailShowsThePreferredRowThenThePick() {
        ScreenMarkupTest.Fake s = session(DETAIL, 3);
        Node root = s.render();
        // Nothing picked: the first row prefer matches.
        assertEquals(List.of("K2 20", "Buy"), texts(find(root, "panel")));
        s.state.put("pick_kit", "k3");
        root = s.render();
        assertEquals(List.of("K3 30", "Buy"), texts(find(root, "panel")));
        // A button inside runs for that row.
        s.click("buy");
        assertEquals("k3", s.state.get("bought"));
        // A pick that is gone falls back.
        s.state.put("pick_kit", "k9");
        assertEquals(List.of("K2 20", "Buy"), texts(find(s.render(), "panel")));
    }

    @Test
    void detailKeysItsContentByTheRow() {
        ScreenMarkupTest.Fake s = session(DETAIL, 3);
        Node panel = find(s.render(), "panel");
        String before = panel.children().get(0).id();
        s.state.put("pick_kit", "k1");
        String after = find(s.render(), "panel").children().get(0).id();
        assertNotNull(before);
        assertTrue(before.endsWith("@k2") && after.endsWith("@k1"), before + " " + after);
        // A child with its own id keeps it.
        assertNotNull(find(s.render(), "buy"));
    }

    @Test
    void detailOfAnEmptySourceShowsItsEmpty() {
        ScreenMarkupTest.Fake s = session(DETAIL, 0);
        assertEquals(List.of("Nothing"), texts(find(s.render(), "panel")));
    }

    @Test
    void detailDefaultNoneWaitsForAPick() {
        ScreenMarkupTest.Fake s = session("""
                <screen><column id="panel">
                  <detail source="kits" key="kit_id" state="pick_kit" default="none"><b>%kit_name%</b><empty><small>Pick</small></empty></detail>
                  <small id="p">${pick('pick_kit')}</small>
                </column></screen>""", 2);
        assertEquals(List.of("Pick"), texts(find(s.render(), "panel")).subList(0, 1));
        s.state.put("pick_kit", "k2");
        assertEquals(List.of("K2", "k2"), texts(find(s.render(), "panel")));
    }

    @Test
    void pickMarksTheDefaultRowSelected() {
        ScreenMarkupTest.Fake s = session("""
                <screen>
                  <list source="kits"><text>${pick('pick_kit') == kit_id ? 'on' : 'off'}</text></list>
                  <detail source="kits" key="kit_id" state="pick_kit"><b>%kit_name%</b></detail>
                </screen>""", 3);
        assertEquals(List.of("on", "off", "off", "K1"), texts(s.render()));
    }

    @Test
    void detailNeedsASourceAndAKey() {
        ScreenMarkup parsed = ScreenMarkup.parse("bad", "<screen><detail state=\"x\"><b>a</b></detail></screen>");
        assertEquals(2, parsed.problems().size(), parsed.problems().toString());
    }

    @Test
    void skipLeavesOutTheFirstRowsAndThePagerCountsTheRest() {
        ScreenMarkupTest.Fake s = session("""
                <screen>
                  <for each="k in kits" skip="1"><small>${k.kit_name}</small></for>
                  <list source="kits" skip="3" rows="2"><text>%playing%</text></list>
                  <pager for="kits"/>
                </screen>""", 8);
        // for: K2..K8; list: rows 4-5 of the 5 left; pager: 3 pages.
        assertEquals(List.of("K2", "K3", "K4", "K5", "K6", "K7", "K8", "40", "50", "‹", "1 / 3", "›"), texts(s.render()));
    }

    @Test
    void verticalProgressFillsBottomUpWithSegments() {
        ScreenMarkupTest.Fake s = session("""
                <screen><progress id="bar" orient="vertical" value="25" max="100" segments="4"/></screen>""", 0);
        Node bar = find(s.render(), "bar");
        assertEquals(Node.Kind.COLUMN, bar.kind());
        // The rest on top, the fill under it, then the hairline overlay.
        assertNull(bar.children().get(0).background());
        assertNotNull(bar.children().get(1).background());
        assertNotNull(bar.children().get(1).transition());
        assertEquals(40, bar.height());
        Node marks = bar.children().get(2);
        assertNotNull(marks.absolute());
        assertEquals(7, marks.children().size());
    }

    @Test
    void countUpAndOnChangeParseAndMarkTheNode() {
        ScreenMarkupTest.Fake s = session("""
                <screen><b id="coins" count-up="8t" on-change="pop 6t">Coins ${coins}</b></screen>""", 0);
        s.state.put("coins", 100);
        Node coins = find(s.render(), "coins");
        assertNotNull(coins.changeKeyframes());
        assertEquals("Coins 100", coins.changeValue());
        assertEquals(0, coins.refresh());
        s.state.put("coins", 200);
        coins = find(s.render(), "coins");
        // Counting: drawn again every 2 ticks, starting from the old value; on-change watches the new one.
        assertEquals(2, coins.refresh());
        assertEquals("Coins 200", coins.changeValue());
        assertEquals(List.of("Coins 100"), texts(coins));
    }
}
