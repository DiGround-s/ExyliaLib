package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.Layout;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenMarkupTest {

    /** A session with state only, enough to render and click. */
    static final class Fake implements ScreenSession {
        final Map<String, Object> state = new HashMap<>();
        final Screen screen;
        boolean closed;

        Fake(Screen screen) {
            this.screen = screen;
            state.putAll(screen.state());
        }

        @Override public Player viewer() { throw new UnsupportedOperationException(); }
        @Override public Screen screen() { return screen; }
        @Override @SuppressWarnings("unchecked") public <T> T state(String key, T fallback) {
            Object v = state.get(key);
            return v == null ? fallback : (T) v;
        }
        @Override public void set(String key, Object value) { if (value == null) state.remove(key); else state.put(key, value); }
        @Override public void redraw() { }
        @Override public boolean open() { return !closed; }
        @Override public void close() { closed = true; }

        Node render() { return screen.render().apply(this); }

        void click(String id) {
            Node node = find(render(), id);
            assertNotNull(node, "no node " + id);
            assertNotNull(node.onClick(), id + " is not clickable");
            node.onClick().accept(new ScreenEvent(this, ScreenEvent.Kind.LEFT, 0, id));
        }
    }

    static Node find(Node node, String id) {
        if (id.equals(node.id())) return node;
        for (Node child : node.children()) {
            Node found = find(child, id);
            if (found != null) return found;
        }
        return null;
    }

    static List<String> texts(Node node) {
        List<String> out = new ArrayList<>();
        if (node.text() != null) out.add(PlainTextComponentSerializer.plainText().serialize(node.text()));
        for (Node child : node.children()) out.addAll(texts(child));
        return out;
    }

    static List<Map<String, Object>> kits(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) rows.add(Map.of("kit_id", "k" + i, "kit_name", "K" + i, "playing", i * 10));
        return rows;
    }

    @Test
    void tabsSelectTheirDefaultAndClicksMoveTheState() {
        ScreenMarkup markup = ScreenMarkup.parse("hub", """
                <screen>
                  <tabs state="tab" default="queue">
                    <tab id="t1" value="queue">1</tab>
                    <tab id="t2" value="duels">2</tab>
                  </tabs>
                  <text id="q" if="tab == 'queue'">111</text>
                  <text id="d" if="tab != 'queue'">222</text>
                </screen>""");
        Fake session = new Fake(markup.screen());
        Node root = session.render();
        assertEquals("{primary}", find(root, "t1").background());
        assertNotNull(find(root, "q"));
        assertEquals(null, find(root, "d"));
        session.click("t2");
        assertEquals("duels", session.state.get("tab"));
        root = session.render();
        assertEquals("{primary}", find(root, "t2").background());
        assertNotNull(find(root, "d"));
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void aGridShowsAWindowAndThePagerMovesIt() {
        ScreenMarkup markup = ScreenMarkup.parse("grid", """
                <screen>
                  <grid source="kits" columns="2" rows="2">
                    <card action="queue %kit_id%"><b>%playing%</b></card>
                  </grid>
                  <pager for="kits"/>
                </screen>""");
        List<String> queued = new ArrayList<>();
        markup.source("kits", s -> kits(10)).action("queue", (event, args) -> queued.add(args.get(0)));
        Fake session = new Fake(markup.screen());
        Node root = session.render();
        assertTrue(texts(root).containsAll(List.of("10", "20", "30", "40", "1 / 3")), texts(root).toString());
        assertFalse(texts(root).contains("50"));
        session.click("kits.next");
        root = session.render();
        assertTrue(texts(root).containsAll(List.of("50", "80", "2 / 3")), texts(root).toString());
        session.click("kits.4");
        assertEquals(List.of("k5"), queued);
        Node grid = find(root, "kits");
        grid.onScroll().accept(new ScreenEvent(session, ScreenEvent.Kind.SCROLL, 5, "kits"));
        assertTrue(texts(session.render()).containsAll(List.of("90", "100", "3 / 3")));
    }

    @Test
    void anEmptySourceShowsItsEmpty() {
        ScreenMarkup markup = ScreenMarkup.parse("e", """
                <screen><list source="kits"><text>%kit_name%</text><empty>0000</empty></list></screen>""");
        markup.source("kits", s -> List.of());
        assertEquals(List.of("0000"), texts(new Fake(markup.screen()).render()));
    }

    @Test
    void sheetsCascadeBySpecificityThenOrder() {
        ScreenMarkup markup = ScreenMarkup.parse("css", """
                <screen><panel class="hero"><card id="c" selected="%on%">1</card></panel></screen>""",
                "card { background: #111111; padding: 2px 4px; } .hero card { background: #222222; }"
                        + " card[selected] { outline: 2px #333333; } card { width: 50px; }");
        Fake session = new Fake(markup.screen(Map.of("on", "true")));
        Node card = find(session.render(), "c");
        assertEquals("#222222", card.background());
        assertEquals("#333333", card.outline());
        assertEquals(2, card.outlineWidth());
        assertEquals(50, card.width());
        assertEquals(4, card.padLeft());
        assertEquals("#333333", card.hoverOutline());
    }

    @Test
    void problemsNameTheFileAndLine() {
        ScreenMarkup markup = ScreenMarkup.parse("bad.html", "<screen>\n<blink/>\n<pager/>\n</screen>",
                "card { colour: red; }");
        List<String> problems = markup.problems();
        assertTrue(problems.contains("bad.html:2: unknown tag <blink>"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad.html:3:")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown property \"colour\"")), problems.toString());
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> ScreenMarkup.parse("x.html", "<screen>\n<panel>\n</screen>"));
        assertTrue(malformed.getMessage().startsWith("x.html:3:"), malformed.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ScreenMarkup.parse("dtd.html",
                "<!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><screen>&x;</screen>"));
    }

    @Test
    void togglesFlipLocalStateAndTheTreeLaysOut() {
        ScreenMarkup markup = ScreenMarkup.parse("t", """
                <screen size="300x200"><toggle id="tg" state="sound" on="1" off="0"/></screen>""");
        Screen screen = markup.screen();
        assertEquals(300, screen.width());
        Fake session = new Fake(screen);
        assertEquals(List.of("0"), texts(session.render()));
        session.click("tg");
        assertEquals(List.of("1"), texts(session.render()));
        assertFalse(Layout.lay(session.render(), 300, 200).isEmpty());
    }

    @Test
    void theBundledDemoAndBaseThemeReadCleanly() throws Exception {
        String html = new String(getClass().getResourceAsStream("/screens/demo.html").readAllBytes());
        String css = new String(getClass().getResourceAsStream("/screens/demo.css").readAllBytes());
        ScreenMarkup markup = ScreenMarkup.parse("demo.html", html, css).value("speed", s -> "1.0")
                .source("kits", s -> kits(10)).action("pick", (e, a) -> { });
        Fake session = new Fake(markup.screen());
        Node root = session.render();
        assertTrue(texts(root).contains("1 / 2"), texts(root).toString());
        session.click("kits.0");
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void conditionsCombineAndAnEmptyOutsideARepeatIsDrawn() {
        ScreenMarkup markup = ScreenMarkup.parse("c", """
                <screen>
                  <text if="x &amp;&amp; !y">both</text>
                  <text if="y || x">either</text>
                  <text if="y &amp;&amp; x">never</text>
                  <empty>nothing here</empty>
                </screen>""").value("x", s -> "true").value("y", s -> "false");
        List<String> shown = texts(new Fake(markup.screen()).render());
        assertTrue(shown.contains("both"), shown.toString());
        assertTrue(shown.contains("either"), shown.toString());
        assertFalse(shown.contains("never"), shown.toString());
        assertTrue(shown.contains("nothing here"), shown.toString());
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void aModalOpensOnItsStateAndDismissClearsIt() {
        ScreenMarkup markup = ScreenMarkup.parse("m", """
                <screen>
                  <button id="ask" action="set confirm yes">Reset</button>
                  <modal if="confirm"><text>Sure?</text></modal>
                </screen>""");
        Fake session = new Fake(markup.screen());
        assertNull(overlay(session.render()));
        session.click("ask");
        Node modal = overlay(session.render());
        assertNotNull(modal);
        modal.onDismiss().accept(session);
        assertNull(overlay(session.render()));
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void aSemicolonInARowValueNeverStartsAnotherAction() {
        ScreenMarkup markup = ScreenMarkup.parse("s", """
                <screen>
                  <list source="rows"><text id="r" action="set picked %name%">%name%</text></list>
                </screen>""").source("rows", s -> List.of(Map.of("name", "x; set hacked yes")));
        Fake session = new Fake(markup.screen());
        Node row = find(session.render(), "rows").children().get(0);
        row.onClick().accept(new ScreenEvent(session, ScreenEvent.Kind.LEFT, 0, row.id()));
        assertNull(session.state.get("hacked"));
    }

    @Test
    void anImgIsReadFromTheMarkupsFolderAndNeverOutsideIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        picture.setRGB(0, 0, 0xFFFF0000);
        java.nio.file.Files.createDirectories(dir.resolve("img"));
        javax.imageio.ImageIO.write(picture, "png", dir.resolve("img/dot.png").toFile());
        java.nio.file.Path html = dir.resolve("hub.html");
        java.nio.file.Files.writeString(html, """
                <screen>
                  <img id="ok" src="img/%name%.png"/>
                  <img id="out" src="../secret.png"/>
                </screen>""");
        ScreenMarkup markup = ScreenMarkup.load(html).value("name", s -> "dot");
        Node root = new Fake(markup.screen()).render();
        Node ok = find(root, "ok");
        assertEquals(Node.Kind.IMAGE, ok.kind());
        assertEquals(3, ok.pixelWidth());
        assertEquals(0xFFFF0000, ok.pixels()[0]);
        assertEquals(Node.Kind.COLUMN, find(root, "out").kind());
        assertTrue(markup.problems().stream().anyMatch(p -> p.contains("outside")), markup.problems().toString());
    }

    @Test
    void anEmptyValueIsEmptyTextNotThePlaceholder() {
        ScreenMarkup markup = ScreenMarkup.parse("t", """
                <screen><list source="rows"><text id="name">%who%</text></list></screen>""")
                .source("rows", s -> { java.util.Map<String, Object> row = new java.util.HashMap<>(); row.put("who", null);
                    return java.util.List.of(row); });
        String drawn = String.valueOf(find(new Fake(markup.screen()).render(), "rows.0.0").text());
        assertFalse(drawn.contains("%"), drawn);
    }

    static Node overlay(Node node) {
        if (node.overlay()) return node;
        for (Node child : node.children()) {
            Node found = overlay(child);
            if (found != null) return found;
        }
        return null;
    }
}
