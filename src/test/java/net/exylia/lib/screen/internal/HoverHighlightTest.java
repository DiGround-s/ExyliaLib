package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoverHighlightTest {

    private static final long MS = 1_000_000L;

    private static Node button() {
        return Node.box().background("#202020").hover("#404040").onClick(event -> { });
    }

    /** A panel at depth 0 holding {@code child} at depth 1. */
    private static List<Layout.Placed> panel(Node panel, Node child) {
        List<Layout.Placed> out = new ArrayList<>();
        out.add(new Layout.Placed(panel, "0", 0, 0, 200, 100, 0, -1, null));
        out.add(new Layout.Placed(child, "0.0", 10, 10, 60, 20, 1, 0, null));
        return out;
    }

    private static Map<String, HoverHighlight.Lit> lone(Node node) {
        return HoverHighlight.eligible(List.of(new Layout.Placed(node, "b", 0, 0, 60, 20, 0, -1, null)), -1);
    }

    @Test
    void aSolidColourHoverIsLitAndAButtonRises() {
        HoverHighlight.Lit lit = lone(button()).get("b");
        assertNotNull(lit);
        assertTrue(lit.rises());
        assertEquals(ScreenMath.argb("#202020", 0), lit.normal());
        assertEquals(ScreenMath.argb("#404040", 0), lit.lit());
        assertEquals(2, lit.layer());
        assertEquals(1, lit.settle());
        assertFalse(lone(Node.box().background("#202020").hover("#404040")).get("b").rises());
    }

    @Test
    void seeThroughColoursAndOtherHoversAreLeftToTheTick() {
        assertTrue(lone(Node.box().background("#202020").hover("#40404080")).isEmpty());
        assertTrue(lone(Node.box().background("#20202080").hover("#404040")).isEmpty());
        assertTrue(lone(Node.box().hover("#404040")).isEmpty(), "nothing drawn plain");
        assertTrue(lone(Node.box().background("#202020").hover("#202020")).isEmpty(), "no change");
        assertTrue(lone(Node.box().background("#202020").hover("linear-gradient(#202020, #404040)")).isEmpty());
        assertTrue(lone(button().hoverTransform(Motion.Transform.scale(1.1))).isEmpty(), "it grows");
        assertTrue(lone(Node.box().background("#202020").hover("#404040").outline("#ffffff", 1)
                .hoverOutline("#ff0000")).isEmpty(), "its outline changes");
        assertTrue(lone(button().outline("#ffffff", 1)).isEmpty(), "its outline grows as it rises");
        assertFalse(lone(Node.box().background("#202020").hover("#404040").outline("#ffffff", 1)).isEmpty());
        assertTrue(lone(button().transition(new Motion.Transition(6, Motion.Ease.LINEAR, true))).isEmpty(), "a fade");
        assertEquals(3, lone(button().transition(3, Motion.Ease.LINEAR)).get("b").settle());
    }

    @Test
    void whatInsideIsMixedOverTheBoxKeepsItOnTheTick() {
        assertTrue(HoverHighlight.eligible(panel(button(), Node.box().background("#ffffff40")), -1).isEmpty());
        assertTrue(HoverHighlight.eligible(panel(button(), Node.image(1, 1, new int[]{0xFFFFFFFF})), -1).isEmpty());
        // An opaque box inside stands over the copy and is not mixed: the panel is still lit.
        Map<String, HoverHighlight.Lit> lits = HoverHighlight.eligible(panel(button(), button()), -1);
        assertEquals(2, lits.size());
        assertEquals(5, lits.get("0.0").layer());
    }

    @Test
    void aTransformAboveOrADialogOverKeepsBoxesOff() {
        Node moved = Node.box().background("#101010").transform(Motion.Transform.translate(4, 0));
        assertNull(HoverHighlight.eligible(panel(moved, button()), -1).get("0.0"));
        // A dialog from index 1: only its boxes can be pointed at.
        Map<String, HoverHighlight.Lit> lits = HoverHighlight.eligible(panel(button(), button()), 1);
        assertEquals(List.of("0.0"), List.copyOf(lits.keySet()));
    }

    private static HoverHighlight.Look look(int argb) {
        return new HoverHighlight.Look(1, 0.5, 2, argb);
    }

    @Test
    void thePoolHoldsEachLookOnceThenTwiceUpToItsCap() {
        HoverHighlight.Pool pool = new HoverHighlight.Pool();
        AtomicInteger ids = new AtomicInteger(100);
        List<HoverHighlight.Look> wanted = new ArrayList<>();
        for (int i = 0; i < 6; i++) wanted.add(look(i));
        HoverHighlight.Pool.Changes changes = pool.prepare(wanted, 400, 0, ids::getAndIncrement);
        assertEquals(HoverHighlight.CAP, changes.spawned().size());
        // Six looks once each, then the two most wanted a second time.
        assertEquals(2, pool.slots.stream().filter(s -> s.look.equals(look(0))).count());
        assertEquals(2, pool.slots.stream().filter(s -> s.look.equals(look(1))).count());
        assertEquals(1, pool.slots.stream().filter(s -> s.look.equals(look(5))).count());
        // Nothing changes when the same looks are wanted again.
        changes = pool.prepare(wanted, 400, 0, ids::getAndIncrement);
        assertTrue(changes.spawned().isEmpty() && changes.relooked().isEmpty() && changes.removed().isEmpty());
        // The budget leaves room for three.
        changes = pool.prepare(wanted, 3, 0, ids::getAndIncrement);
        assertEquals(5, changes.removed().size());
        assertEquals(3, pool.slots.size());
    }

    @Test
    void aFreeCopyTakesANewLookAndOneOverABoxKeepsItsOwn() {
        HoverHighlight.Pool pool = new HoverHighlight.Pool();
        AtomicInteger ids = new AtomicInteger();
        pool.prepare(List.of(look(1)), 2, 0, ids::getAndIncrement);
        HoverHighlight.Slot busy = pool.take(look(1), HoverHighlight.READY_NANOS);
        pool.place(busy, "b", true, "fill", 1, HoverHighlight.READY_NANOS);
        HoverHighlight.Pool.Changes changes = pool.prepare(List.of(look(2)), 2, 0, ids::getAndIncrement);
        assertTrue(changes.spawned().isEmpty());
        assertEquals(1, changes.relooked().size());
        assertEquals(look(1), busy.look);
        assertEquals(look(2), changes.relooked().get(0).look);
    }

    @Test
    void aCopyIsTakenOnlyOnceTheClientDrawsItsLook() {
        HoverHighlight.Pool pool = new HoverHighlight.Pool();
        pool.prepare(List.of(look(1)), 8, 0, () -> 7);
        assertNull(pool.take(look(1), 50 * MS));
        assertNull(pool.take(look(2), HoverHighlight.READY_NANOS));
        HoverHighlight.Slot slot = pool.take(look(1), HoverHighlight.READY_NANOS);
        assertNotNull(slot);
        pool.place(slot, "b", true, "fill", 1, HoverHighlight.READY_NANOS);
        assertSame(slot, pool.on("b"));
    }

    @Test
    void aCopyGoesOnceTheRenderDrawingItsBoxAsItLooksIsDrawn() {
        HoverHighlight.Pool pool = new HoverHighlight.Pool();
        AtomicInteger ids = new AtomicInteger();
        pool.prepare(List.of(look(1), look(2)), 8, 0, ids::getAndIncrement);
        long t = HoverHighlight.READY_NANOS;
        HoverHighlight.Slot lit = pool.take(look(1), t);
        pool.place(lit, "a", true, "lit a", 2, t);
        HoverHighlight.Slot plain = pool.take(look(2), t);
        pool.place(plain, "b", false, "plain b", 1, t);
        int seen = pool.seq;
        HoverHighlight.Slot late = pool.take(look(1), t);
        pool.place(late, "c", true, "lit c", 1, t);

        Map<String, Object> drawn = Map.of("a", "lit a", "b", "lit b", "c", "plain c");
        List<HoverHighlight.Slot> wrong = pool.decide(seen, drawn::get, t);
        // b was drawn otherwise: it goes at once. c came after the render: it waits for the next.
        assertEquals(List.of(plain), wrong);
        assertEquals(t + (HoverHighlight.PARK_TICKS + 2) * HoverHighlight.TICK_NANOS, lit.parkAt);
        assertEquals(Long.MAX_VALUE, late.parkAt);
        HoverHighlight.Pool.park(plain);

        assertTrue(pool.due(lit.parkAt - 1).isEmpty());
        assertEquals(List.of(lit), pool.due(lit.parkAt));
        // A timed copy whose box changed under it goes at once.
        assertEquals(List.of(lit), pool.decide(Integer.MIN_VALUE, Map.of("a", "moved")::get, t));
        // One no render ever decided goes after the hold.
        assertEquals(List.of(lit, late), pool.due(t + HoverHighlight.HOLD_NANOS + 1));
    }
}
