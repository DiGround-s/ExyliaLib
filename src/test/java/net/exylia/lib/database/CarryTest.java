package net.exylia.lib.database;

import net.exylia.fixture.CarryRecords.Arena;
import net.exylia.fixture.CarryRecords.Balance;
import net.exylia.fixture.CarryRecords.ClanStats;
import net.exylia.fixture.CarryRecords.Foreign;
import net.exylia.fixture.CarryRecords.Mine;
import net.exylia.fixture.CarryRecords.OldStats;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.Moves;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.internal.SqlStorage;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rows a table inherits before its plugin reads it: from the old file of a move, or from an older name. */
class CarryTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    @TempDir
    Path folder;

    private Plugin library;
    private SqlSettings target;

    @BeforeAll
    static void server() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        library = FakeServer.newPlugin("ExyliaLib", folder.resolve("lib").toFile());
        target = SqlSettings.memory("h2", "carry" + DATABASE.incrementAndGet());
        DatabaseRuntime.installForTests(library, Map.of("*", target));
    }

    @AfterEach
    void close() {
        Databases.releaseAll();
        Tasks.releaseAll();
        DatabaseRuntime.shutdown();
        FakeServer.reset();
    }

    /** An old H2 file holding these balances, as a plugin left it before the move. */
    private Path oldFile(String name, Balance... rows) throws Exception {
        Path file = folder.resolve(name).resolve("h2");
        EntityModel<Balance> model = EntityModel.of(Balance.class);
        SqlBackend backend = SqlBackend.open(SqlSettings.file("h2", file), "old-" + name);
        try {
            backend.ensureTable(model);
            Repository<Balance> repository = new Repository<>(new SqlStorage(backend, Runnable::run, w -> { }), model);
            for (Balance row : rows) repository.save(row).join();
        } finally {
            backend.close();
        }
        return file;
    }

    private static <T> List<T> all(Repository<T> repository) throws Exception {
        return repository.findAll().get(20, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a moved plugin reads its old file's rows on the first read, before it could seed anything")
    void moveIntoEmpty() throws Exception {
        Path old = oldFile("economy", new Balance("a", 10), new Balance("b", 20));
        Moves.add("Economy", old, Moves.Policy.MERGE);
        Plugin economy = FakeServer.newPlugin("Economy");

        Repository<Balance> balances = Databases.of(economy).repository(Balance.class);

        assertEquals(2L, balances.count().get(20, TimeUnit.SECONDS));
        assertTrue(Moves.of("Economy").done().contains("carry_balances"));
    }

    @Test
    @DisplayName("a second server adds only the keys the network lacks, or nothing when it keeps the network's")
    void moveIntoOccupied() throws Exception {
        Plugin first = FakeServer.newPlugin("First");
        Databases.of(first).repository(Balance.class).save(new Balance("a", 99)).get(20, TimeUnit.SECONDS);

        Moves.add("Merge", oldFile("merge", new Balance("a", 1), new Balance("b", 2)), Moves.Policy.MERGE);
        Repository<Balance> merged = Databases.of(FakeServer.newPlugin("Merge")).repository(Balance.class);
        assertEquals(List.of(new Balance("a", 99), new Balance("b", 2)),
                all(merged).stream().sorted(java.util.Comparator.comparing(Balance::id)).toList());

        Moves.add("Keep", oldFile("keep", new Balance("c", 3)), Moves.Policy.NETWORK);
        Repository<Balance> kept = Databases.of(FakeServer.newPlugin("Keep")).repository(Balance.class);
        assertEquals(2, all(kept).size());
    }

    @Test
    @DisplayName("a renamed table takes the rows of its old name, never those of another plugin's table under it")
    void previousName() throws Exception {
        Plugin clans = FakeServer.newPlugin("Clans");
        Databases.of(clans).repository(OldStats.class).save(new OldStats("p", "red", 4)).get(20, TimeUnit.SECONDS);
        Plugin survival = FakeServer.newPlugin("Survival");
        Databases.of(survival).repository(Foreign.class).save(new Foreign("p", 7)).get(20, TimeUnit.SECONDS);

        assertEquals(List.of(new ClanStats("p", "red", 4)), all(Databases.of(clans).repository(ClanStats.class)));
        assertEquals(List.of(), all(Databases.of(clans).repository(Mine.class)));
    }

    @Test
    @DisplayName("a server scope asked to inherit starts from the plain table; one that did not starts empty")
    void scopedInherit() throws Exception {
        Plugin ffa = FakeServer.newPlugin("FFA");
        Databases.of(ffa).repository(Arena.class).save(new Arena("main", "world")).get(20, TimeUnit.SECONDS);

        assertEquals(List.of(new Arena("main", "world")),
                all(Databases.of(ffa).repository(Arena.class, "survival_1", true)));
        assertEquals(List.of(), all(Databases.of(ffa).repository(Arena.class, "boxpvp", false)));
    }
}
