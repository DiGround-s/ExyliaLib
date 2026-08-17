package net.exylia.lib.economy;

import net.exylia.lib.economy.internal.BalanceCache;
import net.exylia.lib.economy.internal.CurrencyRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Money, for every Exylia plugin.
 *
 * <pre>{@code
 * Economy.balance(player);                    // 1250.00
 * Economy.has(player, price);                 // true
 * Economy.charge(player, price);              // withdraw, or fail without touching it
 * Economy.pay(player, reward);                // deposit
 * Economy.transfer(sender, target, amount);   // move between players
 * Economy.of("points").balance(player);       // a named currency
 * }</pre>
 *
 * <p>One facade over however many economies a server runs. A plugin asks for
 * money and the {@code economy.yml} decides which economy serves it; a plugin
 * that cares names a currency by id. Behind the facade, Vault and PlayerPoints
 * are adapted by reflection, so neither is needed at compile time and either
 * can be absent without consequence.
 *
 * <h2>What the numbers are</h2>
 * Every amount is a {@link BigDecimal}, end to end. A balance is the one number
 * a {@code double} must not hold, and the place a double would be introduced —
 * Vault's API — is converted with {@link BigDecimal#valueOf(double)}, which reads
 * the shortest decimal that round-trips rather than the binary noise underneath.
 *
 * <h2>What fails, and how</h2>
 * The ordinary reasons an economy operation fails are returned, not thrown:
 * not enough money, no provider. They are the expected outcome of a purchase,
 * and a command branches on them. What is a programming error — a negative
 * amount, a null player — is an {@link EconomyException}, because that should
 * fail loudly in development, not be reported to a player as "insufficient
 * funds".
 *
 * <h2>What a call costs</h2>
 * A balance read is served from a short cache: these run on every tick of every
 * scoreboard of every player, and asking the economy each time turns our thin
 * wrapper into the bottleneck it was meant to avoid. A balance the library
 * itself changed is refreshed at once; the cache only covers changes made
 * outside it.
 *
 * @since 1.26.0
 */
public final class Economy {

    private Economy() {
        throw new AssertionError("No instances.");
    }

    static Logger logger = Logger.getLogger("ExyliaLib");

    // ------------------------------------------------------------ access

    /**
     * The operations of one named currency.
     *
     * @param id the currency's id, such as {@code "vault"} or {@code "points"}
     * @return the currency's view
     */
    public static @NotNull CurrencyView of(@NotNull String id) {
        return new CurrencyView(id);
    }

    /** Whether any economy is available. */
    public static boolean isAvailable() {
        return CurrencyRegistry.isAvailable();
    }

    /**
     * Registers a currency the library does not know about.
     *
     * @param provider the provider
     * @throws EconomyException when the id is already taken
     */
    public static void register(@NotNull CurrencyProvider provider) {
        CurrencyRegistry.register(provider);
    }

    /**
     * Removes a currency, for a plugin shutting down.
     *
     * @param id the currency's id
     */
    public static void unregister(@NotNull String id) {
        CurrencyRegistry.unregister(id);
    }

    /**
     * The ids of every registered currency, for diagnostics.
     *
     * @return the ids
     */
    public static @NotNull java.util.Set<String> currencies() {
        return CurrencyRegistry.providers().keySet();
    }

    // --------------------------------------------------------- default

    /** A balance in the default currency. */
    public static @NotNull BigDecimal balance(@NotNull UUID player) {
        return of(null).balance(player);
    }

    /** Whether a player has at least an amount in the default currency. */
    public static boolean has(@NotNull UUID player, @NotNull BigDecimal amount) {
        return of(null).has(player, amount);
    }

    /** Removes an amount from the default currency. */
    public static @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        return of(null).withdraw(player, amount);
    }

    /** Adds an amount to the default currency. */
    public static @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        return of(null).deposit(player, amount);
    }

    /**
     * Removes an amount from the default currency, answering only whether it
     * happened.
     *
     * <p>The natural way to buy something: {@code if (Economy.charge(buyer,
     * price))} give them the item. A charge that cannot be made changes nothing.
     *
     * @param player the player
     * @param amount the price
     * @return whether the amount was withdrawn
     */
    public static boolean charge(@NotNull UUID player, @NotNull BigDecimal amount) {
        return of(null).withdraw(player, amount).isSuccess();
    }

    /** Adds an amount to the default currency, answering only whether it happened. */
    public static boolean pay(@NotNull UUID player, @NotNull BigDecimal amount) {
        return of(null).deposit(player, amount).isSuccess();
    }

    /**
     * Moves an amount between two players in the default currency.
     *
     * @param from   the sender
     * @param to     the receiver
     * @param amount the amount
     * @return the outcome, including the partial state that must not be silent
     */
    public static @NotNull TransferResult transfer(
            @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
        return of(null).transfer(from, to, amount);
    }

    // ------------------------------------------------------------- view

    /**
     * The economy operations, bound to one currency.
     *
     * <p>The default view ({@link Economy#of(String) Economy.of(null)}) is what
     * {@code economy.yml} names; every other view is a currency chosen by id.
     * All of them share the validation, the cache and the transfer logic, so a
     * named currency behaves exactly like the default in everything except which
     * balance it touches.
     *
     * @since 1.26.0
     */
    public static final class CurrencyView {

        private final String id;

        private CurrencyView(@Nullable String id) {
            this.id = id;
        }

        /** A balance. */
        public @NotNull BigDecimal balance(@NotNull UUID player) {
            requirePlayer(player);
            return provider()
                    .map(p -> BalanceCache.balance(p.id(), player, () -> p.balance(player)))
                    .orElse(BigDecimal.ZERO);
        }

        /** Whether a player has at least an amount. */
        public boolean has(@NotNull UUID player, @NotNull BigDecimal amount) {
            requirePlayer(player);
            requireAmount(amount);
            return balance(player).compareTo(amount) >= 0;
        }

        /** Removes an amount. */
        public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
            requirePlayer(player);
            requireAmount(amount);
            Optional<CurrencyProvider> provider = provider();
            if (provider.isEmpty()) {
                return EconomyResponse.notAvailable();
            }
            EconomyResponse response = provider.get().withdraw(player, amount);
            if (response.isSuccess()) {
                BalanceCache.invalidate(provider.get().id(), player);
            }
            return response;
        }

        /** Adds an amount. */
        public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
            requirePlayer(player);
            requireAmount(amount);
            Optional<CurrencyProvider> provider = provider();
            if (provider.isEmpty()) {
                return EconomyResponse.notAvailable();
            }
            EconomyResponse response = provider.get().deposit(player, amount);
            if (response.isSuccess()) {
                BalanceCache.invalidate(provider.get().id(), player);
            }
            return response;
        }

        /** Sets a balance to an exact value. */
        public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount) {
            requirePlayer(player);
            requireNonNegative(amount);
            Optional<CurrencyProvider> provider = provider();
            if (provider.isEmpty()) {
                return EconomyResponse.notAvailable();
            }
            EconomyResponse response = provider.get().set(player, amount);
            if (response.isSuccess()) {
                BalanceCache.invalidate(provider.get().id(), player);
            }
            return response;
        }

        /** {@link Economy#charge(UUID, BigDecimal)}, for this currency. */
        public boolean charge(@NotNull UUID player, @NotNull BigDecimal amount) {
            return withdraw(player, amount).isSuccess();
        }

        /** {@link Economy#pay(UUID, BigDecimal)}, for this currency. */
        public boolean pay(@NotNull UUID player, @NotNull BigDecimal amount) {
            return deposit(player, amount).isSuccess();
        }

        /**
         * Moves an amount between two players.
         *
         * <p>Uses the provider's own transfer when it has one — PlayerPoints
         * moves points as a single operation. When it does not, the library does
         * it as withdraw, verify, deposit: the sender is charged only once their
         * balance has actually dropped, and the receiver is credited only after.
         * A deposit that then fails triggers a refund of what was withdrawn, and
         * if that refund is also refused the outcome is {@link
         * TransferResult.Type#PARTIAL}, which the library logs as an error rather
         * than return quietly — that is the state where money left one balance
         * and arrived at none.
         *
         * @param from   the sender
         * @param to     the receiver
         * @param amount the amount
         * @return the outcome
         */
        public @NotNull TransferResult transfer(
                @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
            requirePlayer(from);
            requirePlayer(to);
            requireAmount(amount);
            if (from.equals(to)) {
                return TransferResult.invalidAmount(amount);
            }

            Optional<CurrencyProvider> provider = provider();
            if (provider.isEmpty()) {
                return TransferResult.notAvailable();
            }
            CurrencyProvider currency = provider.get();

            TransferResult nativeResult = currency.transfer(from, to, amount);
            if (nativeResult != null) {
                if (nativeResult.isSuccess()) {
                    BalanceCache.invalidate(currency.id(), from);
                    BalanceCache.invalidate(currency.id(), to);
                }
                return nativeResult;
            }
            return transferManually(currency, from, to, amount);
        }

        /** The provider for this view's currency, with fallback applied. */
        private @NotNull Optional<CurrencyProvider> provider() {
            return CurrencyRegistry.resolve(id);
        }
    }

    // ------------------------------------------------------------- logic

    /**
     * Withdraw, verify, deposit, refund — for a currency with no native move.
     *
     * <p>The order is the whole point. Money is taken from the sender first and
     * only given to the receiver once the sender's balance has verifiably
     * dropped, so a deposit that invents money can never mint currency: the
     * receiver gets at most what left the sender. The dangerous case is the
     * inverse — paying the receiver before charging the sender — which is how a
     * failed charge still delivers the money.
     */
    private static TransferResult transferManually(
            CurrencyProvider currency, UUID from, UUID to, BigDecimal amount) {

        EconomyResponse withdrawn = currency.withdraw(from, amount);
        if (!withdrawn.isSuccess()) {
            if (withdrawn.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS) {
                return TransferResult.insufficientFunds(from, to, amount);
            }
            return TransferResult.withdrawFailed(from, to, amount, withdrawn.message());
        }
        BalanceCache.invalidate(currency.id(), from);

        // The sender is now down the amount. Credit the receiver; on failure,
        // refund what was taken rather than leave it gone.
        EconomyResponse deposited = currency.deposit(to, amount);
        if (deposited.isSuccess()) {
            BalanceCache.invalidate(currency.id(), to);
            return TransferResult.success(from, to, amount);
        }

        EconomyResponse refunded = currency.deposit(from, amount);
        if (refunded.isSuccess()) {
            BalanceCache.invalidate(currency.id(), from);
            logger.warning("Economy: deposit to " + to + " failed after charging "
                    + from + "; the " + amount + " was refunded. Reason: " + deposited.message());
            return TransferResult.withdrawFailed(from, to, amount,
                    "Deposit failed; sender was refunded");
        }

        // The sender is charged, the receiver has nothing, and the refund was
        // refused. Money is gone from one balance and in no other. This is not a
        // quiet failure: it is logged as an error with everything needed to
        // refund by hand, because it is the one state a player opens a ticket
        // about and the only one a silent boolean could hide.
        logger.severe("Economy: PARTIAL transfer. " + amount + " " + currency.id()
                + " was taken from " + from + " for " + to + ", but the deposit failed"
                + " (" + deposited.message() + ") and the refund failed ("
                + refunded.message() + "). Manual refund required.");
        return TransferResult.partial(from, to, amount,
                "Charged but not delivered; refund refused");
    }

    // --------------------------------------------------------- validation

    private static void requirePlayer(UUID player) {
        if (player == null) {
            throw new EconomyException("player must not be null");
        }
    }

    private static void requireAmount(BigDecimal amount) {
        if (amount == null) {
            throw new EconomyException("amount must not be null");
        }
        if (amount.signum() <= 0) {
            throw new EconomyException(
                    "amount must be positive, got " + amount + " — this is a caller bug, not a balance problem");
        }
    }

    private static void requireNonNegative(BigDecimal amount) {
        if (amount == null) {
            throw new EconomyException("amount must not be null");
        }
        if (amount.signum() < 0) {
            throw new EconomyException("amount must not be negative, got " + amount);
        }
    }
}
