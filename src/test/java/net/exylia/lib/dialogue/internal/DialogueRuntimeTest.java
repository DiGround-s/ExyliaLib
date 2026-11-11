package net.exylia.lib.dialogue.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueRuntimeTest {

    @Test
    void wheelMovesOneAnswerAndWraps() {
        assertEquals(1, DialogueRuntime.scrolled(0, 3, 4, 5));
        assertEquals(0, DialogueRuntime.scrolled(2, 3, 4, 5));
        assertEquals(2, DialogueRuntime.scrolled(0, 3, 4, 3));
        assertEquals(1, DialogueRuntime.scrolled(0, 3, 8, 0));
        assertEquals(2, DialogueRuntime.scrolled(0, 3, 0, 8));
    }

    @Test
    void numberKeyPicksItsAnswerAndIgnoresMissingOnes() {
        assertEquals(2, DialogueRuntime.scrolled(0, 3, 6, 2));
        assertEquals(1, DialogueRuntime.scrolled(1, 3, 0, 6));
    }
}
