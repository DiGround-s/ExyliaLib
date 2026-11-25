package net.exylia.lib.tablist.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TabDiffTest {

    @Test
    void sendsOnlyWhenTheValueChanges() {
        TabDiff diff = new TabDiff();
        assertFalse(diff.changed(TabDiff.HEADER, null), "nothing set, nothing sent");
        assertTrue(diff.changed(TabDiff.HEADER, "a"));
        assertFalse(diff.changed(TabDiff.HEADER, "a"));
        assertTrue(diff.changed(TabDiff.HEADER, "b"));
        assertTrue(diff.changed(TabDiff.HEADER, null), "a dropped field is a change back to vanilla");
        assertNull(diff.last(TabDiff.ORDER));
    }

    @Test
    void slotsAreIndependent() {
        TabDiff diff = new TabDiff();
        assertTrue(diff.changed(TabDiff.ORDER, 5));
        assertTrue(diff.changed(TabDiff.NAME, "5"));
        assertFalse(diff.changed(TabDiff.ORDER, 5));
        assertEquals(5, diff.last(TabDiff.ORDER));
    }

    @Test
    void aMovingLineIsRebuiltEachFrameAStillOneIsNot() {
        java.util.concurrent.atomic.AtomicLong millis = new java.util.concurrent.atomic.AtomicLong();
        net.exylia.lib.text.internal.TextEffects.clock(millis::get);
        try {
            TabDiff diff = new TabDiff();
            String text = "<marquee:3,speed=1>ABCDEFG<nl>players: 5";
            var first = diff.build(TabDiff.HEADER, text, TabRuntime::parse);
            assertTrue(diff.moving());
            assertTrue(diff.show(TabDiff.HEADER, first));
            millis.addAndGet(1000);
            var second = diff.build(TabDiff.HEADER, text, TabRuntime::parse);
            assertNotEquals(first, second, "same text, next frame");
            assertTrue(diff.show(TabDiff.HEADER, second));

            TabDiff still = new TabDiff();
            var a = still.build(TabDiff.FOOTER, "players: 5", TabRuntime::parse);
            millis.addAndGet(1000);
            assertSame(a, still.build(TabDiff.FOOTER, "players: 5", TabRuntime::parse));
            assertFalse(still.moving());
        } finally {
            net.exylia.lib.text.internal.TextEffects.clock(null);
        }
    }
}
