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

    /**
     * How a currency presents itself, with the owner's overlay applied.
     *
     * <p>For the default currency, pass {@code null} or the empty id. A
     * currency nothing registered still gets a description — its id read as a
     * name — so a menu never draws a blank.
     *
     * @param id the currency's id, or {@code null} for the default
     * @return the description
     * @since 1.150.0
     */
    public static @NotNull CurrencyInfo info(@Nullable String id) {
        Optional<CurrencyProvider> provider = CurrencyRegistry.resolve(id);
        String resolved = provider.map(CurrencyProvider::id)
                .orElse(id == null || id.isEmpty() ? CurrencyRegistry.defaultId() : id);
        CurrencyInfo base = provider.map(CurrencyProvider::info)
                .orElseGet(() -> CurrencyInfo.of(resolved, "", "", ""));
        return base.overlaid(CurrencyRegistry.overlay(resolved));
    }

    /**
     * An amount of a currency, written the way that currency writes it.
     *
     * @param id     the currency's id, or {@code null} for the default
     * @param amount the amount
     * @return the text, such as {@code $1,250.00} or {@code 3 Tokens}
     * @since 1.150.0
     */
    public static @NotNull String format(@Nullable String id, @NotNull BigDecimal amount) {
        return info(id).format(amount);
    }

    /**
     * An amount of a currency, written short.
     *
     * @since 1.150.0
     */
    public static @NotNull String formatCompact(@Nullable String id, @NotNull BigDecimal amount) {
        return info(id).formatCompact(amount);
    }

    /**
     * A player's most recent lines in a stored currency's ledger, newest
     * first.
     *
     * <p>Empty for a currency that keeps no ledger — Vault, PlayerPoints, a
     * plugin's own — and completes off the server thread.
     *
     * @param id     the currency's id
     * @param player whose history
     * @param limit  how many lines at most
     * @return the lines
     * @since 1.150.0
     */
    public static @NotNull java.util.concurrent.CompletableFuture<java.util.List<LedgerEntry>> history(
            @NotNull String id, @NotNull UUID player, int limit) {
        return net.exylia.lib.economy.internal.StoredEconomy.history(id, player, limit);
    }

    /**
     * The richest players in a stored currency, richest first.
     *
     * <p>Cached for a minute, because a leaderboard on a scoreboard is read
     * every tick. Empty for a currency that is not stored by the library.
     *
     * @param id    the currency's id
     * @param limit how many at most
     * @return the entries
     * @since 1.150.0
     */
    public static @NotNull java.util.List<TopEntry> top(@NotNull String id, int limit) {
        return net.exylia.lib.economy.internal.StoredEconomy.top(id, limit);
    }

    /**
     * Swaps an amount of one currency for another at the configured rate.
     *
     * <p>The rate is the one {@code currencies.yml} sets on the source
     * currency for the target. The source is withdrawn first, the target
     * deposited second, and a failed deposit refunds the source — the same
     * order a transfer between players uses.
     *
     * @param player who is exchanging
     * @param from   the currency given
     * @param to     the currency received
     * @param amount how much of {@code from}
     * @return the outcome, with the amount of {@code to} received as its amount
     * @since 1.150.0
     */
    public static @NotNull EconomyResponse exchange(@NotNull UUID player, @NotNull String from,
                                                    @NotNull String to, @NotNull BigDecimal amount) {
        return net.exylia.lib.economy.internal.StoredEconomy.exchange(player, from, to, amount);
    }

    /**
     * The rules {@code currencies.yml} sets on a stored currency: aliases,
     * limits, transfer and exchange terms.
     *
     * <p>Empty for a currency the library does not store. What a plugin that
     * puts commands on a currency reads — the library itself registers none.
     *
     * @since 1.151.0
     */
    public static @NotNull Optional<CurrencyRules> rules(@NotNull String id) {
        return net.exylia.lib.economy.internal.StoredEconomy.rules(id);
    }

    /**
     * What kind of thing a currency is.
     *
     * @since 1.151.0
     */
    public static @NotNull Kind kind(@NotNull String id) {
        return net.exylia.lib.economy.internal.StoredEconomy.kind(id);
    }

    /** The kinds of currency, for a plugin deciding which ones fit a purpose. */
    public enum Kind {
        /** Kept by the library in its own table: works offline and across servers. */
        STORED,
        /** An item in the player's inventory. Read and paid while they are here; paid to an absent player on their next join. */
        ITEM,
        /** Experience levels or points. Read and paid while they are here; paid to an absent player on their next join. */
        EXPERIENCE,
        /** Vault, PlayerPoints or a plugin's own provider. */
        EXTERNAL,
        /** Nothing registered under that id. */
        UNKNOWN
    }

    /**
     * Reads an amount the way players type them: {@code 100}, {@code 2.5k},
     * {@code 1m}, {@code 3b}.
     *
     * @return the amount, or {@code null} when the text is not a positive one
     * @since 1.151.0
     */
    public static @Nullable BigDecimal parseAmount(@Nullable String typed) {
        if (typed == null || typed.isBlank()) return null;
        String text = typed.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "");
        BigDecimal scale = BigDecimal.ONE;
        switch (text.charAt(text.length() - 1)) {
            case 'k' -> scale = BigDecimal.valueOf(1_000);
            case 'm' -> scale = BigDecimal.valueOf(1_000_000);
            case 'b' -> scale = BigDecimal.valueOf(1_000_000_000);
            default -> { }
        }
        if (scale.compareTo(BigDecimal.ONE) != 0) text = text.substring(0, text.length() - 1);
        try {
            BigDecimal value = new BigDecimal(text).multiply(scale);
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** One line of a leaderboard. */
    public record TopEntry(int position, @NotNull UUID player, @NotNull String name,
                           @NotNull BigDecimal amount) {
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

        /**
         * A balance, fetched rather than remembered.
         *
         * <p>{@link #balance(UUID)} answers from what is in memory, which for
         * a stored currency is the players who are on this server: everybody
         * else reads as zero. This one goes to the database for them, so a
         * command about somebody offline says a true number.
         *
         * <p>Completes off the server thread. Hop back before touching the
         * world with the answer.
         *
         * @param player the player
         * @return their balance
         * @since 1.154.0
         */
        public @NotNull java.util.concurrent.CompletableFuture<BigDecimal> balanceLater(@NotNull UUID player) {
            requirePlayer(player);
            // Asked, never peeked: the cache may hold the placeholder zero a
            // memory read leaves behind for a player nothing here holds.
            return provider()
                    .map(p -> p.balanceLater(player).thenApply(amount -> {
                        BalanceCache.remember(p.id(), player, amount);
                        return amount;
                    }))
                    .orElseGet(() -> java.util.concurrent.CompletableFuture.completedFuture(BigDecimal.ZERO));
        }

        /** Whether a player has at least an amount. */
        public boolean has(@NotNull UUID player, @NotNull BigDecimal amount) {
            requirePlayer(player);
            requireAmount(amount);
            return balance(player).compareTo(amount) >= 0;
        }

        /** Removes an amount. */
        public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
            return withdraw(player, amount, Transaction.NONE);
        }

        /**
         * Removes an amount, saying why.
         *
         * @since 1.150.0
         */
        public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount,
                                                 @NotNull Transaction transaction) {
            requirePlayer(player);
            requireAmount(amount);
            Optional<CurrencyProvider> provider = provider();
            if (provider.isEmpty()) {
                return EconomyResponse.notAvailable();
            }
            EconomyResponse response = provider.get().withdraw(player, amount, transaction);
            if (response.isSuccess()) {
                changed(provider.get(), player, response.balance().add(response.amount()), response.balance(), transaction);
            }
            return response;
        }

        public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
            return deposit(player, amount, Transaction.NONE);
        }

        /**
         * Adds an amount, saying why.
         *
         * @since 1.150.0
         */
        public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount,
                                                @NotNull Transaction transaction) {
            requirePlayer(player);
            requireAmount(amount);
            Optional<CurrencyProvider> provider = provider();
            if (provider.isEmpty()) {
                return EconomyResponse.notAvailable();
            }
            EconomyResponse response = provider.get().deposit(player, amount, transaction);
            if (response.isSuccess()) {
                changed(provider.get(), player, response.balance().subtract(response.amount()), response.balance(), transaction);
            }
            return response;
        }

        public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount) {
            return set(player, amount, Transaction.NONE);
        }

        /**
         * Sets a balance, saying why.
         *
         * @since 1.150.0
         */
        public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount,
                                            @NotNull Transaction transaction) {
            requirePlayer(player);
            requireNonNegative(amount);
            Optional<CurrencyProvider> provider = provider();
            if (provider.isEmpty()) {
                return EconomyResponse.notAvailable();
            }
            BigDecimal before = balance(player);
            EconomyResponse response = provider.get().set(player, amount, transaction);
            if (response.isSuccess()) {
                changed(provider.get(), player, before, response.balance(), transaction);
            }
            return response;
        }

        /**
         * The currency this view is bound to, as it presents itself.
         *
         * @since 1.150.0
         */
        public @NotNull CurrencyInfo info() {
            return Economy.info(id);
        }

        /**
         * An amount, written the way this currency writes it.
         *
         * @since 1.150.0
         */
        public @NotNull String format(@NotNull BigDecimal amount) {
            return info().format(amount);
        }

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
            return transfer(from, to, amount, Transaction.of("transfer").by(from));
        }

        /**
         * Moves an amount between two players, saying why.
         *
         * @since 1.150.0
         */
        public @NotNull TransferResult transfer(
                @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount,
                @NotNull Transaction transaction) {
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
            return transferManually(currency, from, to, amount, transaction);
        }

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
    /**
     * Drops the cached balance and tells the server.
     *
     * <p>The one place a change becomes visible: the cache is the only thing
     * that could show a stale number, and the event is how anything else
     * hears about it.
     */
    private static void changed(CurrencyProvider currency, UUID player, BigDecimal before,
                                BigDecimal after, Transaction transaction) {
        BalanceCache.invalidate(currency.id(), player);
        try {
            org.bukkit.Bukkit.getPluginManager().callEvent(
                    new BalanceChangeEvent(player, currency.id(), before.max(BigDecimal.ZERO), after, transaction));
        } catch (RuntimeException | LinkageError noServer) {
            // No server behind this call — a test, a tool — and nobody to tell.
        }
    }

    private static TransferResult transferManually(
            CurrencyProvider currency, UUID from, UUID to, BigDecimal amount, Transaction transaction) {

        EconomyResponse withdrawn = currency.withdraw(from, amount, transaction);
        if (!withdrawn.isSuccess()) {
            if (withdrawn.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS) {
                return TransferResult.insufficientFunds(from, to, amount);
            }
            return TransferResult.withdrawFailed(from, to, amount, withdrawn.message());
        }
        BalanceCache.invalidate(currency.id(), from);

        // The sender is now down the amount. Credit the receiver; on failure,
        // refund what was taken rather than leave it gone.
        changed(currency, from, withdrawn.balance().add(amount), withdrawn.balance(), transaction);
        EconomyResponse deposited = currency.deposit(to, amount, transaction);
        if (deposited.isSuccess()) {
            changed(currency, to, deposited.balance().subtract(amount), deposited.balance(), transaction);
            return TransferResult.success(from, to, amount);
        }

        EconomyResponse refunded = currency.deposit(from, amount, Transaction.of("transfer:refund"));
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
