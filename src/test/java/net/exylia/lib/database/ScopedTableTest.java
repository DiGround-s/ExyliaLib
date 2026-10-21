package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.SqlStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One record in two scopes is two tables: rows and statements never cross. */
class ScopedTableTest {

    @Table("scoped_listings")
    @Index(name = "idx_scoped_by_price", columns = {"price"})
    record Listing(@Id UUID id, @Column int price) {
    }

    private static final EntityModel<Listing> PLAIN = EntityModel.of(Listing.class);
    private static final EntityModel<Listing> SURVIVAL = PLAIN.scoped("survival");

    private SqlBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        backend = SqlBackend.open(SqlSettings.memory("h2", "scoped_" + UUID.randomUUID()), "ScopedTest");
        backend.ensureTable(PLAIN);
        backend.ensureTable(SURVIVAL);
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    @DisplayName("a scope is its own table with its own index names")
    void ownTable() {
        assertEquals("survival_scoped_listings", SURVIVAL.table());
        assertNotEquals(PLAIN.indexes().get(0).name(), SURVIVAL.indexes().get(0).name());
        assertNotEquals(PLAIN.key(), SURVIVAL.key());
    }

    @Test
    @DisplayName("a row saved in one scope is not in the other")
    void rowsStayInTheirScope() {
        Repository<Listing> plain = new Repository<>(new SqlStorage(backend, Runnable::run, warning -> { }), PLAIN);
        Repository<Listing> survival = new Repository<>(new SqlStorage(backend, Runnable::run, warning -> { }), SURVIVAL);
        UUID id = UUID.randomUUID();
        survival.save(new Listing(id, 10)).join();
        plain.save(new Listing(UUID.randomUUID(), 5)).join();

        assertTrue(plain.find(id).join().isEmpty());
        assertEquals(10, survival.find(id).join().orElseThrow().price());
        assertEquals(1, survival.findAll().join().size());
        assertEquals(1, plain.findAll().join().size());
    }

    @Test
    @DisplayName("a scope is a safe identifier or refused")
    void scopeNames() {
        assertEquals("", PluginDatabase.scope("  "));
        assertEquals("survival_1", PluginDatabase.scope("Survival_1"));
        assertEquals("survival_1", PluginDatabase.scope("survival-1"));
        assertThrows(IllegalArgumentException.class, () -> PluginDatabase.scope("a;drop"));
    }
}
