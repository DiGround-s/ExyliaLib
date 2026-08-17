package net.exylia.lib.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A source of one currency.
 *
 * <p>Implement this to hand the library a currency it does not know about —
 * one a custom economy plugin tracks, one backed by a web service, one that is
 * just another database column. Register it with
 * {@link Economy#register(CurrencyProvider)} and every method of {@link Economy}
 * can take its {@link #id()}.
 *
 * <p>Only standard types appear here: a {@link UUID}, a {@link BigDecimal}, a
 * string. That is deliberate. A provider backed by Vault, by PlayerPoints or by
 * something that does not exist yet all implement the same interface, and none
 * of them need to know which Bukkit object stands behind a balance. The two
 * built-in providers adapt Vault and PlayerPoints to this exact contract.
 *
 * <h2>Precision</h2>
 * Every amount is a {@link BigDecimal}, because a balance is the one number a
 * {@code double} must not hold: adding {@code 0.1} to {@code 0.2} in a double is
 * {@code 0.30000000000000004}, and a shop that sums prices that way shows a
 * total a player can prove wrong. A provider whose own backend holds a double —
 * Vault does — converts through {@link BigDecimal#valueOf(double)}, which reads
 * the shortest decimal that round-trips rather than the binary noise underneath.
 *
 * <h2>Threading</h2>
 * Implementations must be safe from any thread. Balances are read on every tick
 * of every scoreboard of every player, and written from command handlers that
 * the library may run off the main thread.
 *
 * @since 1.26.0
 */
public interface CurrencyProvider {

    /**
     * The identifier this currency answers to.
     *
     * <p>Lowercase, stable, and unique across every registered provider. It is
     * the key a config writes and the value {@link Economy#of(String)} takes.
     *
     * @return the id, such as {@code "vault"} or {@code "points"}
     */
    @NotNull String id();

    /**
     * A name a player reads, such as {@code "Vault"} or {@code "PlayerPoints"}.
     *
     * @return the display name
     */
    @NotNull String displayName();

    /**
     * Whether this provider can serve requests right now.
     *
     * <p>Called when the library decides which currency answers an operation,
     * and again on the fallback path when a provider goes away. It should be
     * cheap to call: a flag, not a network round-trip.
     *
     * @return {@code true} when the underlying source is present
     */
    boolean isAvailable();

    /**
     * Reads a balance.
     *
     * @param player the player
     * @return the balance, never negative, never {@code null}
     */
    @NotNull BigDecimal balance(@NotNull UUID player);

    /**
     * Adds to a balance.
     *
     * <p>Amounts are positive and already validated by the library; a provider
     * is not expected to defend against a negative or a zero.
     *
     * @param player the player
     * @param amount the amount to add, greater than zero
     * @return the result, with the new balance when it succeeded
     */
    @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount);

    /**
     * Removes from a balance.
     *
     * <p>The library checks for sufficient funds before calling this, but a
     * provider must not rely on that check alone: another plugin touching the
     * same balance between the check and the call is the normal case, not a
     * race to be surprised by.
     *
     * @param player the player
     * @param amount the amount to remove, greater than zero
     * @return the result, with the new balance when it succeeded
     */
    @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount);

    /**
     * Sets a balance to an exact value.
     *
     * <p>Default implemented as a deposit or withdraw of the difference, which
     * is what a currency with no "set" operation — Vault — has to do. Override
     * it when the backend can set directly, as PlayerPoints can.
     *
     * @param player the player
     * @param amount the value to set, zero or more
     * @return the result, with the new balance when it succeeded
     */
    default @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount) {
        BigDecimal current = balance(player);
        int change = amount.compareTo(current);
        if (change > 0) {
            return deposit(player, amount.subtract(current));
        }
        if (change < 0) {
            return withdraw(player, current.subtract(amount));
        }
        return EconomyResponse.success(BigDecimal.ZERO, amount);
    }

    /**
     * Moves an amount between two players atomically, when the backend can.
     *
     * <p>PlayerPoints has a native {@code pay}; most currencies do not. The
     * default returns {@code null}, which tells the library to do the transfer
     * itself as withdraw, verify, deposit — never returning money to the sender
     * on a failed deposit, because that is how money is created from nothing.
     *
     * <p>Override it only when the backend performs the move as one operation.
     * A "transfer" that is really a withdraw then a deposit belongs in the
     * library, where the failure handling is written once.
     *
     * @param from   the sender
     * @param to     the receiver
     * @param amount the amount, greater than zero
     * @return the result when the backend did it, or {@code null} to let the
     *         library transfer instead
     */
    default @Nullable TransferResult transfer(
            @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
        return null;
    }

    /**
     * The name of one unit of this currency, such as {@code "dollar"} or
     * {@code "point"}.
     *
     * @param plural {@code true} for the plural form
     * @return the unit name
     */
    @NotNull String currencyName(boolean plural);

    /**
     * The symbol shown with an amount, such as {@code "$"} or {@code "★"}.
     *
     * @return the symbol
     */
    @NotNull String symbol();
}
