package net.exylia.lib.database.internal;

import net.exylia.lib.database.DatabaseException;
import net.exylia.lib.database.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the async adapter that do not need a server.
 *
 * <p>Most of {@link MongoStorage} is a round trip wrapped in a future, and a
 * future around a call to a database nobody is running proves nothing. Two
 * things in it are real logic, and both are silent when wrong:
 *
 * <ul>
 *   <li>the sort direction flip, where {@link Query.Sort} counts ascending and
 *       {@link Dialect.Sort} counts descending. Getting it backwards compiles,
 *       runs, and hands a leaderboard back with the worst player on top.</li>
 *   <li>the scheduling rejection path, which is what a plugin hits during
 *       shutdown — exactly when it is flushing player data — and which must
 *       arrive through the future rather than as a throw from the caller's own
 *       thread.</li>
 * </ul>
 */
class MongoStorageTest {

    @Test
    @DisplayName("ascending becomes not-descending, and descending becomes descending")
    void sortDirectionsAreFlipped() {
        List<Dialect.Sort> sorts = MongoStorage.sorts(List.of(
                new Query.Sort("elo", false),
                new Query.Sort("clan", true)));

        assertEquals(2, sorts.size());
        // Query counts ascending because that reads well at a call site;
        // Dialect counts descending because that is the keyword a statement
        // emits. They are opposites, not synonyms.
        assertEquals("elo", sorts.get(0).column());
        assertTrue(sorts.get(0).descending());
        assertEquals("clan", sorts.get(1).column());
        assertFalse(sorts.get(1).descending());
    }

    @Test
    @DisplayName("no order asked for allocates nothing")
    void emptySortIsEmpty() {
        assertEquals(List.of(), MongoStorage.sorts(List.of()));
    }

    @Test
    @DisplayName("an executor that refuses work fails the future, it does not throw")
    void rejectedSchedulingArrivesThroughTheFuture() {
        // A plugin's scheduler refuses work once the plugin is disabled. A
        // caller that handles every failure through the future would otherwise
        // be killed by the one failure that arrived by another route.
        MongoStorage storage = new MongoStorage(null, task -> {
            throw new java.util.concurrent.RejectedExecutionException("disabled");
        }, warning -> {
        });

        var future = storage.count(EntityModel.of(Stats.class), List.of(), List.of());

        assertTrue(future.isCompletedExceptionally());
        DatabaseException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DatabaseException.class,
                () -> {
                    try {
                        future.join();
                    } catch (java.util.concurrent.CompletionException wrapped) {
                        throw wrapped.getCause();
                    }
                });
        // The message has to name the collection: a rejection with no context
        // tells the person reading the console nothing about which plugin.
        assertTrue(failure.getMessage().contains("player_stats"));
        assertTrue(failure.getMessage().contains("disabled"));
    }

    @net.exylia.lib.database.Table("player_stats")
    record Stats(@net.exylia.lib.database.Id java.util.UUID uuid,
                 @net.exylia.lib.database.Column int elo) {
    }
}
