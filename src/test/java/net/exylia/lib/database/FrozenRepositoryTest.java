package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.SqlStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /exylialib wipe} relies on: a frozen repository stores nothing
 * and still reads, so the rows a plugin holds in memory cannot come back
 * through the shutdown that is meant to apply the wipe.
 */
class FrozenRepositoryTest {

    @Table("frozen_clans")
    record Clan(@Id UUID id, @Column String name) {
    }

    private static final EntityModel<Clan> MODEL = EntityModel.of(Clan.class);

    private SqlBackend backend;
    private Repository<Clan> repository;

    @BeforeEach
    void setUp() throws Exception {
        backend = SqlBackend.open(SqlSettings.memory("h2", "frozen_" + UUID.randomUUID()), "FrozenTest");
        backend.ensureTable(MODEL);
        repository = new Repository<>(new SqlStorage(backend, Runnable::run, warning -> { }), MODEL);
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    @DisplayName("a frozen repository drops writes, keeps reading, and thaws without a restart")
    void frozenWritesAreDropped() {
        Clan clan = new Clan(UUID.randomUUID(), "Exylia");
        repository.save(clan).join();
        repository.freeze();

        repository.save(new Clan(UUID.randomUUID(), "ghost")).join();
        repository.saveAll(List.of(new Clan(UUID.randomUUID(), "ghost2"))).join();
        assertEquals(false, repository.delete(clan.id()).join(), "a frozen delete reports nothing removed");

        List<Clan> stored = repository.findAll().join();
        assertEquals(List.of(clan), stored, "nothing written while frozen, nothing removed either");
        assertTrue(repository.frozen());

        repository.thaw();
        repository.save(new Clan(UUID.randomUUID(), "back")).join();
        assertEquals(2, repository.findAll().join().size(), "thawed, the write lands");
    }
}
