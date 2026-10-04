package net.exylia.lib.cosmetic;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Money is only kept when the cosmetic was given, and one purchase runs at a time. */
class CosmeticPurchasesTest {

    private Plugin plugin;
    private FakePlayer steve;
    private UUID uuid;
    private final Gems gems = new Gems();
    private final Price price = new Price(new BigDecimal("500"), "gems");

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("KillEffects");
        steve = new FakePlayer("Steve");
        FakeServer.online(steve.player());
        uuid = steve.player().getUniqueId();
        Economy.register(gems);
        gems.balances.put(uuid, new BigDecimal("800"));
    }

    @AfterEach
    void tearDown() {
        Economy.unregister("gems");
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private CompletableFuture<CosmeticPurchases.Result> buy(CompletableFuture<Boolean> grant) {
        return CosmeticPurchases.purchase(plugin, steve.player(), "flame", "Flame", price, () -> false, () -> grant);
    }

    @Test
    @DisplayName("a granted purchase keeps the money; a failed grant refunds it")
    void refundsAFailedGrant() {
        assertEquals(CosmeticPurchases.Result.SUCCESS, buy(CompletableFuture.completedFuture(true)).join());
        assertEquals(0, new BigDecimal("300").compareTo(gems.balances.get(uuid)));

        gems.balances.put(uuid, new BigDecimal("800"));
        CompletableFuture<CosmeticPurchases.Result> failed = buy(CompletableFuture.failedFuture(new IllegalStateException("db down")));
        FakeServer.tick(1);
        assertEquals(CosmeticPurchases.Result.FAILED, failed.join());
        assertEquals(0, new BigDecimal("800").compareTo(gems.balances.get(uuid)));

        assertEquals(CosmeticPurchases.Result.INSUFFICIENT_FUNDS,
                CosmeticPurchases.purchase(plugin, steve.player(), "flame", "Flame",
                        new Price(new BigDecimal("900"), "gems"), () -> false,
                        () -> CompletableFuture.completedFuture(true)).join());
        assertEquals(CosmeticPurchases.Result.ALREADY_OWNED,
                CosmeticPurchases.purchase(plugin, steve.player(), "flame", "Flame", price, () -> true,
                        () -> CompletableFuture.completedFuture(true)).join());
    }

    @Test
    @DisplayName("a second click while the first is in flight is refused, and the guard is released after")
    void oneAtATime() {
        CompletableFuture<Boolean> slow = new CompletableFuture<>();
        CompletableFuture<CosmeticPurchases.Result> first = buy(slow);
        assertEquals(CosmeticPurchases.Result.BUSY, buy(CompletableFuture.completedFuture(true)).join());

        slow.complete(true);
        assertEquals(CosmeticPurchases.Result.SUCCESS, first.join());
        assertEquals(0, new BigDecimal("300").compareTo(gems.balances.get(uuid)));
        assertEquals(CosmeticPurchases.Result.INSUFFICIENT_FUNDS, buy(CompletableFuture.completedFuture(true)).join());
    }

    @Test
    @DisplayName("prices read from config: numbers, blocks, tiers; nothing that is not a positive plain amount")
    void parsesPrices() {
        assertEquals(Optional.of(new Price(new BigDecimal("500"), null)), Price.parse(500));
        assertEquals(Optional.of(new Price(new BigDecimal("50"), "gems")), Price.parse(Map.of("amount", 50, "currency", "Gems")));
        assertEquals(new BigDecimal("2500"), Price.parse("2.5k").orElseThrow().amount().stripTrailingZeros().setScale(0));
        for (Object bad : new Object[]{0, -5, "1e9", "abc", Double.NaN, null, Map.of("currency", "gems")}) {
            assertTrue(Price.parse(bad).isEmpty(), "accepted " + bad);
        }

        Map<String, Price> tiers = Price.parseAll(Map.of("Common", 100, "broken", "x"));
        assertEquals(Map.of("common", new Price(new BigDecimal("100"), null)), tiers);
        assertEquals(Optional.of(price), Price.resolve(price, "common", tiers));
        assertEquals(tiers.get("common"), Price.resolve(null, "COMMON", tiers).orElseThrow());
        assertTrue(Price.resolve(null, "legendary", tiers).isEmpty());
        assertTrue(Price.resolve(null, null, tiers).isEmpty());
    }

    /** An in-memory currency. */
    private static final class Gems implements CurrencyProvider {
        final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();

        @Override public @NotNull String id() { return "gems"; }
        @Override public @NotNull String displayName() { return "Gems"; }
        @Override public boolean isAvailable() { return true; }
        @Override public @NotNull BigDecimal balance(@NotNull UUID player) { return balances.getOrDefault(player, BigDecimal.ZERO); }
        @Override public @NotNull String currencyName(boolean plural) { return plural ? "gems" : "gem"; }
        @Override public @NotNull String symbol() { return "◆"; }

        @Override
        public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
            BigDecimal next = balance(player).add(amount);
            balances.put(player, next);
            return EconomyResponse.success(amount, next);
        }

        @Override
        public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
            BigDecimal current = balance(player);
            if (current.compareTo(amount) < 0) return EconomyResponse.insufficientFunds(amount, current);
            BigDecimal next = current.subtract(amount);
            balances.put(player, next);
            return EconomyResponse.success(amount, next);
        }
    }
}
