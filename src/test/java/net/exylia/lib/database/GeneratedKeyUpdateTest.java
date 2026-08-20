package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changing a row whose key the database handed out.
 *
 * <p>A generated key is a placeholder only until the row exists. Once it has
 * been stored the record carries the key the database chose, and there is
 * nothing ambiguous about which row it means — refusing to write it leaves a
 * table that can be inserted into and never changed again.
 */
class GeneratedKeyUpdateTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    @Table("shield_design_library")
    record Design(
            @Id(generated = true) long id,
            @Column("owner_uuid") String ownerUuid,
            @Column("design_json") String json,
            @Column int uses) {

        Design withJson(String replacement) {
            return new Design(id, ownerUuid, replacement, uses);
        }

        Design withOneMoreUse() {
            return new Design(id, ownerUuid, json, uses + 1);
        }
    }

    private Plugin plugin;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Shields");
        Databases.installForTests(plugin,
                SqlSettings.memory("h2", "genupdate" + DATABASE.incrementAndGet()));
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Repository<Design> repository() {
        return Databases.of(plugin).repository(Design.class);
    }

    private Design stored() {
        Repository<Design> repository = repository();
        long id = await(repository.insert(new Design(0L, UUID.randomUUID().toString(), "{}", 0)));
        return await(repository.find(id)).orElseThrow();
    }

    @Test
    @DisplayName("a row that came back from the database can be written again")
    void aStoredRowCanBeUpdated() {
        // Exactly what a plugin does to change a published design: read it,
        // change what it holds, write it back. The key is the one the database
        // chose, so there is no question which row is meant.
        Repository<Design> repository = repository();
        Design published = stored();

        assertDoesNotThrow(() -> await(repository.update(published.withJson("{\"base\":\"RED\"}"))),
                "a row read from the database can be changed");

        assertEquals("{\"base\":\"RED\"}",
                await(repository.find(published.id())).orElseThrow().json());
    }

    @Test
    @DisplayName("an update changes the row rather than adding another")
    void updateDoesNotInsert() {
        Repository<Design> repository = repository();
        Design published = stored();

        await(repository.update(published.withOneMoreUse()));

        assertEquals(1L, await(repository.count()), "still one row");
        assertEquals(1, await(repository.find(published.id())).orElseThrow().uses());
    }

    @Test
    @DisplayName("a record still holding the placeholder key is refused")
    void placeholderKeyIsStillRefused() {
        // The reason the rule exists: this record has never been stored, so its
        // key is a zero that belongs to whichever row happens to hold it.
        Repository<Design> repository = repository();

        assertThrows(IllegalArgumentException.class,
                () -> repository.update(new Design(0L, UUID.randomUUID().toString(), "{}", 0)),
                "a placeholder key names no row");
    }

    @Test
    @DisplayName("save still points a generated key at insert")
    void saveStillRefusesGeneratedKeys() {
        // Unchanged: save is an upsert, and an upsert on a key the database has
        // not handed out yet is the overwrite this rule was written to stop.
        Repository<Design> repository = repository();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> repository.save(new Design(0L, UUID.randomUUID().toString(), "{}", 0)));
        assertTrue(refused.getMessage().contains("insert()"), refused.getMessage());
    }

    @Test
    @DisplayName("save names update as the way to change a stored row")
    void saveMessagePointsAtUpdate() {
        // A stored row is the case that brought somebody here: the message has
        // to name the call that does work, or it sends them to insert() and
        // they publish a duplicate.
        Repository<Design> repository = repository();
        Design published = stored();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> repository.save(published.withOneMoreUse()));
        assertTrue(refused.getMessage().contains("update()"), refused.getMessage());
    }
}
