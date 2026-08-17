package net.exylia.lib.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The outcome of one balance operation.
 *
 * <p>An economy call does not throw for the ordinary reasons it fails — a
 * player without enough money, a provider that is not there. Those are the
 * expected outcomes of a purchase, and they belong in a return value a command
 * can branch on, not in an exception that looks like a bug.
 *
 * <p>What would be a genuine bug — a negative amount, a null player — is a
 * programming error and throws before the provider is ever called.
 *
 * @since 1.26.0
 */
public final class EconomyResponse {

    /** Why an operation ended the way it did. */
    public enum Type {
        /** The operation happened. */
        SUCCESS,
        /** The player did not have enough for what was asked. */
        INSUFFICIENT_FUNDS,
        /** The amount itself was not usable. */
        INVALID_AMOUNT,
        /** No provider could serve the currency. */
        NOT_AVAILABLE,
        /** The provider was called and refused, for a reason it named. */
        FAILURE
    }

    private static final EconomyResponse INVALID_AMOUNT =
            new EconomyResponse(Type.INVALID_AMOUNT, false, "Invalid amount",
                    BigDecimal.ZERO, BigDecimal.ZERO);
    private static final EconomyResponse NOT_AVAILABLE =
            new EconomyResponse(Type.NOT_AVAILABLE, false, "Economy provider not available",
                    BigDecimal.ZERO, BigDecimal.ZERO);

    private final Type type;
    private final boolean success;
    private final String message;
    private final BigDecimal amount;
    private final BigDecimal balance;

    private EconomyResponse(Type type, boolean success, String message,
                            BigDecimal amount, BigDecimal balance) {
        this.type = type;
        this.success = success;
        this.message = message;
        this.amount = amount;
        this.balance = balance;
    }

    /**
     * The operation succeeded.
     *
     * @param amount  the amount that moved
     * @param balance the balance afterwards
     * @return the response
     */
    public static @NotNull EconomyResponse success(
            @NotNull BigDecimal amount, @NotNull BigDecimal balance) {
        return new EconomyResponse(Type.SUCCESS, true, null,
                amount, balance);
    }

    /**
     * The player did not have enough.
     *
     * @param amount  the amount that was asked for
     * @param balance what the player actually has
     * @return the response
     */
    public static @NotNull EconomyResponse insufficientFunds(
            @NotNull BigDecimal amount, @NotNull BigDecimal balance) {
        return new EconomyResponse(Type.INSUFFICIENT_FUNDS, false, "Insufficient funds",
                amount, balance);
    }

    /**
     * The provider refused, giving a reason.
     *
     * @param message the provider's reason
     * @return the response
     */
    public static @NotNull EconomyResponse failure(@NotNull String message) {
        return new EconomyResponse(Type.FAILURE, false, message,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** The amount was not usable. */
    public static @NotNull EconomyResponse invalidAmount() {
        return INVALID_AMOUNT;
    }

    /** No provider could serve the currency. */
    public static @NotNull EconomyResponse notAvailable() {
        return NOT_AVAILABLE;
    }

    /** Why the operation ended the way it did. */
    public @NotNull Type type() {
        return type;
    }

    /** Whether the operation happened. */
    public boolean isSuccess() {
        return success;
    }

    /**
     * The provider's reason when it refused, or {@code null}.
     *
     * @return the message
     */
    public @Nullable String message() {
        return message;
    }

    /**
     * The amount that moved, or was asked for.
     *
     * @return the amount
     */
    public @NotNull BigDecimal amount() {
        return amount;
    }

    /**
     * The balance after the operation, or the balance that was too low.
     *
     * @return the balance
     */
    public @NotNull BigDecimal balance() {
        return balance;
    }

    /**
     * A shortfall, when the operation failed for lack of funds.
     *
     * <p>How much more the player needed: the amount asked minus what they had.
     * Zero on any other outcome. A command answering "you need 12 more" does
     * not have to subtract.
     *
     * @return the shortfall, or zero
     */
    public @NotNull BigDecimal shortfall() {
        if (type != Type.INSUFFICIENT_FUNDS) {
            return BigDecimal.ZERO;
        }
        return amount.subtract(balance).max(BigDecimal.ZERO);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EconomyResponse that)) {
            return false;
        }
        return success == that.success
                && type == that.type
                && Objects.equals(message, that.message)
                && amount.compareTo(that.amount) == 0
                && balance.compareTo(that.balance) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, success, message, amount.stripTrailingZeros(),
                balance.stripTrailingZeros());
    }

    @Override
    public String toString() {
        return success
                ? "EconomyResponse{success, amount=" + amount + ", balance=" + balance + '}'
                : "EconomyResponse{" + type + (message == null ? "" : ", " + message) + '}';
    }
}
