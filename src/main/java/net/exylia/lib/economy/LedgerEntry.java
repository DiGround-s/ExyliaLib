package net.exylia.lib.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a player's history in a stored currency.
 *
 * <p>Kept for every operation a stored currency applies, including the ones
 * that arrived from another server. Read back with
 * {@link Economy#history(String, UUID, int)}.
 *
 * @param currency     the currency id
 * @param player       whose balance
 * @param delta        what moved; negative for a withdrawal
 * @param balanceAfter what the balance read once it had
 * @param reason       the {@link Transaction#reason()} it was made with
 * @param initiator    who caused it, or {@code null}
 * @param server       which server applied it
 * @param at           when, in epoch milliseconds
 * @since 1.149.0
 */
public record LedgerEntry(
        @NotNull String currency,
        @NotNull UUID player,
        @NotNull BigDecimal delta,
        @NotNull BigDecimal balanceAfter,
        @NotNull String reason,
        @Nullable UUID initiator,
        @NotNull String server,
        long at) {

    /** Whether money came in rather than went out. */
    public boolean isDeposit() {
        return delta.signum() > 0;
    }
}
