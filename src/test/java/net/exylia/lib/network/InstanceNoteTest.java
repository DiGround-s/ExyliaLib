package net.exylia.lib.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The handover notes on the arrivals channel, and the one that withdraws a failed move's. */
class InstanceNoteTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("A note names the player and keeps every pipe of its text; a withdrawal is told apart")
    void parse() {
        PluginInstances.Note note = PluginInstances.Note.parse(PLAYER + "|castle|east");
        assertEquals(PLAYER, note.player());
        assertEquals("castle|east", note.text());
        assertFalse(note.withdrawn());

        PluginInstances.Note withdrawn = PluginInstances.Note.parse(PluginInstances.WITHDRAWN + PLAYER + "|castle");
        assertTrue(withdrawn.withdrawn());
        assertEquals(PLAYER, withdrawn.player());
        assertEquals("castle", withdrawn.text());
    }

    @Test
    @DisplayName("An unreadable note is ignored")
    void unreadable() {
        assertNull(PluginInstances.Note.parse("nobody|castle"));
        assertNull(PluginInstances.Note.parse("|castle"));
        assertNull(PluginInstances.Note.parse(PluginInstances.WITHDRAWN));
    }
}
