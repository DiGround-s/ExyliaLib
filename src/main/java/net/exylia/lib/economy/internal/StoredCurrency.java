package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A currency the library keeps itself.
 *
 * <h2>Who may write a balance</h2>
 * The server the player is on. Their balance is loaded into memory when they
 * join, every operation is applied to that number and written through, and
 * the number is dropped when they leave. A server they are not on never
 * touches the snapshot: it writes a {@link PendingRow} and the owner folds it
 * in. So a balance has one writer at a time, and the ordinary "two servers
 * saved different totals" bug cannot happen.
 *
 * <h2>What an operation on somebody who is not here means</h2>
 * A deposit is queued and applied when they are next loaded anywhere: it
 * always lands. A withdrawal is queued too, and floored at zero when it lands
 * — the library cannot promise not to overdraw somebody it cannot see, and
 * says so in the response's balance, which is zero for "unknown". Every
 * caller in the ecosystem that withdraws does it from a player standing in
 * front of it; the queue exists for the admin who takes money from somebody
 * who logged off.
 *
 * <h2>Threads</h2>
 * Every read-modify-write is under one lock per currency. Operations arrive
 * from the game thread, from database callbacks folding in pending rows and
 * from placeholders, and two of them reading the same balance and both writing
 * it back is how a deposit vanishes. The lock is held for a map lookup and an
 * add; nothing under it touches the database.
 */
public final class StoredCurrency implements CurrencyProvider {

    private final CurrencyFile.Stored settings;
    private final StoredEconomy economy;

    /** The balances of players loaded here, which are the only ones written. */
    private final Map<UUID, BigDecimal> loaded = new ConcurrentHashMap<>();

    StoredCurrency(CurrencyFile.Stored settings, StoredEconomy economy) {
        this.settings = settings;
        this.economy = economy;
    }

    public @NotNull CurrencyFile.Stored settings() {
        return settings;
    }

    @Override
    public @NotNull String id() {
        return settings.id();
    }

    @Override
    public @NotNull String displayName() {
        return settings.info().namePlural();
    }

    @Override
    public @NotNull CurrencyInfo info() {
        return settings.info();
    }

    @Override
    public boolean isAvailable() {
        return economy.isReady();
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        return plural ? settings.info().namePlural() : settings.info().name();
    }

    @Override
    public @NotNull String symbol() {
        return settings.info().symbol();
    }

    // ------------------------------------------------------------- memory

    /** Whether this player's balance is held here, and so may be written. */
    public boolean isLoaded(@NotNull UUID player) {
        return loaded.containsKey(player);
    }

    /** Puts a balance in memory: the player is now this server's. */
    void load(@NotNull UUID player, @NotNull BigDecimal amount) {
        loaded.put(player, clamp(amount));
    }

    /** Forgets a player who has left. */
    void unload(@NotNull UUID player) {
        loaded.remove(player);
    }

    /** What is in memory, or {@code null} for a player who is not here. */
    BigDecimal held(@NotNull UUID player) {
        return loaded.get(player);
    }

    // --------------------------------------------------------- operations

    @Override
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        BigDecimal here = loaded.get(player);
        if (here != null) return here;
        return economy.snapshot(id(), player);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        return deposit(player, amount, Transaction.NONE);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount,
                                            @NotNull Transaction transaction) {
        BigDecimal scaled = info().scale(amount);
        if (scaled.signum() <= 0) return EconomyResponse.invalidAmount();
        synchronized (this) {
            BigDecimal current = loaded.get(player);
            if (current == null) {
                economy.queue(this, player, scaled, false, transaction);
                return EconomyResponse.success(scaled, BigDecimal.ZERO);
            }
            BigDecimal after = clamp(current.add(scaled));
            BigDecimal moved = after.subtract(current);
            if (moved.signum() <= 0) {
                return EconomyResponse.failure("The balance is at its ceiling of " + info().format(settings.max()) + ".");
            }
            apply(player, after, moved, transaction);
            return EconomyResponse.success(moved, after);
        }
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        return withdraw(player, amount, Transaction.NONE);
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount,
                                             @NotNull Transaction transaction) {
        BigDecimal scaled = info().scale(amount);
        if (scaled.signum() <= 0) return EconomyResponse.invalidAmount();
        synchronized (this) {
            BigDecimal current = loaded.get(player);
            if (current == null) {
                economy.queue(this, player, scaled.negate(), false, transaction);
                return EconomyResponse.success(scaled, BigDecimal.ZERO);
            }
            if (current.compareTo(scaled) < 0) {
                return EconomyResponse.insufficientFunds(scaled, current);
            }
            BigDecimal after = current.subtract(scaled);
            apply(player, after, scaled.negate(), transaction);
            return EconomyResponse.success(scaled, after);
        }
    }

    @Override
    public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount) {
        return set(player, amount, Transaction.NONE);
    }

    @Override
    public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount,
                                        @NotNull Transaction transaction) {
        BigDecimal after = clamp(info().scale(amount));
        synchronized (this) {
            BigDecimal current = loaded.get(player);
            if (current == null) {
                economy.queue(this, player, after, true, transaction);
                return EconomyResponse.success(after, after);
            }
            apply(player, after, after.subtract(current), transaction);
            return EconomyResponse.success(after, after);
        }
    }

    /**
     * Folds a queued change into a loaded balance.
     *
     * @return what actually moved, which is less than asked when a withdrawal
     *         ran into zero or a deposit into the ceiling
     */
    BigDecimal applyPending(@NotNull UUID player, @NotNull PendingRow pending) {
        synchronized (this) {
            BigDecimal current = loaded.get(player);
            if (current == null) return BigDecimal.ZERO;
            BigDecimal after = pending.absolute()
                    ? clamp(pending.amount())
                    : clamp(current.add(pending.amount()));
            BigDecimal moved = after.subtract(current);
            UUID initiator = pending.initiator() == null ? null : UUID.fromString(pending.initiator());
            apply(player, after, moved, new Transaction(pending.reason(), initiator));
            return moved;
        }
    }

    /** Writes a new balance to memory, to the snapshot and to the ledger. */
    private void apply(UUID player, BigDecimal after, BigDecimal moved, Transaction transaction) {
        loaded.put(player, after);
        economy.written(this, player, after, moved, transaction);
    }

    /** Never below zero, never above the ceiling, never finer than the currency. */
    BigDecimal clamp(BigDecimal amount) {
        BigDecimal scaled = info().scale(amount.max(BigDecimal.ZERO));
        return settings.isCapped() ? scaled.min(info().scale(settings.max())) : scaled;
    }
}
