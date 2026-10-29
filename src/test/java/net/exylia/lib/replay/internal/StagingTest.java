package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.Replay;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;

class StagingTest {

    @Test
    void aRecordingThatDoesNotSayWhereItWasCannotBeWatchedLive() {
        // No scenes: one at the origin of no world, which is nowhere on this server.
        Replay replay = new Replay(UUID.randomUUID(), 1L, 1, List.of(), List.of(), List.of(), List.of(), null);
        assertNull(Staging.live("Test", replay));
    }
}
