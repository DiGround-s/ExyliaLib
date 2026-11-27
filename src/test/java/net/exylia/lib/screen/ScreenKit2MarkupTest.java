package net.exylia.lib.screen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static net.exylia.lib.screen.ScreenMarkupTest.find;
import static net.exylia.lib.screen.ScreenMarkupTest.texts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 1.672.0 kit: hold to confirm, CSS imports, small tooltips, burst, podium. */
class ScreenKit2MarkupTest {

    private static ScreenMarkupTest.Fake session(String markup) {
        ScreenMarkup parsed = ScreenMarkup.parse("kit2", markup);
        assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
        return new ScreenMarkupTest.Fake(parsed.screen());
    }

    @Test
    void actionHoldMarksTheBoxWithItsTimeLabelAndColour() {
        ScreenMarkupTest.Fake s = session("""
                <screen><button id="go" key="space" action="set done yes" action-hold="1.2s" hold-label="Keep holding"
                  style="color: {success}">Rebirth</button></screen>""");
        Node go = find(s.render(), "go");
        assertEquals(24, go.holdTicks());
        assertNotNull(go.holdLabel());
        assertEquals(List.of("Keep holding"), texts(go.holdLabel()));
        assertEquals("{success}", go.holdColour());
        // The handler is the ordinary click: the screen runs it once the hold is full.
        s.click("go");
        assertEquals("yes", s.state.get("done"));
    }

    @Test
    void aDisabledHoldIsNoHold() {
        ScreenMarkupTest.Fake s = session("""
                <screen><button id="go" disabled="" action="set done yes" action-hold="24t">Go</button></screen>""");
        assertEquals(0, find(s.render(), "go").holdTicks());
    }

    @Test
    void actionHoldNeedsAnActionAndATime() {
        assertEquals(1, ScreenMarkup.parse("bad", "<screen><button action-hold=\"24t\">Go</button></screen>").problems().size());
        assertEquals(1, ScreenMarkup.parse("bad", "<screen><button action=\"close\" action-hold=\"soon\">Go</button></screen>")
                .problems().size());
    }

    @Test
    void cssImportsReadInsideTheFolderFirst(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("sections"));
        Files.writeString(dir.resolve("s.html"), "<screen><box id=\"a\"/><box id=\"b\"/></screen>");
        Files.writeString(dir.resolve("sections/home.css"), "@import 'sections/base.css';\n#a { width: 30px; } #b { width: 7px; }");
        Files.writeString(dir.resolve("sections/base.css"), "#a { width: 10px; height: 3px; }");
        Files.writeString(dir.resolve("s.css"), "@import \"sections/home.css\";\n#b { width: 9px; }");
        ScreenMarkup parsed = ScreenMarkup.load(dir.resolve("s.html"), dir.resolve("s.css"));
        assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
        Node root = new ScreenMarkupTest.Fake(parsed.screen()).render();
        // Imported rules come first: the importing sheet wins over them, a later import over an earlier one.
        assertEquals(30, find(root, "a").width());
        assertEquals(3, find(root, "a").height());
        assertEquals(9, find(root, "b").width());
    }

    @Test
    void cssImportsNeverLeaveTheFolder(@TempDir Path dir) throws Exception {
        Path inner = Files.createDirectories(dir.resolve("screens"));
        Files.writeString(dir.resolve("secret.css"), "#a { width: 99px; }");
        Files.writeString(inner.resolve("s.html"), "<screen><box id=\"a\"/></screen>");
        Files.writeString(inner.resolve("s.css"), "@import \"../secret.css\";\n@import \"missing.css\";");
        ScreenMarkup parsed = ScreenMarkup.load(inner.resolve("s.html"), inner.resolve("s.css"));
        assertEquals(2, parsed.problems().size(), parsed.problems().toString());
        assertTrue(parsed.problems().get(0).startsWith("s.css:1:"), parsed.problems().get(0));
    }

    @Test
    void hintTooltipsPerBoxOrForTheWholeScreen() {
        ScreenMarkupTest.Fake s = session("""
                <screen><box id="a" tooltip="Coins"/><box id="b" tooltip="Gems" tooltip-style="hint"/></screen>""");
        Node root = s.render();
        assertEquals(Node.TooltipStyle.PANEL, find(root, "a").tooltipStyle());
        assertEquals(Node.TooltipStyle.HINT, find(root, "b").tooltipStyle());
        s = session("""
                <screen tooltips="hint"><box id="a" tooltip="Coins"/><box id="b" tooltip="Name" tooltip-style="panel"/></screen>""");
        root = s.render();
        assertEquals(Node.TooltipStyle.HINT, find(root, "a").tooltipStyle());
        assertEquals(Node.TooltipStyle.PANEL, find(root, "b").tooltipStyle());
    }
}
