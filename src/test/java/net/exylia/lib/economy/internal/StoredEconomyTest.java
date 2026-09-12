package net.exylia.lib.economy.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.EconomySettings;
import net.exylia.lib.economy.LedgerEntry;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stored currencies against a real database.
 *
 * <p>H2 in memory and a real async executor, so the ownership rules — a
 * loaded player is written through, an absent one is queued and folded in on
 * load — run the way they run on a server.
 */
class StoredEconomyTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    @TempDir
    Path folder;

    private Plugin plugin;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        CurrencyRegistry.clearForTests();
        CurrencyRegistry.apply(new EconomySettings("coins", List.of(), 500L));
        BalanceCache.apply(new EconomySettings());
        plugin = FakeServer.newPlugin("ExyliaLib", folder.toFile());
        TestDatabases.memory(plugin, "economy" + DATABASE.incrementAndGet());
        StoredEconomy.init(plugin);
    }

    @AfterEach
    void tearDown() {
        StoredEconomy.shutdown();
        Databases.releaseAll();
        Tasks.releaseAll();
        CurrencyRegistry.clearForTests();
        BalanceCache.invalidateAll();
        FakeServer.reset();
    }

    private StoredEconomy economy() {
        StoredEconomy economy = StoredEconomy.get();
        assertNotNull(economy);
        return economy;
    }

    private StoredCurrency coins() {
        StoredCurrency coins = economy().currency("coins");
        assertNotNull(coins);
        return coins;
    }

    /** Loads a player the way a join does, and waits for the read to land. */
    private void join(UUID player, String name) throws Exception {
        economy().load(player, name);
        long deadline = System.currentTimeMillis() + 5000;
        while (economy().currencies().stream().anyMatch(currency -> !currency.isLoaded(player))) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never loaded");
            Thread.sleep(10);
        }
    }

    private void settle() throws Exception {
        Thread.sleep(150);
    }

    @Test
    @DisplayName("the file's currencies are registered and answer to the facade")
    void registered() {
        assertTrue(Economy.currencies().contains("coins"));
        assertTrue(Economy.currencies().contains("gems"));
        assertTrue(Economy.currencies().contains("xp_levels"));
        assertEquals("Coins", Economy.info("coins").namePlural());
        assertEquals("$", Economy.info("vault").symbol());
    }

    @Test
    @DisplayName("a loaded player is charged and paid in memory and written through")
    void loadedPlayer() throws Exception {
        join(alice, "Alice");
        Economy.CurrencyView view = Economy.of("coins");

        assertTrue(view.deposit(alice, new BigDecimal("100"), Transaction.of("test:give")).isSuccess());
        assertEquals(new BigDecimal("100"), view.balance(alice));
        EconomyResponse taken = view.withdraw(alice, new BigDecimal("30"), Transaction.of("test:take"));
        assertTrue(taken.isSuccess());
        assertEquals(new BigDecimal("70"), taken.balance());

        EconomyResponse tooMuch = view.withdraw(alice, new BigDecimal("500"));
        assertEquals(EconomyResponse.Type.INSUFFICIENT_FUNDS, tooMuch.type());
        assertEquals(new BigDecimal("70"), view.balance(alice));

        settle();
        BalanceRow row = economy().balances().find(BalanceRow.id(alice, "coins")).get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(0, new BigDecimal("70").compareTo(row.amount()));
        assertEquals("Alice", row.name());
    }

    @Test
    @DisplayName("a whole-number currency drops the fraction rather than rounding it up")
    void integerCurrency() throws Exception {
        join(alice, "Alice");
        Economy.of("coins").deposit(alice, new BigDecimal("10.99"));
        assertEquals(0, new BigDecimal("10").compareTo(Economy.of("coins").balance(alice)));
    }

    @Test
    @DisplayName("an absent player is queued, and the queue is folded in on their next load")
    void absentPlayer() throws Exception {
        Economy.CurrencyView view = Economy.of("coins");
        assertFalse(coins().isLoaded(bob));

        assertTrue(view.deposit(bob, new BigDecimal("40"), Transaction.of("market:sale")).isSuccess());
        assertTrue(view.deposit(bob, new BigDecimal("2"), Transaction.of("market:sale")).isSuccess());
        settle();
        assertEquals(2, economy().pendingRows().where("player", bob.toString()).count().get(5, TimeUnit.SECONDS));

        join(bob, "Bob");
        long deadline = System.currentTimeMillis() + 5000;
        while (view.balance(bob).compareTo(new BigDecimal("42")) != 0) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("pending never applied: " + view.balance(bob));
            Thread.sleep(10);
        }
        settle();
        assertEquals(0, economy().pendingRows().where("player", bob.toString()).count().get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("every applied change is a line in the ledger, with its reason")
    void ledger() throws Exception {
        join(alice, "Alice");
        Economy.of("coins").deposit(alice, new BigDecimal("50"), Transaction.of("quest:daily"));
        Economy.of("coins").withdraw(alice, new BigDecimal("20"), Transaction.of("shop:buy").by(bob));
        settle();

        List<LedgerEntry> lines = Economy.history("coins", alice, 10).get(5, TimeUnit.SECONDS);
        assertEquals(2, lines.size());
        assertEquals("shop:buy", lines.get(0).reason());
        assertEquals(bob, lines.get(0).initiator());
        assertEquals(0, new BigDecimal("-20").compareTo(lines.get(0).delta()));
        assertEquals(0, new BigDecimal("30").compareTo(lines.get(0).balanceAfter()));
        assertEquals("quest:daily", lines.get(1).reason());
    }

    @Test
    @DisplayName("exchanging follows the file's rate and refunds on a failed deposit")
    void exchange() throws Exception {
        join(alice, "Alice");
        Economy.of("coins").deposit(alice, new BigDecimal("1000"));

        EconomyResponse swapped = Economy.exchange(alice, "coins", "gems", new BigDecimal("500"));
        assertTrue(swapped.isSuccess(), () -> String.valueOf(swapped.message()));
        assertEquals(0, new BigDecimal("5").compareTo(swapped.amount()));
        assertEquals(0, new BigDecimal("500").compareTo(Economy.of("coins").balance(alice)));
        assertEquals(0, new BigDecimal("5").compareTo(Economy.of("gems").balance(alice)));

        EconomyResponse tooSmall = Economy.exchange(alice, "coins", "gems", new BigDecimal("10"));
        assertFalse(tooSmall.isSuccess());
        assertEquals(0, new BigDecimal("500").compareTo(Economy.of("coins").balance(alice)));
    }

    @Test
    @DisplayName("the leaderboard is what the table says, richest first")
    void leaderboard() throws Exception {
        join(alice, "Alice");
        join(bob, "Bob");
        Economy.of("coins").deposit(alice, new BigDecimal("10"));
        Economy.of("coins").deposit(bob, new BigDecimal("90"));
        settle();

        Economy.top("coins", 10);
        settle();
        List<Economy.TopEntry> top = Economy.top("coins", 10);
        assertEquals(2, top.size());
        assertEquals(bob, top.get(0).player());
        assertEquals(1, top.get(0).position());
        assertEquals(0, new BigDecimal("90").compareTo(top.get(0).amount()));
    }

    @Test
    @DisplayName("amounts are read the way players type them")
    void amounts() {
        assertEquals(0, new BigDecimal("2500").compareTo(Economy.parseAmount("2.5k")));
        assertEquals(0, new BigDecimal("1000000").compareTo(Economy.parseAmount("1m")));
        assertEquals(0, new BigDecimal("42").compareTo(Economy.parseAmount("42")));
        assertEquals(null, Economy.parseAmount("-5"));
        assertEquals(null, Economy.parseAmount("lots"));
    }
}
