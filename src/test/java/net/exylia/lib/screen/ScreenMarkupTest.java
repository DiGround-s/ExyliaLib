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
    void twoGridsOfOneSourceInBranchesEachKeepTheirColumns() {
        ScreenMarkup markup = ScreenMarkup.parse("grid", """
                <screen>
                  <grid if="wide" source="kits" columns="2" rows="1">
                    <card><b>%playing%</b></card>
                  </grid>
                  <grid if="!wide" source="kits" columns="3" rows="1">
                    <card><b>%playing%</b></card>
                  </grid>
                  <pager for="kits"/>
                </screen>""");
        markup.source("kits", s -> kits(6));
        Fake session = new Fake(markup.screen());
        session.state.put("wide", true);
        Node root = session.render();
        // The first branch's grid windows two cells a line, not one column of every row.
        assertEquals(List.of("10", "20", "‹", "1 / 3", "›"), texts(root), texts(root).toString());
        session.state.put("wide", false);
        root = session.render();
        assertEquals(List.of("10", "20", "30", "‹", "1 / 2", "›"), texts(root), texts(root).toString());
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
    void aRequirementPicksTheDenyStepsWhenItFails() {
        ScreenMarkup markup = ScreenMarkup.parse("req", """
                <screen>
                  <button id="b" require="vip" action="hit yes" deny="hit no">Buy</button>
                  <button id="c" action="[console] say %player_name%; close" require="perm('x') or true">Run</button>
                </screen>""");
        List<String> hits = new ArrayList<>();
        markup.action("hit", (event, args) -> hits.add(args.get(0)));
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
        Fake session = new Fake(markup.screen());
        session.render();
        session.click("b");
        session.set("vip", true);
        session.render();
        session.click("b");
        assertEquals(List.of("no", "yes"), hits);
    }

    @Test
    void anEmptySourceShowsItsEmpty() {
        ScreenMarkup markup = ScreenMarkup.parse("e", """
                <screen><list source="kits"><text>%kit_name%</text><empty>0000</empty></list></screen>""");
        markup.source("kits", s -> List.of());
        assertEquals(List.of("0000"), texts(new Fake(markup.screen()).render()));
    }

    @Test
    void anEmptyFromATemplateIsTheEmptyNotARow() {
        ScreenMarkup markup = ScreenMarkup.parse("e", """
                <screen><template name="nothing" props="title"><empty><text>${title}</text></empty></template>
                <list source="kits"><text>%kit_name%</text><nothing title="0000"/></list></screen>""");
        markup.source("kits", s -> List.of(Map.of("kit_name", "a")));
        assertEquals(List.of("a"), texts(new Fake(markup.screen()).render()));
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
    void dynamicClassesTakePartInMatching() {
        ScreenMarkup markup = ScreenMarkup.parse("dyn", """
                <screen><card id="c" class="tile ${on ? 'lit' : 'dim'}">1</card></screen>""",
                ".tile.lit { background: #222222; } .tile.dim { background: #444444; }");
        assertEquals("#222222", find(new Fake(markup.screen(Map.of("on", true))).render(), "c").background());
        assertEquals("#444444", find(new Fake(markup.screen(Map.of("on", false))).render(), "c").background());
    }

    @Test
    void persistedStateKeysAreCollected() {
        ScreenMarkup markup = ScreenMarkup.parse("prefs.html", """
                <screen><tabs state="tab" default="a" persist="player"><tab value="a">A</tab></tabs>
                <chips state="cat" default="x"><chip value="x">X</chip></chips></screen>""");
        Screen screen = markup.screen();
        assertEquals("prefs", screen.persistScope());
        assertEquals(java.util.Set.of("tab"), screen.persisted());
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void problemsNameTheFileAndLine() {
        ScreenMarkup markup = ScreenMarkup.parse("bad.html", "<screen>\n<blink/>\n<pager/>\n</screen>",
                "card { colour: red; }");
        List<String> problems = markup.problems();
        assertTrue(problems.contains("bad.html:2:1: unknown tag <blink>"), problems.toString());
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
                .source("kits", s -> kits(10)).action("pick", (e, a) -> { }).action("speed", (e, a) -> { });
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

    // ------------------------------------------------------------------
    // 1.494.0: authoring
    // ------------------------------------------------------------------

    @Test
    void htmlHabitsReadAsTheyLook() {
        ScreenMarkup markup = ScreenMarkup.parse("l", """
                <screen>
                  <text id="a" if="x && y < 3">{primary}&lBOLD</text>
                  <button id="b" disabled action=close>No</button>
                </screen>""").value("x", s -> "true").value("y", s -> 2);
        Fake session = new Fake(markup.screen());
        Node root = session.render();
        assertNotNull(find(root, "a"));
        assertTrue(texts(root).contains("BOLD"), texts(root).toString());
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void expressionsComputeAndFormat() {
        ScreenMarkup markup = ScreenMarkup.parse("e", """
                <screen>
                  <text>${kills + deaths} ${plural(kills, 'kill', 'kills')} ${ratio > 1.5 ? 'good' : 'bad'}</text>
                  <text>${fmt.number(1234567)} ${upper(name)} ${min(3, 1, 2)} ${'a' in 'a b'} ${not false and (1 == 1.0)}</text>
                  <text if="kills >= 10 or missing">ten</text>
                </screen>""").value("kills", s -> 10).value("deaths", s -> "4").value("ratio", s -> 2.5)
                .value("name", s -> "bob");
        List<String> shown = texts(new Fake(markup.screen()).render());
        assertEquals(List.of("14 kills good", "1,234,567 BOB 1 true true", "ten"), shown);
        ScreenMarkup broken = ScreenMarkup.parse("b.html", "<screen>\n  <text if=\"a ==\">x</text>\n</screen>");
        assertTrue(broken.problems().stream().anyMatch(p -> p.startsWith("b.html:2:3: if=")), broken.problems().toString());
    }

    @Test
    void templatesFillTheirPropsAndSlot() {
        ScreenMarkup markup = ScreenMarkup.parse("t", """
                <screen>
                  <template name="stat" props="label value tone=info">
                    <row class="stat tone-${tone}"><small>${label}</small><b>${value}</b><slot/></row>
                  </template>
                  <stat id="s1" label="Kills" value="%kills%" tone="success"><text>!</text></stat>
                  <stat id="s2" label="Deaths" value="3"/>
                  <stat if="never" id="s3" label="x" value="y"/>
                </screen>""", ".tone-success { background: #00ff00; } .tone-info { background: #0000ff; }")
                .value("kills", s -> 7).value("never", s -> false);
        Node root = new Fake(markup.screen()).render();
        assertEquals(List.of("Kills", "7", "!", "Deaths", "3"), texts(root));
        assertEquals("#00ff00", find(root, "s1").background());
        assertEquals("#0000ff", find(root, "s2").background());
        assertNull(find(root, "s3"));
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void forSwitchAndElseChooseWhatIsDrawn() {
        ScreenMarkup markup = ScreenMarkup.parse("f", """
                <screen>
                  <for each="kit in kits"><text>${loop.number}:${kit.kit_name}</text></for>
                  <for each="i in 1..3"><text>n${i}</text></for>
                  <switch on="tab"><case value="a|b">AB</case><case value="c">C</case><default>D</default></switch>
                  <text if="tab == 'x'">X</text><text else if="tab == 'c'">elseif</text><text else>else</text>
                </screen>""").source("kits", s -> kits(2));
        Fake session = new Fake(markup.screen(Map.of("tab", "c")));
        assertEquals(List.of("1:K1", "2:K2", "n1", "n2", "n3", "C", "elseif"), texts(session.render()));
        session.state.put("tab", "b");
        assertEquals(List.of("1:K1", "2:K2", "n1", "n2", "n3", "AB", "else"), texts(session.render()));
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void wordsComeFromTheLanguageFileWithTheInlineTextAsFallback(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve("hub.html"), """
                <screen><text t="play.title">Play</text><text t="play.missing">Inline</text>
                <toggle state="x" t-on="on" t-off="off"/><include src="parts/foot.html"/></screen>""");
        java.nio.file.Files.createDirectories(dir.resolve("parts"));
        java.nio.file.Files.writeString(dir.resolve("parts/foot.html"), "<small>foot</small><small>%n%</small>");
        Map<String, String> words = Map.of("play.title", "Jugar", "on", "Sí", "off", "No");
        ScreenMarkup markup = ScreenMarkup.read("hub.html", () -> ScreenMarkup.compile("hub.html",
                java.nio.file.Files.readString(dir.resolve("hub.html")), List.of(), List.of(),
                rel -> ScreenMarkup.readInside(dir, rel), words::get, dir)).value("n", s -> 5);
        assertEquals(List.of("Jugar", "Inline", "No", "foot", "5"), texts(new Fake(markup.screen()).render()));
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void aPlainValueNeverRestylesTheScreenNorSplitsAnAction() {
        List<List<String>> args = new ArrayList<>();
        List<Object> rows = new ArrayList<>();
        ScreenMarkup markup = ScreenMarkup.parse("x", """
                <screen><list source="people"><text action="pick %who% end" action-right="drop">%who% %rank%</text></list>
                <text id="q">%query%</text></screen>""")
                .rows("people", s -> List.of(net.exylia.lib.ui.UiEntry.of("bob-object")
                        .with("who", "<red>Bob two").withFormatted("rank", "{highlight}VIP").build()))
                .text("query", s -> "&cred")
                .action("pick", (e, a) -> { args.add(a); rows.add(e.row()); })
                .action("drop", (e, a) -> rows.add("right"));
        Fake session = new Fake(markup.screen());
        Node root = session.render();
        Node row = find(root, "people").children().get(0);
        String raw = String.valueOf(row.text());
        assertTrue(texts(root).contains("<red>Bob two VIP"), texts(root).toString());
        assertTrue(texts(root).contains("&cred"), texts(root).toString());
        row.onClick().accept(new ScreenEvent(session, ScreenEvent.Kind.LEFT, 0, row.id()));
        assertEquals(List.of(List.of("<red>Bob two", "end")), args);
        assertEquals("bob-object", rows.get(0));
        row.onClick().accept(new ScreenEvent(session, ScreenEvent.Kind.RIGHT, 0, row.id()));
        assertEquals("right", rows.get(1));
        assertFalse(raw.isEmpty());
    }

    @Test
    void aTrackedRowKeepsItsIdWhenTheListReorders() {
        List<Map<String, Object>> list = new ArrayList<>(kits(3));
        ScreenMarkup markup = ScreenMarkup.parse("k", """
                <screen><list source="kits" track="%kit_id%"><text>%kit_name%</text></list></screen>""")
                .source("kits", s -> list);
        Fake session = new Fake(markup.screen());
        assertEquals("kits@k2", find(session.render(), "kits").children().get(1).id());
        java.util.Collections.reverse(list);
        assertEquals("kits@k2", find(session.render(), "kits").children().get(1).id());
        assertEquals("kits@k3", find(session.render(), "kits").children().get(0).id());
    }

    @Test
    void cssVariablesAndStructuralSelectors() {
        ScreenMarkup markup = ScreenMarkup.parse("c", """
                <screen><row id="r"><box id="a"/><box id="b"/><box id="c" class="x"/></row></screen>""",
                ":root { --accent: #111111; } box { background: var(--accent); } row > box:first-child { width: 5px; }"
                        + " box:nth-child(even) { background: #222222; } box:last-child:not(.y) { height: 7px; }"
                        + " screen > box { width: 99px; } #r { --accent: #333333; }");
        Node root = new Fake(markup.screen()).render();
        assertEquals("#333333", find(root, "a").background());
        assertEquals(5, find(root, "a").width());
        assertEquals("#222222", find(root, "b").background());
        assertEquals(7, find(root, "c").height());
        assertTrue(find(root, "b").width() < 0, "only a child of screen is 99px");
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void namesNobodyRegisteredAreReportedWithTheirPlace() {
        ScreenMarkup markup = ScreenMarkup.parse("n.html", "<screen>\n<list source=\"nope\"/>\n<text action=\"zap\">x</text>\n<frob/></screen>");
        List<String> problems = markup.problems();
        assertTrue(problems.contains("n.html:2:1: no source \"nope\" is registered"), problems.toString());
        assertTrue(problems.contains("n.html:3:1: no action \"zap\" is registered"), problems.toString());
        assertTrue(problems.contains("n.html:4:1: unknown tag <frob>"), problems.toString());
        markup.source("nope", s -> List.of()).action("zap", (e, a) -> { });
        assertEquals(1, markup.problems().size(), markup.problems().toString());
    }

    @Test
    void aRoutedScreenReadsItsSourcesFromTheMenu() {
        ScreenMarkup markup = ScreenMarkup.parse("r.html", "<screen routes=\"shop\">\n<list source=\"main\"/></screen>");
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void nestedGridsAndTrackedRowsGiveEveryBoxItsOwnKey() {
        ScreenMarkup markup = ScreenMarkup.parse("nest", """
                <screen size="300x200">
                  <grid source="kits" columns="2">
                    <card><b>%kit_name%</b><text>%playing%</text><text>x</text></card>
                  </grid>
                  <list source="more" track="%kit_id%">
                    <card><b>a</b><b>b</b><b>c</b></card><text>tail</text>
                  </list>
                </screen>""");
        markup.source("kits", s -> kits(4)).source("more", s -> kits(2));
        List<Layout.Placed> laid = Layout.lay(new Fake(markup.screen()).render(), 300, 200);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Layout.Placed box : laid) assertTrue(keys.add(box.key()), "two boxes keyed " + box.key());
    }
}
