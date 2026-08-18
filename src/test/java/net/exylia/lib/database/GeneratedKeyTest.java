package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys the database hands out, against a real engine.
 *
 * <p>H2 in memory, not a mock. The whole point of a generated key is that the
 * engine picks it, so a mock would only assert that the library returns the
 * number the test itself made up — which is the shape of the bug this guards
 * against: reading the key back with a second statement gives a plausible
 * number that belongs to whoever inserted last.
 */
class GeneratedKeyTest {

    private static final long TIMEOUT_SECONDS = 15L;
    private static final AtomicInteger DATABASE = new AtomicInteger();

    @Table("shield_design_library")
    record Design(
            @Id(generated = true) long id,
            @Column("owner_uuid") UUID owner,
            @Column("design_json") String json,
            @Column int uses) {
    }

    @Table("design_uses")
    record Use(
            @Id(generated = true) int id,
            @Column("design_id") long designId) {
    }

    @Table("player_stats")
    record Owned(@Id UUID uuid, @Column int elo) {
    }

    @Table("bad")
    record BadKey(@Id(generated = true) UUID id, @Column int value) {
    }

    private SqlBackend backend;
    private EntityModel<Design> designs;
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
        int id = DATABASE.incrementAndGet();
        backend = SqlBackend.open(SqlSettings.memory("h2", "keys" + id), "tests");
        designs = EntityModel.of(Design.class);
        Databases.installForTests(plugin, SqlSettings.memory("h2", "keysfacade" + id));
    }

    @AfterEach
    void close() {
        if (backend != null) {
            backend.close();
        }
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for a database operation", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError("A database operation did not complete", failure);
        }
    }

    private static Design design(String json) {
        return new Design(0L, UUID.randomUUID(), json, 0);
    }

    // ------------------------------------------------------------- the basics

    @Test
    @DisplayName("the engine hands out the key, and the row is readable under it")
    void insertReturnsTheKeyTheRowIsStoredUnder() throws SQLException {
        backend.ensureTable(designs);

        long id = backend.insert(designs, design("{\"a\":1}"));

        assertTrue(id > 0L, "a generated key is a real number, not the placeholder");
        Design stored = backend.find(designs, id);
        assertNotNull(stored, "the key that came back has to address the row that was written");
        assertEquals("{\"a\":1}", stored.json());
        assertEquals(id, stored.id(), "the row carries the key the engine chose");
    }

    @Test
    @DisplayName("the placeholder in the record is never what gets stored")
    void placeholderIsNotStored() throws SQLException {
        backend.ensureTable(designs);

        // Every insert passes a zero. If the placeholder were written, the
        // second would collide with the first on the primary key.
        long first = backend.insert(designs, design("one"));
        long second = backend.insert(designs, design("two"));

        assertNotEquals(first, second);
        assertEquals(2L, backend.count(designs, List.of(), List.of()));
    }

    @Test
    @DisplayName("a hundred inserts produce a hundred distinct keys")
    void keysAreNeverReused() throws SQLException {
        backend.ensureTable(designs);

        Set<Long> keys = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            keys.add(backend.insert(designs, design("row" + index)));
        }

        // The count is the assertion: a duplicate key silently overwrites a row,
        // and the naive implementations of this — MAX(id) + 1, count() + 1 —
        // both produce duplicates the moment anything is deleted.
        assertEquals(100, keys.size(), "every insert gets a key of its own");
    }

    @Test
    @DisplayName("a key stays taken after its row is deleted")
    void deletedKeysAreNotHandedOutAgain() throws SQLException {
        backend.ensureTable(designs);

        long first = backend.insert(designs, design("one"));
        backend.delete(designs, first);
        long second = backend.insert(designs, design("two"));

        // The damage a reused key does is not the duplicate: anything that
        // stored the old id — a slot, a use record — now points at another
        // player's row.
        assertNotEquals(first, second, "a counter moves forward, it does not fill gaps");
    }

    @Test
    @DisplayName("an int key is narrowed to int, not left as a long")
    void narrowsTheKeyToTheDeclaredType() throws SQLException {
        EntityModel<Use> uses = EntityModel.of(Use.class);
        backend.ensureTable(uses);

        long key = backend.insert(uses, new Use(0, 7L));

        // Rebuilt, not re-read. A database counter is always wide, and handing
        // a Long to a constructor that declares int fails the invokeExact with
        // a WrongMethodTypeException — which only this path sees, because a
        // value read back from the engine has already been coerced.
        Use rebuilt = uses.withId(new Use(0, 7L), key);
        assertEquals((int) key, rebuilt.id());
        assertEquals(7L, rebuilt.designId(), "the other columns survive the rebuild");

        Use stored = backend.find(uses, key);
        assertNotNull(stored);
        assertEquals((int) key, stored.id());
    }

    @Test
    @DisplayName("every other column is written exactly as it was")
    void otherColumnsSurvive() throws SQLException {
        backend.ensureTable(designs);
        UUID owner = UUID.randomUUID();

        long id = backend.insert(designs, new Design(0L, owner, "{}", 42));

        Design stored = backend.find(designs, id);
        assertNotNull(stored);
        // The rebuild around a generated key must not route the other values
        // through their codec twice: a UUID encoded to text and handed back to
        // the constructor as a String would fail it.
        assertEquals(owner, stored.owner());
        assertEquals(42, stored.uses());
    }

    @Test
    @DisplayName("a row inserted with a generated key is updated with save")
    void savingAnInsertedRowUpdatesIt() throws SQLException {
        backend.ensureTable(designs);
        long id = backend.insert(designs, design("before"));

        Design stored = backend.find(designs, id);
        assertNotNull(stored);
        backend.save(designs, new Design(stored.id(), stored.owner(), "after", 1));

        assertEquals("after", backend.find(designs, id).json());
        assertEquals(1L, backend.count(designs, List.of(), List.of()),
                "an update of an existing key is not a second row");
    }

    @Test
    @DisplayName("the keys ascend, so key order is insertion order")
    void keysAscend() throws SQLException {
        backend.ensureTable(designs);
        List<Long> inserted = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            inserted.add(backend.insert(designs, design("row" + index)));
        }

        List<Design> ordered = backend.select(designs, List.of(), List.of(),
                List.of(new Dialect.Sort("id", false)), 0, 0);

        assertEquals(inserted, ordered.stream().map(Design::id).toList());
    }

    // ------------------------------------------------------------ the facade

    @Test
    @DisplayName("a repository insert answers the key, and insertReturning the record")
    void repositoryHandsBackTheKey() {
        Repository<Design> repository = Databases.of(plugin).repository(Design.class);

        long id = await(repository.insert(design("one")));
        Design stored = await(repository.insertReturning(design("two")));

        assertTrue(id > 0L);
        assertNotEquals(0L, stored.id(), "the record comes back carrying its key");
        assertNotEquals(id, stored.id());
        assertEquals("two", stored.json());
        // The record that came back is the one to keep: reading by its key has
        // to find the row it describes.
        assertEquals("two", await(repository.find(stored.id())).orElseThrow().json());
    }

    // -------------------------------------------------------------- refusals

    @Test
    @DisplayName("a generated key has to be a whole number")
    void refusesAGeneratedKeyThatIsNotANumber() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(BadKey.class));
        // A UUID would have to be invented by the library rather than the
        // database, and two servers would each invent their own.
        assertTrue(failure.getMessage().contains("generated key"), failure.getMessage());
    }

    @Test
    @DisplayName("insert refuses a record that brings its own key")
    void insertRefusesANaturalKey() {
        Repository<Owned> repository = Databases.of(plugin).repository(Owned.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.insert(new Owned(UUID.randomUUID(), 1200)));
        assertTrue(failure.getMessage().contains("save()"), failure.getMessage());
    }

    @Test
    @DisplayName("save refuses a record whose key the database hands out")
    void saveRefusesAGeneratedKey() {
        Repository<Design> repository = Databases.of(plugin).repository(Design.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.save(design("{}")));
        // A save on a placeholder zero would merge onto whichever row holds
        // id 0, or create one and overwrite it on the next insert.
        assertTrue(failure.getMessage().contains("insert()"), failure.getMessage());
    }

    @Test
    @DisplayName("saveAll refuses a batch of generated keys rather than losing them")
    void saveAllRefusesGeneratedKeys() {
        Repository<Design> repository = Databases.of(plugin).repository(Design.class);

        // A batch cannot answer with the keys it was given, so a caller would
        // have stored a hundred rows nothing can refer to.
        assertThrows(IllegalArgumentException.class,
                () -> repository.saveAll(List.of(design("a"), design("b"))));
    }
}
