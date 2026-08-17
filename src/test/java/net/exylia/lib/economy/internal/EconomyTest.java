package net.exylia.lib.economy.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyException;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.EconomySettings;
import net.exylia.lib.economy.TransferResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviours an economy has to get right, exercised against in-memory
 * providers.
 *
 * <p>The fakes are the point. They let a test reproduce the failure a real
 * economy only shows under load — a deposit that is refused after the sender
 * was already charged — and prove the library never mints or loses money in
 * it. A test that only exercises the happy path proves the happy path works,
 * which nobody doubted.
 */
class EconomyTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        CurrencyRegistry.clearForTests();
        CurrencyRegistry.apply(new EconomySettings());
        BalanceCache.apply(new EconomySettings());
    }

    @AfterEach
    void tearDown() {
        CurrencyRegistry.clearForTests();
        BalanceCache.invalidateAll();
    }

    /**
     * An in-memory currency, with switches to make individual operations fail.
     *
     * <p>Precision is kept with {@link BigDecimal} throughout, so a fake can
     * hold the same fractional balances a real one does.
     */
    static class FakeCurrency implements CurrencyProvider {
        final String id;
        final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
        volatile boolean available = true;
        volatile boolean failDeposit;
        volatile boolean failWithdraw;

        FakeCurrency(String id) {
            this.id = id;
        }

        void give(UUID player, String amount) {
            balances.put(player, new BigDecimal(amount));
        }

        @Override
        public @NotNull String id() {
            return id;
        }

        @Override
        public @NotNull String displayName() {
            return "Fake " + id;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public @NotNull BigDecimal balance(@NotNull UUID player) {
            return balances.getOrDefault(player, BigDecimal.ZERO);
        }

        @Override
        public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
            if (!available) {
                return EconomyResponse.notAvailable();
            }
            if (failDeposit) {
                return EconomyResponse.failure("deposit refused by test");
            }
            BigDecimal next = balance(player).add(amount);
            balances.put(player, next);
            return EconomyResponse.success(amount, next);
        }

        @Override
        public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
            if (!available) {
                return EconomyResponse.notAvailable();
            }
            if (failWithdraw) {
                return EconomyResponse.failure("withdraw refused by test");
            }
            BigDecimal current = balance(player);
            if (current.compareTo(amount) < 0) {
                return EconomyResponse.insufficientFunds(amount, current);
            }
            BigDecimal next = current.subtract(amount);
            balances.put(player, next);
            return EconomyResponse.success(amount, next);
        }

        @Override
        public @NotNull String currencyName(boolean plural) {
            return plural ? "coins" : "coin";
        }

        @Override
        public @NotNull String symbol() {
            return "C";
        }
    }

    private FakeCurrency install(String id) {
        FakeCurrency currency = new FakeCurrency(id);
        CurrencyRegistry.install(id, currency);
        return currency;
    }

    private FakeCurrency install() {
        return install("test");
    }

    private Economy.CurrencyView view() {
        return Economy.of("test");
    }

    private FakeCurrency installDefault() {
        FakeCurrency currency = install("test");
        CurrencyRegistry.apply(new EconomySettings("test",
                java.util.List.of(), 500L));
        return currency;
    }

    // ------------------------------------------------------------ basics

    @Test
    @DisplayName("deposit adds and withdraw removes")
    void depositAndWithdraw() {
        FakeCurrency currency = installDefault();
        Economy.CurrencyView view = view();

        view.deposit(alice, new BigDecimal("100"));
        assertEquals(0, new BigDecimal("100").compareTo(currency.balance(alice)));

        EconomyResponse withdrawn = view.withdraw(alice, new BigDecimal("30"));
        assertTrue(withdrawn.isSuccess());
        assertEquals(0, new BigDecimal("70").compareTo(currency.balance(alice)));
    }

    @Test
    @DisplayName("a withdraw that cannot be afforded changes nothing")
    void insufficientFundsChangesNothing() {
        FakeCurrency currency = installDefault();
        currency.give(alice, "20");

        EconomyResponse response = view().withdraw(alice, new BigDecimal("50"));

        assertFalse(response.isSuccess());
        assertEquals(EconomyResponse.Type.INSUFFICIENT_FUNDS, response.type());
        assertEquals(0, new BigDecimal("20").compareTo(currency.balance(alice)),
                "the balance must be untouched");
        assertEquals(0, new BigDecimal("30").compareTo(response.shortfall()));
    }

    @Test
    @DisplayName("charge answers whether the money moved")
    void charge() {
        FakeCurrency currency = installDefault();
        currency.give(alice, "10");

        assertTrue(view().charge(alice, new BigDecimal("10")));
        assertFalse(view().charge(alice, new BigDecimal("1")),
                "the first charge emptied the balance");
    }

    @Test
    @DisplayName("set lands on the exact value either way")
    void set() {
        FakeCurrency currency = installDefault();
        currency.give(alice, "40");

        view().set(alice, new BigDecimal("100"));
        assertEquals(0, new BigDecimal("100").compareTo(currency.balance(alice)));

        view().set(alice, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(currency.balance(alice)));
    }

    // --------------------------------------------------------- precision

    @Test
    @DisplayName("money keeps the decimals a double would have lost")
    void precision() {
        installDefault();
        Economy.CurrencyView view = view();

        view.deposit(alice, new BigDecimal("0.1"));
        view.deposit(alice, new BigDecimal("0.2"));

        assertEquals(0, new BigDecimal("0.3").compareTo(view.balance(alice)),
                "0.1 + 0.2 must be 0.3, not 0.30000000000000004");
    }

    // --------------------------------------------------------- validation

    @Test
    @DisplayName("a negative amount is a caller bug, thrown, not reported as a balance problem")
    void negativeAmountThrows() {
        installDefault();
        Economy.CurrencyView view = view();

        assertThrows(EconomyException.class, () -> view.withdraw(alice, new BigDecimal("-5")));
        assertThrows(EconomyException.class, () -> view.deposit(alice, new BigDecimal("-5")));
        assertThrows(EconomyException.class, () -> view.transfer(alice, bob, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("a null player is a caller bug, thrown")
    void nullPlayerThrows() {
        installDefault();
        assertThrows(EconomyException.class, () -> view().balance(null));
        assertThrows(EconomyException.class, () -> view().deposit(null, BigDecimal.ONE));
    }

    @Test
    @DisplayName("no provider yields NOT_AVAILABLE, never an exception")
    void noProvider() {
        // Nothing installed. A server without an economy must still answer,
        // so a plugin can say "no economy here" instead of crashing on boot.
        assertFalse(Economy.isAvailable());
        assertEquals(EconomyResponse.Type.NOT_AVAILABLE,
                view().deposit(alice, BigDecimal.ONE).type());
        assertEquals(0, BigDecimal.ZERO.compareTo(view().balance(alice)));
        assertEquals(TransferResult.Type.NOT_AVAILABLE,
                view().transfer(alice, bob, BigDecimal.ONE).type());
    }

    // ----------------------------------------------------------- transfer

    @Test
    @DisplayName("a transfer moves the exact amount, no more, no less")
    void transferHappyPath() {
        FakeCurrency currency = installDefault();
        currency.give(alice, "100");

        TransferResult result = view().transfer(alice, bob, new BigDecimal("40"));

        assertTrue(result.isSuccess());
        assertEquals(0, new BigDecimal("60").compareTo(currency.balance(alice)));
        assertEquals(0, new BigDecimal("40").compareTo(currency.balance(bob)));
    }

    @Test
    @DisplayName("a transfer the sender cannot afford moves nothing")
    void transferInsufficient() {
        FakeCurrency currency = installDefault();
        currency.give(alice, "10");
        currency.give(bob, "5");

        TransferResult result = view().transfer(alice, bob, new BigDecimal("50"));

        assertEquals(TransferResult.Type.INSUFFICIENT_FUNDS, result.type());
        assertEquals(0, new BigDecimal("10").compareTo(currency.balance(alice)));
        assertEquals(0, new BigDecimal("5").compareTo(currency.balance(bob)));
    }

    @Test
    @DisplayName("a transfer to oneself is refused")
    void transferToSelf() {
        installDefault();
        TransferResult result = view().transfer(alice, alice, new BigDecimal("10"));
        assertEquals(TransferResult.Type.INVALID_AMOUNT, result.type());
    }

    @Test
    @DisplayName("a failed deposit refunds the sender, so no money is created or lost")
    void failedDepositRefunds() {
        // The case Commons never handled: the sender is charged, the receiver's
        // deposit is refused. The library must put the money back, because the
        // alternative is the amount vanishing.
        FakeCurrency currency = new FakeCurrency("test") {
            @Override
            public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
                // The receiver's account is broken (frozen, say); the sender's is not,
                // so the refund to them can still land.
                if (player.equals(bob)) {
                    return EconomyResponse.failure("receiver account frozen");
                }
                return super.deposit(player, amount);
            }
        };
        CurrencyRegistry.install("test", currency);
        CurrencyRegistry.apply(new EconomySettings("test", java.util.List.of(), 500L));
        currency.give(alice, "100");

        TransferResult result = view().transfer(alice, bob, new BigDecimal("40"));

        assertFalse(result.isSuccess());
        assertEquals(0, new BigDecimal("100").compareTo(currency.balance(alice)),
                "the sender must be refunded in full");
        assertEquals(0, BigDecimal.ZERO.compareTo(currency.balance(bob)),
                "the receiver got nothing");
    }

    @Test
    @DisplayName("a charged-but-undelivered transfer with a refused refund is PARTIAL and loud")
    void partialTransferIsReported() {
        // The worst case: charged, not delivered, and the refund itself refused.
        // The money is genuinely gone, and the library's job is to say so with
        // everything needed to refund by hand — not return a quiet false.
        FakeCurrency currency = installDefault();
        currency.give(alice, "100");
        currency.failDeposit = true; // both the credit and the refund are deposits

        TransferResult result = view().transfer(alice, bob, new BigDecimal("40"));

        assertEquals(TransferResult.Type.PARTIAL, result.type());
        assertTrue(result.isPartial());
        assertEquals(alice, result.from());
        assertEquals(bob, result.to());
        assertEquals(0, new BigDecimal("40").compareTo(result.amount()));
        // The money really is gone from the sender and never arrived: 60 left.
        assertEquals(0, new BigDecimal("60").compareTo(currency.balance(alice)));
        assertEquals(0, BigDecimal.ZERO.compareTo(currency.balance(bob)));
    }

    @Test
    @DisplayName("a native transfer is used when the provider has one")
    void nativeTransferPreferred() {
        // A provider with an atomic pay must be used, not routed through
        // withdraw-verify-deposit.
        final boolean[] nativeUsed = {false};
        FakeCurrency currency = new FakeCurrency("test") {
            @Override
            public @Nullable TransferResult transfer(
                    @NotNull UUID f, @NotNull UUID t, @NotNull BigDecimal amount) {
                nativeUsed[0] = true;
                balances.put(f, balance(f).subtract(amount));
                balances.put(t, balance(t).add(amount));
                return TransferResult.success(f, t, amount);
            }
        };
        CurrencyRegistry.install("test", currency);
        CurrencyRegistry.apply(new EconomySettings("test", java.util.List.of(), 500L));
        currency.give(alice, "50");

        TransferResult result = view().transfer(alice, bob, new BigDecimal("20"));

        assertTrue(result.isSuccess());
        assertTrue(nativeUsed[0], "the provider's own transfer must be used");
        assertEquals(0, new BigDecimal("30").compareTo(currency.balance(alice)));
        assertEquals(0, new BigDecimal("20").compareTo(currency.balance(bob)));
    }

    // --------------------------------------------------------------- cache

    @Test
    @DisplayName("a balance the library changed is visible at once, not after the cache expires")
    void ownWritesInvalidateTheCache() {
        // The cache exists so a scoreboard does not hit the economy every tick.
        // The trap it creates: a player buys something and their balance still
        // reads the old number for half a second. That is the "my money
        // disappeared" ticket, so every write the library makes drops the entry.
        FakeCurrency currency = installDefault();
        currency.give(alice, "100");
        Economy.CurrencyView view = view();

        assertEquals(0, new BigDecimal("100").compareTo(view.balance(alice)),
                "priming the cache");

        view.withdraw(alice, new BigDecimal("40"));
        assertEquals(0, new BigDecimal("60").compareTo(view.balance(alice)),
                "the balance must reflect the purchase immediately");

        view.deposit(alice, new BigDecimal("10"));
        assertEquals(0, new BigDecimal("70").compareTo(view.balance(alice)));

        view.set(alice, new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(view.balance(alice)));
    }

    @Test
    @DisplayName("a transfer refreshes both balances")
    void transferInvalidatesBothSides() {
        FakeCurrency currency = installDefault();
        currency.give(alice, "100");
        currency.give(bob, "0");
        Economy.CurrencyView view = view();

        // Prime both.
        view.balance(alice);
        view.balance(bob);

        view.transfer(alice, bob, new BigDecimal("30"));

        assertEquals(0, new BigDecimal("70").compareTo(view.balance(alice)));
        assertEquals(0, new BigDecimal("30").compareTo(view.balance(bob)));
    }

    @Test
    @DisplayName("a balance read twice does not hit the provider twice")
    void cacheActuallyCaches() {
        // The reason the cache is there at all. Without it this is two provider
        // calls per scoreboard line per tick.
        final int[] reads = {0};
        FakeCurrency currency = new FakeCurrency("test") {
            @Override
            public @NotNull BigDecimal balance(@NotNull UUID player) {
                reads[0]++;
                return super.balance(player);
            }
        };
        CurrencyRegistry.install("test", currency);
        CurrencyRegistry.apply(new EconomySettings("test", java.util.List.of(), 500L));
        currency.give(alice, "10");

        Economy.CurrencyView view = view();
        view.balance(alice);
        view.balance(alice);
        view.balance(alice);

        assertEquals(1, reads[0], "three reads in one tick must ask the economy once");
    }

    // ------------------------------------------------------------- fallback

    @Test
    @DisplayName("when the default currency is gone, the fallback serves")
    void fallbackServes() {
        FakeCurrency vault = install("vault");
        FakeCurrency points = install("points");
        vault.available = false;
        CurrencyRegistry.apply(new EconomySettings("vault",
                java.util.List.of("points"), 500L));

        points.give(alice, "77");

        // The DEFAULT view — not a named one — must follow the fallback to the
        // currency that is actually there, so a server whose economy plugin
        // disappeared keeps working instead of reporting zero to everyone.
        assertEquals(0, new BigDecimal("77").compareTo(Economy.balance(alice)));
    }

    @Test
    @DisplayName("an explicit currency does not fall back to another")
    void explicitCurrencyDoesNotFallBack() {
        install("vault");
        FakeCurrency points = install("points");
        points.give(alice, "9");

        // Asking for "points" must read points, not silently read the default.
        assertEquals(0, new BigDecimal("9").compareTo(Economy.of("points").balance(alice)));
    }

    @Test
    @DisplayName("a named currency that is down fails, it does not charge a different one")
    void namedCurrencyDownDoesNotFallBack() {
        // The failure this prevents: a plugin asks to charge 500 points, points
        // is unavailable, and the player is charged 500 of their money instead —
        // the right number in the wrong currency. Falling back is for the
        // default, so a server keeps working; it is not for turning an explicit
        // request into a different one.
        FakeCurrency vault = install("vault");
        FakeCurrency points = install("points");
        points.available = false;
        vault.give(alice, "1000");
        CurrencyRegistry.apply(new EconomySettings("vault",
                java.util.List.of("vault", "points"), 500L));

        EconomyResponse response = Economy.of("points").withdraw(alice, new BigDecimal("500"));

        assertEquals(EconomyResponse.Type.NOT_AVAILABLE, response.type());
        assertEquals(0, new BigDecimal("1000").compareTo(vault.balance(alice)),
                "the player's money must not pay for a points purchase");
    }

    @Test
    @DisplayName("a duplicate currency id is refused, not silently overwritten")
    void duplicateIdRefused() {
        Economy.register(new FakeCurrency("vault"));
        assertThrows(EconomyException.class, () -> Economy.register(new FakeCurrency("vault")),
                "one id, one economy — otherwise a balance goes to the wrong place");
    }
}
