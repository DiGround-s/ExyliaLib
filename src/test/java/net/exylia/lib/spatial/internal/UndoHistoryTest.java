package net.exylia.lib.spatial.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoHistoryTest {

    @Test
    void aClickThatChangedNothingIsNoStep() {
        UndoHistory<String> history = new UndoHistory<>(8);
        history.record("a", "a");
        assertTrue(history.isEmpty());
        assertNull(history.pop());
    }

    @Test
    void stepsComeBackNewestFirst() {
        UndoHistory<String> history = new UndoHistory<>(8);
        history.record("a", "b");
        history.record("b", "c");
        assertEquals("b", history.pop());
        assertEquals("a", history.pop());
        assertNull(history.pop());
    }

    @Test
    void theOldestStepGoesWhenTheHistoryIsFull() {
        UndoHistory<Integer> history = new UndoHistory<>(2);
        history.record(1, 2);
        history.record(2, 3);
        history.record(3, 4);
        assertEquals(2, history.size());
        assertEquals(3, history.pop());
        assertEquals(2, history.pop());
        assertTrue(history.isEmpty());
    }

    @Test
    void draftsSnapshotAndRestore() {
        Drafts drafts = new Drafts();
        Map<String, ?> saved = Map.of("design", "saved");
        drafts.put("design", "one", saved);
        Map<String, Object> before = drafts.snapshot();
        drafts.put("design", "two", saved);
        assertEquals("two", drafts.get("design", saved));
        drafts.restore(before);
        assertEquals("one", drafts.get("design", saved), "back to the step before");
        drafts.put("design", "three", saved);
        assertEquals("one", before.get("design"), "a snapshot is a copy, not a view");
    }
}
