package net.exylia.lib.economy.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.economy.EconomySettings;
import net.exylia.lib.task.Tasks;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The library as the server's Vault economy: beneath any economy plugin, on
 * top only when forced, and the {@code vault} currency follows whoever serves.
 */
class VaultBridgeTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    @TempDir
    Path folder;

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        CurrencyRegistry.clearForTests();
        CurrencyRegistry.apply(new EconomySettings());
        BalanceCache.apply(new EconomySettings());
        plugin = FakeServer.newPlugin("ExyliaLib", folder.toFile());
        FakeServer.plugins(plugin, FakeServer.newPlugin("Vault"));
        TestDatabases.memory(plugin, "vault" + DATABASE.incrementAndGet());
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

    private void start(boolean force) throws Exception {
        Files.writeString(folder.resolve(CurrencyFile.FILE), """
                stored:
                  shards:
                    name: Shard
                    plural: Shards
                vault:
                  provide: shards
                  force: %s
                """.formatted(force));
        StoredEconomy.init(plugin);
    }

    private static Object served() {
        return Bukkit.getServicesManager().getRegistration(Economy.class).getProvider();
    }

    @Test
    @DisplayName("with no economy plugin, the library is Vault's economy and the default serves")
    void alone() throws Exception {
        start(false);

        assertEquals("ExyliaLib:shards", ((Economy) served()).getName());
        assertEquals("vault", net.exylia.lib.economy.Economy.info(null).id());
    }

    @Test
    @DisplayName("an economy plugin that loads later serves, and the vault currency follows it")
    void pluginLoadingLaterTakesOver() throws Exception {
        start(false);
        Economy essentials = bank(42);
        Bukkit.getServicesManager().register(Economy.class, essentials,
                FakeServer.newPlugin("Essentials"), ServicePriority.Normal);

        assertSame(essentials, served());
        assertEquals(2, Bukkit.getServicesManager().getRegistrations(Economy.class).size());
        assertEquals(0, new BigDecimal("42").compareTo(
                net.exylia.lib.economy.Economy.of("vault").balance(UUID.randomUUID())));
    }

    @Test
    @DisplayName("force puts the library above an economy plugin already there")
    void forceTakesOver() throws Exception {
        Bukkit.getServicesManager().register(Economy.class, bank(42),
                FakeServer.newPlugin("Essentials"), ServicePriority.Normal);
        start(true);

        assertEquals("ExyliaLib:shards", ((Economy) served()).getName());
    }

    /** An economy plugin with one balance for everybody. */
    private static Economy bank(double balance) {
        return new Economy() {
            @Override public String getName() { return "Essentials"; }
            @Override public double getBalance(OfflinePlayer player) { return balance; }
            @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
                return new EconomyResponse(amount, balance + amount, EconomyResponse.ResponseType.SUCCESS, "");
            }
            @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
                return new EconomyResponse(amount, balance - amount, EconomyResponse.ResponseType.SUCCESS, "");
            }
            @Override public String currencyNameSingular() { return "dollar"; }
            @Override public String currencyNamePlural() { return "dollars"; }
        };
    }
}
