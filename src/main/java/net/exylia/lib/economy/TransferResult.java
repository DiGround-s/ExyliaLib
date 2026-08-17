package net.exylia.lib.economy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of paying one player from another.
 *
 * <p>A transfer is two balance operations, and the truth of it is more than a
 * boolean: it can succeed, fail before any money moved, or fail after the
 * sender was charged and the receiver never got it. That third outcome is the
 * one a plain {@code boolean} hides, and the one a player opens a ticket about.
 *
 * @since 1.26.0
 */
public final class TransferResult {

    /** Where a transfer ended. */
    public enum Type {
        /** The amount reached the receiver. */
        SUCCESS,
        /** The sender did not have enough. Nothing moved. */
        INSUFFICIENT_FUNDS,
        /** The amount itself was not usable. Nothing moved. */
        INVALID_AMOUNT,
        /** No provider could serve the currency. Nothing moved. */
        NOT_AVAILABLE,
        /** The sender's withdraw was refused. Nothing moved. */
        WITHDRAW_FAILED,
        /**
         * The sender was charged but the receiver never got the amount, and the
         * refund was refused. Money left one balance and arrived at none.
         *
         * <p>This is the outcome that must not be silent. It carries everything
         * needed to refund by hand, and the library logs it as an error the
         * moment it happens.
         */
        PARTIAL
    }

    private final Type type;
    private final UUID from;
    private final UUID to;
    private final BigDecimal amount;
    private final String message;

    private TransferResult(Type type, UUID from, UUID to,
                           BigDecimal amount, String message) {
        this.type = type;
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.message = message;
    }

    /** The amount reached the receiver. */
    public static @NotNull TransferResult success(
            @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
        return new TransferResult(Type.SUCCESS, from, to, amount, null);
    }

    /** The sender did not have enough. */
    public static @NotNull TransferResult insufficientFunds(
            @NotNull UUID from, @NotNull UUID to, @NotNull BigDecimal amount) {
        return new TransferResult(Type.INSUFFICIENT_FUNDS, from, to, amount, "Insufficient funds");
    }

    /** The amount was not usable. */
    public static @NotNull TransferResult invalidAmount(@NotNull BigDecimal amount) {
        return new TransferResult(Type.INVALID_AMOUNT, null, null, amount, "Invalid amount");
    }

    /** No provider could serve the currency. */
    public static @NotNull TransferResult notAvailable() {
        return new TransferResult(Type.NOT_AVAILABLE, null, null,
                BigDecimal.ZERO, "Economy provider not available");
    }

    /** The sender's withdraw was refused. */
    public static @NotNull TransferResult withdrawFailed(
            @NotNull UUID from, @NotNull UUID to,
            @NotNull BigDecimal amount, @Nullable String reason) {
        return new TransferResult(Type.WITHDRAW_FAILED, from, to, amount, reason);
    }

    /** The sender was charged but the receiver never got the amount. */
    public static @NotNull TransferResult partial(
            @NotNull UUID from, @NotNull UUID to,
            @NotNull BigDecimal amount, @Nullable String reason) {
        return new TransferResult(Type.PARTIAL, from, to, amount, reason);
    }

    /** Where the transfer ended. */
    public @NotNull Type type() {
        return type;
    }

    /** Whether the amount reached the receiver. */
    public boolean isSuccess() {
        return type == Type.SUCCESS;
    }

    /**
     * Whether the sender's balance went down without the amount arriving.
     *
     * <p>Only true on {@link Type#PARTIAL}. It is the one state a caller must
     * not treat as an ordinary failure, because somebody's money is missing.
     *
     * @return {@code true} when money was lost
     */
    public boolean isPartial() {
        return type == Type.PARTIAL;
    }

    /** The sender, when there was one. */
    public @Nullable UUID from() {
        return from;
    }

    /** The receiver, when there was one. */
    public @Nullable UUID to() {
        return to;
    }

    /** The amount that was asked to move. */
    public @NotNull BigDecimal amount() {
        return amount;
    }

    /** The provider's reason when it refused, or {@code null}. */
    public @Nullable String message() {
        return message;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransferResult that)) {
            return false;
        }
        return type == that.type
                && Objects.equals(from, that.from)
                && Objects.equals(to, that.to)
                && amount.compareTo(that.amount) == 0
                && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, from, to, amount.stripTrailingZeros(), message);
    }

    @Override
    public String toString() {
        return "TransferResult{" + type
                + (message == null ? "" : ", " + message)
                + (type == Type.SUCCESS ? ", amount=" + amount : "")
                + '}';
    }
}
