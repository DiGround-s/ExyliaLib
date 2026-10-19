package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.SqlStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The storage runs writes in parallel; writes to one row must still land in
 * the order they were made, or an older save overtakes a newer one.
 */
class OrderedWritesTest {

    @Table("ordered_ratings")
    record Rating(@Id UUID id, @Column int elo) {
    }

    private static final EntityModel<Rating> MODEL = EntityModel.of(Rating.class);

    private SqlBackend backend;
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private Repository<Rating> repository;

    @BeforeEach
    void setUp() throws Exception {
        backend = SqlBackend.open(SqlSettings.memory("h2", "ordered_" + UUID.randomUUID()), "OrderedTest");
        backend.ensureTable(MODEL);
        repository = new Repository<>(new SqlStorage(backend, pending::push, warning -> { }), MODEL);
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    /** Runs whatever was queued newest first, the worst order a pool can pick. */
    private void drainNewestFirst() {
        while (!pending.isEmpty()) pending.pop().run();
    }

    @Test
    @DisplayName("writes to one row land in the order they were made, whatever order the pool runs them in")
    void sameRowWritesKeepTheirOrder() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        repository.saveAll(List.of(new Rating(id, 2366), new Rating(other, 1000)));
        repository.save(new Rating(id, 2406));
        repository.save(new Rating(other, 1010));
        drainNewestFirst();

        assertEquals(2406, readNow(id));
        assertEquals(1010, readNow(other));
    }

    private int readNow(UUID id) {
        var found = repository.find(id);
        drainNewestFirst();
        return found.join().orElseThrow().elo();
    }
}
