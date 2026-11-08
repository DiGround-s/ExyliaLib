package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The motion CSS, wrapped text and the new layout: pure, no server. */
class MotionTest {

    @Test
    void readsMotionCss() {
        assertEquals(new Motion.Transform(0, -2, 1.1), MotionCss.transform("translateY(-2px) scale(1.1)"));
        assertEquals(Motion.Transform.NONE, MotionCss.transform("none"));
        assertEquals(4, MotionCss.ticks("200ms"));
        assertEquals(6, MotionCss.ticks("0.3s"));
        assertEquals(-1, MotionCss.ticks("ease"));
        Motion.Transition t = MotionCss.transition("transform 3t ease-out, background 6t linear");
        assertEquals(6, t.ticks());
        assertEquals(Motion.Ease.LINEAR, t.ease());
        assertTrue(t.colours());
        assertNull(MotionCss.transition("none"));
        Motion.Tween in = MotionCss.tween("scale(0.9) 5t back");
        assertEquals(5, in.ticks());
        assertEquals(Motion.Ease.BACK, in.ease());
        assertEquals(Motion.Tween.NONE, MotionCss.tween("none"));
        assertTrue(Motion.Ease.BACK.at(0.6) > 1, "back overshoots");
        assertEquals(1, Motion.Ease.OUT.at(1), 1e-9);
    }

    @Test
    void keyframesLoop() {
        Css css = new Css().add("@keyframes pulse { from { transform: scale(1); } 50% { transform: scale(1.2); } to { transform: scale(1); } }"
                + " .p { animation: pulse 20t linear infinite; }", "test", problem -> { throw new AssertionError(problem); });
        Motion.Keyframes k = MotionCss.animation("pulse 20t linear infinite", css);
        assertNotNull(k);
        assertEquals(1.2, k.at(10).scale(), 1e-6);
        assertEquals(1.0, k.at(20).scale(), 1e-6);
        assertEquals(1.1, k.at(25).scale(), 1e-6);
        assertNull(MotionCss.animation("missing 20t", css));
    }

    @Test
    void wrapsAtSpaces() {
        Layout.Wrapped one = TextWrap.wrap(Component.text("short"), 200);
        assertEquals(1, one.lines());
        Layout.Wrapped many = TextWrap.wrap(Component.text("the quick brown fox jumps over the lazy dog"), 40);
        assertTrue(many.lines() > 2, "lines: " + many.lines());
        assertTrue(many.width() <= 40 + 1e-9 || many.lines() > 1);
        assertEquals(3, TextWrap.wrap(Component.text("a\nb\nc"), 200).lines());
    }

    @Test
    void linesKeepASmallIndent() {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
                TextWrap.wrap(Component.text("Status\n      · Money: 3\n Body text"), 400).text());
        // A run of spaces opening a line is cut to the indent, never stretched across the tooltip.
        assertEquals("Status\n  · Money: 3\n Body text", plain);
    }

    @Test
    void tooltipsAreANameAndOneBlock() {
        var panel = LiveScreen.tipPanel(java.util.List.of(Component.text("Name"), Component.text("Kind"),
                Component.empty(), Component.text("  · Stat: 1"), Component.empty()));
        assertEquals(2, panel.children().size());
        assertEquals(LiveScreen.TIP_LORE, panel.children().get(1).scale());
        assertEquals(3, TextWrap.wrap(panel.children().get(1).text(), 400).lines());
    }

    @Test
    void libraryComponentsCompile() {
        var markup = net.exylia.lib.screen.ScreenMarkup.parse("lib", """
                <screen keys="navigate">
                  <panel>
                    <page-head title="Kits" subtitle="Pick one"/>
                    <stat label="Wins" value="3" tone="success"/>
                    <badge tone="warning">New</badge>
                    <key-hint key="Shift">Back</key-hint>
                    <empty-state title="Nothing yet"/>
                    <search state="q" prompt="Search a kit"/>
                    <p>Long words that wrap across lines when they run past the width of their box.</p>
                    <button action="confirm 'Leave?' dangerous then close" action-shift="toast info shift">Leave</button>
                    <dialog title="Sure?" when="open" dismiss="clear open"><text>Really</text></dialog>
                  </panel>
                </screen>""", "");
        assertTrue(markup.problems().isEmpty(), markup.problems().toString());
    }

    @Test
    void marginsAndAbsolute() {
        Node a = Node.box().width(20).height(10).margin(5, 0, 0, 7);
        Node pinned = Node.box().width(8).height(8).absolute(2, 3, Double.NaN, Double.NaN);
        Node root = Node.column(a, pinned).width(100).height(50);
        List<Layout.Placed> laid = Layout.lay(root, 100, 50);
        Layout.Placed boxA = laid.stream().filter(p -> p.node() == a).findFirst().orElseThrow();
        Layout.Placed boxP = laid.stream().filter(p -> p.node() == pinned).findFirst().orElseThrow();
        Layout.Placed boxR = laid.stream().filter(p -> p.node() == root).findFirst().orElseThrow();
        assertEquals(boxR.y() + 5, boxA.y(), 1e-6);
        assertEquals(boxR.x() + 7, boxA.x(), 1e-6, "margin-left moves it in from the start");
        assertEquals(boxR.x() + boxR.w() - 3 - 8, boxP.x(), 1e-6, "right: 3px from the parent's edge");
        assertEquals(boxR.y() + 2, boxP.y(), 1e-6);
        assertEquals(laid.indexOf(boxR), boxA.parent());
    }
}
