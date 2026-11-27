package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hold to confirm's label swap and bar growth: pure, no server. */
class HoldToConfirmTest {

    private static String plain(Node node) {
        return PlainTextComponentSerializer.plainText().serialize(node.text());
    }

    @Test
    void theLabelReplacesTheWordsOfABoxThatIsItselfText() {
        // <button>⎵ Hold to rebirth</button> builds one text node: the label must swap its words, not a child's.
        Node button = Node.text("Hold to rebirth").id("go").hold(24, Node.text("Keep holding"), null);
        Node root = Node.column(Node.box().size(10, 10), button);
        LiveScreen.holdLabel(root, "#go", 200, 100);
        assertEquals("Keep holding", plain(root.children().get(1)));
        assertEquals(24, root.children().get(1).holdTicks());
    }

    @Test
    void theLabelReplacesTheFirstTextInsideABox() {
        Node button = Node.row(Node.text("Go"), Node.text("now")).id("go").hold(24, Node.text("Keep"), null);
        LiveScreen.holdLabel(Node.column(button), "#go", 200, 100);
        assertEquals("Keep", plain(button.children().get(0)));
        assertEquals("now", plain(button.children().get(1)));
    }

    @Test
    void theBarGrowsLinearlyFromItsLeftEdge() {
        double w = 100;
        Motion.Keyframes grow = LiveScreen.holdGrowth(w, 24);
        assertEquals(24, grow.ticks());
        for (int tick : new int[]{0, 6, 12, 24}) {
            Motion.Transform t = grow.at(tick);
            double width = w * t.squeeze();
            // Squeezed about its centre and moved back: the left edge stays at 0.
            assertEquals(0, w / 2 - width / 2 + t.x(), 1e-9, "left edge at tick " + tick);
            assertEquals(tick == 0 ? 1 : w * tick / 24.0, width, tick == 0 ? 1e-9 : 1 - 1 / w + 1e-9, "width at tick " + tick);
        }
        assertEquals(Motion.Transform.NONE, grow.at(30));
    }
}
