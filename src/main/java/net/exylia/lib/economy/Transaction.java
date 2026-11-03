package net.exylia.lib.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Why money moved, and who moved it.
 *
 * <p>Every balance operation may carry one. A stored currency writes it into
 * the ledger, the {@link BalanceChangeEvent} carries it to whoever listens,
 * and an admin reading {@code /economy history} sees {@code market:buy} next
 * to the amount rather than a number with no story.
 *
 * <pre>{@code
 * Economy.of("coins").withdraw(buyer, price, Transaction.of("market:buy"));
 * Economy.of("coins").deposit(target, amount, Transaction.of("admin:give").by(staff));
 * }</pre>
 *
 * <p>A reason is short, lower-case and shaped {@code <plugin or module>:<what>}.
 * It is free text; nothing has to be declared.
 *
 * @param reason    what happened, such as {@code market:buy}
 * @param initiator who caused it, or {@code null} for the system
 * @since 1.150.0
 */
public record Transaction(@NotNull String reason, @Nullable UUID initiator) {

    /** What an operation carries when the caller said nothing. */
    public static final Transaction NONE = new Transaction("api", null);

    /** How long a reason may be, because the ledger stores it. */
    public static final int REASON_LENGTH = 64;

    public Transaction {
        String clean = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) clean = "api";
        if (clean.length() > REASON_LENGTH) clean = clean.substring(0, REASON_LENGTH);
        reason = clean;
    }

    /** A transaction with a reason and nobody in particular behind it. */
    public static @NotNull Transaction of(@NotNull String reason) {
        return new Transaction(reason, null);
    }

    /**
     * The transaction a library call is carrying through Vault on this thread, or {@code null}.
     *
     * <p>Vault's API has no room for a reason, so a change made with {@code Economy.of("vault")}
     * reaches the Vault backend bare. A backend or a pass-through in front of Vault calls this
     * while handling the call to keep the caller's reason ({@code sell:shop}) instead of guessing
     * one from the stack ({@code vault:<plugin>}).
     *
     * @since 1.460.0
     */
    public static @Nullable Transaction relayed() {
        return net.exylia.lib.economy.internal.VaultCurrency.relayed();
    }

    /** The same transaction, attributed to somebody. */
    public @NotNull Transaction by(@Nullable UUID initiator) {
        return new Transaction(reason, initiator);
    }
}
