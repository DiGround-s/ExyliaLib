package net.exylia.lib.escrow;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.TestDatabases;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.escrow.internal.ItemRow;
import net.exylia.lib.escrow.internal.OwedRow;
import net.exylia.lib.escrow.internal.StakeRow;
import net.exylia.lib.escrow.internal.StakeState;
import net.exylia.lib.proxy.internal.ProxyRuntime;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stakes move PENDING → HELD → SETTLED/REFUNDED once, survive a restart, and owed rows are delivered once. */
class EscrowTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private final Gems gems = new Gems();
    private final Map<UUID, List<String>> given = new ConcurrentHashMap<>();
    /** How many items fit in any inventory. */
    private volatile int room = Integer.MAX_VALUE;
    private final Map<UUID, Integer> saves = new ConcurrentHashMap<>();

    private Plugin plugin;
    private FakePlayer alice;
    private FakePlayer bob;
    private Repository<StakeRow> stakes;
    private Repository<ItemRow> items;
    private Repository<OwedRow> owed;

    @BeforeAll
    static void install() {
        FakeServer.install();
    }

    @BeforeEach
    void open() {
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("Duels");
        TestDatabases.memory(plugin, "escrow" + DATABASE.incrementAndGet());
        Economy.register(gems);
        alice = new FakePlayer("alice");
        bob = new FakePlayer("bob");
        FakeServer.online(alice.player(), bob.player());
        gems.balances.put(id(alice), new BigDecimal("100"));
        gems.balances.put(id(bob), new BigDecimal("100"));
        Escrow.setHands(new StubHands());
        stakes = Databases.of(plugin).repository(StakeRow.class);
        items = Databases.of(plugin).repository(ItemRow.class);
        owed = Databases.of(plugin).repository(OwedRow.class);
    }

    @AfterEach
    void close() {
        Escrow.releaseAll();
        Escrow.resetHands();
        Economy.unregister("gems");
        Databases.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    private Escrow escrow() {
        Escrow escrow = Escrow.of(plugin);
        escrow.recovery().join();
        return escrow;
    }

    @Test
    @DisplayName("a won pot is paid once, less the tax, and a second settle pays nothing")
    void settleOnce() {
        Escrow escrow = escrow();
        assertEquals(Escrow.Hold.HELD, escrow.hold("d1", id(alice), "gems", new BigDecimal("40")).join());
        assertEquals(Escrow.Hold.HELD, escrow.hold("d1", id(bob), "gems", new BigDecimal("40")).join());
        assertBalance(alice, "60");
        assertBalance(bob, "60");

        List<Escrow.Payout> payouts = escrow.settle("d1", id(alice), 0.1).join();
        FakeServer.tick(1);

        assertEquals(1, payouts.size());
        assertEquals(0, new BigDecimal("8").compareTo(payouts.getFirst().tax()));
        assertBalance(alice, "132");
        assertTrue(escrow.settle("d1", id(alice), 0.1).join().isEmpty());
        assertTrue(escrow.refund("d1").thenApply(ignored -> true).join());
        FakeServer.tick(1);
        assertBalance(alice, "132");
        assertBalance(bob, "60");
        assertEquals(2, states(StakeState.SETTLED));
    }

    @Test
    @DisplayName("a refund gives each stake back once, and a settle after it pays nothing")
    void refundOnce() {
        Escrow escrow = escrow();
        escrow.hold("d1", id(alice), "gems", new BigDecimal("30")).join();
        escrow.hold("d1", id(bob), "gems", new BigDecimal("50")).join();

        escrow.refund("d1").join();
        escrow.refund("d1").join();
        assertTrue(escrow.settle("d1", id(alice)).join().isEmpty());
        FakeServer.tick(1);

        assertBalance(alice, "100");
        assertBalance(bob, "100");
        assertEquals(2, states(StakeState.REFUNDED));
    }

    @Test
    @DisplayName("a settle racing a refund moves each stake once")
    void raceSettleRefund() {
        Escrow escrow = escrow();
        int matches = 20;
        for (int i = 0; i < matches; i++) {
            escrow.hold("d" + i, id(alice), "gems", BigDecimal.ONE).join();
            escrow.hold("d" + i, id(bob), "gems", BigDecimal.ONE).join();
        }
        List<java.util.concurrent.CompletableFuture<?>> racing = new ArrayList<>();
        for (int i = 0; i < matches; i++) {
            String match = "d" + i;
            racing.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> escrow.settle(match, id(alice)).join()));
            racing.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> escrow.refund(match).join()));
        }
        racing.forEach(java.util.concurrent.CompletableFuture::join);
        FakeServer.tick(1);

        BigDecimal total = gems.balance(id(alice)).add(gems.balance(id(bob)));
        assertEquals(0, new BigDecimal("200").compareTo(total), "total " + total);
        assertEquals(2L * matches, states(StakeState.SETTLED) + states(StakeState.REFUNDED));
    }

    @Test
    @DisplayName("a player who cannot pay holds nothing")
    void cannotPay() {
        Escrow escrow = escrow();
        assertEquals(Escrow.Hold.CANNOT_PAY, escrow.hold("d1", id(alice), "gems", new BigDecimal("101")).join());
        assertBalance(alice, "100");
        assertEquals(0, states(StakeState.HELD));
    }

    @Test
    @DisplayName("a hold landing after the match ended goes straight back")
    void lateHold() {
        Escrow escrow = escrow();
        escrow.settle("d1", id(bob)).join();
        assertEquals(Escrow.Hold.FAILED, escrow.hold("d1", id(alice), "gems", new BigDecimal("10")).join());
        await(() -> states(StakeState.REFUNDED) == 1);
        FakeServer.tick(1);
        assertBalance(alice, "100");
        assertBalance(bob, "100");
    }

    @Test
    @DisplayName("a restart refunds this server's held stakes, voids pending ones, and leaves other servers' alone")
    void recover() {
        String here = ProxyRuntime.networkServerId().orElse("server-1");
        StakeRow held = StakeRow.pending("Duels", here, "d1", id(alice), "gems", new BigDecimal("25"))
                .with(StakeState.HELD);
        StakeRow pending = StakeRow.pending("Duels", here, "d1", id(bob), "gems", new BigDecimal("25"));
        StakeRow elsewhere = StakeRow.pending("Duels", "other", "d2", id(bob), "gems", new BigDecimal("25"))
                .with(StakeState.HELD);
        stakes.saveAll(List.of(held, pending, elsewhere)).join();
        items.save(ItemRow.held("Duels", here, "d1", id(bob), "sword,apple")).join();

        escrow();
        FakeServer.tick(1);

        assertBalance(alice, "125");
        assertBalance(bob, "100");
        assertEquals(StakeState.REFUNDED, stakes.find(held.id()).join().orElseThrow().state());
        assertEquals(StakeState.VOID, stakes.find(pending.id()).join().orElseThrow().state());
        assertEquals(StakeState.HELD, stakes.find(elsewhere.id()).join().orElseThrow().state());
        assertEquals(List.of("sword", "apple"), given.get(id(bob)));
    }

    @Test
    @DisplayName("an offline winner is paid on their next join, exactly once")
    void owedOnce() {
        Escrow escrow = escrow();
        escrow.hold("d1", id(alice), "gems", new BigDecimal("20")).join();
        escrow.hold("d1", id(bob), "gems", new BigDecimal("20")).join();
        FakeServer.online(alice.player());

        escrow.settle("d1", id(bob)).join();
        await(() -> owed.count().join() == 1);
        assertBalance(bob, "80");

        FakeServer.online(alice.player(), bob.player());
        // Two joins, or two servers, racing for the same row.
        escrow.deliver(bob.player()).join();
        escrow.deliver(bob.player()).join();
        FakeServer.tick(1);

        assertBalance(bob, "120");
        assertEquals(0L, owed.count().join());
    }

    @Test
    @DisplayName("held items go to the winner once; what does not fit is owed, never dropped")
    void items() {
        Escrow escrow = escrow();
        assertTrue(escrow.holdItems("d1", id(alice), List.of(new Stub("sword"), new Stub("helmet"))).join());
        assertTrue(escrow.holdItems("d1", id(bob), List.of(new Stub("bow"))).join());
        FakeServer.tick(1);
        await(() -> saves.getOrDefault(id(alice), 0) == 1 && saves.getOrDefault(id(bob), 0) == 1);
        room = 2;

        escrow.settleItems("d1", id(bob)).join();
        escrow.settleItems("d1", id(bob)).join();
        escrow.refundItems("d1").join();
        FakeServer.tick(1);

        await(() -> owed.count().join() == 1);
        assertEquals(2, given.get(id(bob)).size());
        assertEquals(2, saves.get(id(bob)), "saved again once the winnings are in the inventory");
        assertFalse(given.containsKey(id(alice)));

        room = Integer.MAX_VALUE;
        escrow.deliver(bob.player()).join();
        FakeServer.tick(1);
        assertEquals(3, given.get(id(bob)).size());
        assertEquals(0L, owed.count().join());
    }

    @Test
    @DisplayName("a refund gives each player their own items back")
    void refundItems() {
        Escrow escrow = escrow();
        escrow.holdItems("d1", id(alice), List.of(new Stub("sword"))).join();
        escrow.holdItems("d1", id(bob), List.of(new Stub("bow"))).join();

        escrow.refundItems("d1").join();
        escrow.settleItems("d1", id(alice)).join();
        FakeServer.tick(1);

        assertEquals(List.of("sword"), given.get(id(alice)));
        assertEquals(List.of("bow"), given.get(id(bob)));
    }

    @Test
    @DisplayName("a player's held items are found by match until they are paid out")
    void heldItemMatches() {
        Escrow escrow = escrow();
        assertTrue(escrow.heldItemMatches(id(alice)).join().isEmpty());
        escrow.holdItems("d1", id(alice), List.of(new Stub("sword"))).join();
        escrow.holdItems("d2", id(alice), List.of(new Stub("bow"))).join();
        assertEquals(java.util.Set.of("d1", "d2"), escrow.heldItemMatches(id(alice)).join());
        assertTrue(escrow.heldItemMatches(id(bob)).join().isEmpty());

        escrow.settleItems("d1", id(bob)).join();
        assertEquals(java.util.Set.of("d2"), escrow.heldItemMatches(id(alice)).join());
    }

    @Test
    @DisplayName("a further stake needs a first one, joins the pot, and is refused once the match ended")
    void holdMore() {
        Escrow escrow = escrow();
        assertTrue(escrow.isReady());
        assertEquals(Escrow.Hold.FAILED, escrow.holdMore("d1", id(alice), "gems", BigDecimal.TEN).join());
        assertBalance(alice, "100");

        escrow.hold("d1", id(alice), "gems", BigDecimal.TEN).join();
        assertEquals(Escrow.Hold.HELD, escrow.holdMore("d1", id(alice), "gems", BigDecimal.TEN).join());
        assertEquals(Escrow.Hold.FAILED, escrow.holdMore("d1", id(bob), "gems", BigDecimal.TEN).join());
        escrow.hold("d1", id(bob), "gems", BigDecimal.TEN).join();

        List<Escrow.Payout> payouts = escrow.settle("d1", id(bob)).join();
        FakeServer.tick(1);
        assertEquals(0, new BigDecimal("30").compareTo(payouts.getFirst().pot()));
        assertBalance(bob, "120");
        assertEquals(Escrow.Hold.FAILED, escrow.holdMore("d1", id(alice), "gems", BigDecimal.TEN).join());
        assertBalance(alice, "80");
    }

    @Test
    @DisplayName("an amount is paid once, the stakes the game used are settled, and an unused extra goes back")
    void settleAmount() throws InterruptedException {
        Escrow escrow = escrow();
        escrow.hold("r1", id(alice), "gems", BigDecimal.TEN).join();
        Thread.sleep(5);
        escrow.holdMore("r1", id(alice), "gems", BigDecimal.TEN).join();
        assertBalance(alice, "80");

        assertTrue(escrow.settleAmount("r1", id(alice), new BigDecimal("25"), 1).join());
        assertFalse(escrow.settleAmount("r1", id(alice), new BigDecimal("25")).join());
        escrow.refund("r1").join();
        await(() -> states(StakeState.REFUNDED) == 1);
        FakeServer.tick(1);

        assertBalance(alice, "115");
        assertEquals(1, states(StakeState.SETTLED));

        escrow.hold("r2", id(bob), "gems", BigDecimal.TEN).join();
        assertTrue(escrow.settleAmount("r2", id(bob), BigDecimal.ZERO).join());
        FakeServer.tick(1);
        assertBalance(bob, "90");
    }

    @Test
    @DisplayName("two settles of an amount racing each other, or a refund, pay it once")
    void settleAmountRace() {
        Escrow escrow = escrow();
        int matches = 20;
        for (int i = 0; i < matches; i++) {
            escrow.hold("r" + i, id(alice), "gems", BigDecimal.ONE).join();
        }
        List<java.util.concurrent.CompletableFuture<?>> racing = new ArrayList<>();
        for (int i = 0; i < matches; i++) {
            String match = "r" + i;
            racing.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> escrow.settleAmount(match, id(alice), new BigDecimal("3")).join()));
            racing.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> escrow.settleAmount(match, id(alice), new BigDecimal("3")).join()));
            if (i % 2 == 0) {
                racing.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> escrow.refund(match).join()));
            }
        }
        racing.forEach(java.util.concurrent.CompletableFuture::join);
        FakeServer.tick(1);

        long settled = states(StakeState.SETTLED);
        long refunded = states(StakeState.REFUNDED);
        assertEquals(matches, settled + refunded);
        BigDecimal expected = new BigDecimal(80 + 3 * settled + refunded);
        assertBalance(alice, expected.toPlainString());
    }

    @Test
    @DisplayName("held items are paid out at a multiplier, and nothing at zero")
    void settleItemsMultiplied() {
        Escrow escrow = escrow();
        escrow.holdItems("h1", id(alice), List.of(new Stub("sword"))).join();
        escrow.holdItems("h2", id(bob), List.of(new Stub("bow"))).join();

        escrow.settleItems("h1", id(alice), 3).join();
        escrow.settleItems("h1", id(alice), 3).join();
        escrow.settleItems("h2", id(bob), 0).join();
        escrow.refundItems("h2").join();
        FakeServer.tick(1);

        assertEquals(List.of("sword", "sword", "sword"), given.get(id(alice)));
        assertFalse(given.containsKey(id(bob)));
        assertEquals(2L, items.where("state", StakeState.SETTLED).count().join());
    }

    // ---------------------------------------------------------------- helpers

    private static UUID id(FakePlayer player) {
        return player.player().getUniqueId();
    }

    private void assertBalance(FakePlayer player, String expected) {
        BigDecimal actual = gems.balance(id(player));
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "balance " + actual);
    }

    private long states(StakeState state) {
        return stakes.where("state", state).count().join();
    }

    private static void await(BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("timed out");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Items by name, given into a list per player with a fixed room. */
    private final class StubHands implements Escrow.Hands {
        @Override
        public @NotNull String encode(@NotNull List<ItemStack> stacks) {
            return String.join(",", stacks.stream().map(item -> ((Stub) item).kind).toList());
        }

        @Override
        public @NotNull List<ItemStack> decode(@NotNull String stored) {
            return new ArrayList<>(Arrays.stream(stored.split(",")).map(Stub::new).toList());
        }

        @Override
        public void save(@NotNull Player player) {
            saves.merge(player.getUniqueId(), 1, Integer::sum);
        }

        @Override
        public @NotNull List<ItemStack> give(@NotNull Player player, @NotNull List<ItemStack> stacks) {
            List<String> inventory = given.computeIfAbsent(player.getUniqueId(), key -> new CopyOnWriteArrayList<>());
            List<ItemStack> left = new ArrayList<>();
            for (ItemStack stack : stacks) {
                if (inventory.size() < room) {
                    inventory.add(((Stub) stack).kind);
                } else {
                    left.add(stack);
                }
            }
            return left;
        }
    }

    /** An item named by kind; {@code new ItemStack(...)} needs a running server. */
    private static final class Stub extends ItemStack {
        private final String kind;

        Stub(String kind) {
            this.kind = kind;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public @NotNull ItemStack clone() {
            return new Stub(kind);
        }
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
