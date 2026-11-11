package net.exylia.lib.screen;

import net.exylia.lib.screen.internal.Layout;
import net.exylia.lib.screen.internal.ScreenMath;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LayoutTest {

    private static Layout.Placed find(List<Layout.Placed> placed, String id) {
        return placed.stream().filter(p -> id.equals(p.node().id())).findFirst().orElseThrow();
    }

    @Test
    void rowPlacesChildrenSideBySideWithPaddingAndGap() {
        Node root = Node.row(Node.box().size(30, 10).id("a"), Node.box().size(20, 10).id("b")).padding(5).gap(4);
        List<Layout.Placed> placed = Layout.lay(root, 100, 50);
        Layout.Placed a = find(placed, "a");
        Layout.Placed b = find(placed, "b");
        assertEquals(5, a.x());
        assertEquals(5, a.y());
        assertEquals(39, b.x());
        assertEquals(1, a.depth());
    }

    @Test
    void growSharesWhatIsLeftAndStretchFillsTheCrossAxis() {
        Node root = Node.row(Node.box().width(20).id("fixed"), Node.box().grow(1).id("a"), Node.box().grow(3).id("b"));
        List<Layout.Placed> placed = Layout.lay(root, 100, 40);
        assertEquals(20, find(placed, "a").w());
        assertEquals(60, find(placed, "b").w());
        assertEquals(40, find(placed, "b").h());
        assertEquals(40, find(placed, "b").x());
    }

    @Test
    void justifyAndAlignCentre() {
        Node root = Node.column(Node.box().size(40, 20).id("c"))
                .justify(Node.Justify.CENTER).align(Node.Align.CENTER);
        Layout.Placed c = find(Layout.lay(root, 100, 60), "c");
        assertEquals(30, c.x());
        assertEquals(20, c.y());
    }

    @Test
    void spaceBetweenPushesTheLastToTheEnd() {
        Node root = Node.row(Node.box().width(10).id("a"), Node.box().width(10).id("b"))
                .justify(Node.Justify.SPACE_BETWEEN);
        assertEquals(90, find(Layout.lay(root, 100, 10), "b").x());
    }

    @Test
    void textIsMeasuredByTheFontAndScale() {
        Node text = Node.text(Component.text("ab")).scale(2).padding(1, 3).id("t");
        Layout.Placed t = find(Layout.lay(Node.row(text).align(Node.Align.START), 200, 50), "t");
        assertEquals(2 * 2 * 6 + 6, t.w());
        assertEquals(2 * Layout.LINE + 2, t.h());
    }

    @Test
    void underPicksTheTopmostMatchingBox() {
        Node inner = Node.box().size(20, 20).id("inner").onClick(e -> { });
        Node outer = Node.column(inner).padding(10).id("outer").onScroll(e -> { });
        List<Layout.Placed> placed = Layout.lay(Node.row(outer).align(Node.Align.START), 100, 100);
        assertEquals("inner", Layout.under(placed, 15, 15, Node::pointable).node().id());
        assertEquals("outer", Layout.under(placed, 15, 15, n -> n.onScroll() != null).node().id());
        assertEquals("outer", Layout.under(placed, 5, 5, Node::pointable).node().id());
        assertNull(Layout.under(placed, 80, 80, Node::pointable));
    }

    @Test
    void keysStayStableAndIdsWin() {
        Node root = Node.column(Node.box(), Node.box().id("named"));
        List<Layout.Placed> placed = Layout.lay(root, 10, 10);
        assertEquals("0/0", placed.get(1).key());
        assertEquals("#named", placed.get(2).key());
    }

    @Test
    void coloursAndTurns() {
        assertEquals(0xFF112233, ScreenMath.argb("#112233", 7));
        assertEquals(0x80112233, ScreenMath.argb("#11223380", 7));
        assertEquals(0xFF112233, ScreenMath.argb("#123", 7));
        assertEquals(0x88112233, ScreenMath.argb("#1238", 7));
        assertEquals(7, ScreenMath.argb("#12345", 7));
        // A card's --tone: #ff0000 read as var(--tone)30 is red at 30, as in CSS.
        assertEquals(0x30ff0000, ScreenMath.argb("#ff000030", 7));
        assertEquals(0x408a51c4, ScreenMath.argb("var(--primary)40", 7));
        assertEquals(0xFFa89ab5, ScreenMath.argb("var(--letters-black)", 7));
        assertEquals(7, ScreenMath.argb("nope", 7));
        assertEquals(0, ScreenMath.argb(null, 7));
        assertEquals(-2, ScreenMath.turn(179, -179 - 4), 1e-9);
        assertEquals(10, ScreenMath.turn(-175, 195), 1e-9);
        assertEquals(0xFF808080, ScreenMath.over(0x80FFFFFF, 0xFF000000));
        assertEquals(0xFF112233, ScreenMath.over(0xFF112233, 0xFFFFFFFF));
        assertEquals(0xFF0A141E, ScreenMath.over(0x00FFFFFF, 0xFF0A141E));
    }

    @Test
    void growingBoxesGiveBackRoomWhenTheirContentOverflows() {
        Node wide = Node.row(Node.box().size(100, 10)).grow(1).id("wide");
        Node root = Node.row(Node.box().size(40, 10).id("fixed"), wide);
        List<Layout.Placed> placed = Layout.lay(root, 100, 20);
        assertEquals(40, find(placed, "fixed").w());
        assertEquals(60, find(placed, "wide").w());
    }

    @Test
    void anOverlayIsCentredOverADimmerAndLeavesTheFlow() {
        Node root = Node.column(Node.box().size(10, 10).id("under"), Node.box().size(20, 10).overlay(true).id("modal"));
        List<Layout.Placed> placed = Layout.lay(root, 100, 50);
        Layout.Placed modal = find(placed, "modal");
        assertEquals(40, modal.x());
        assertEquals(20, modal.y());
        assertEquals(Layout.OVERLAY, modal.depth());
        int start = Layout.overlayStart(placed);
        assertEquals("~dim", placed.get(start).key());
        assertEquals(100, placed.get(start).w());
    }

    @Test
    void anImageMeasuresOneLayoutPixelAPicturePixelAndRefusesHugeOnes() {
        Node image = Node.image(4, 2, new int[8]).id("img");
        Layout.Placed placed = find(Layout.lay(Node.row(image).align(Node.Align.START), 100, 50), "img");
        assertEquals(4, placed.w());
        assertEquals(2, placed.h());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Node.image(Node.MAX_IMAGE + 1, 1, new int[Node.MAX_IMAGE + 1]));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Node.image(2, 2, new int[3]));
    }
}
