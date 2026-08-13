package net.exylia.lib.action;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The outcome of one action or sequence.
 *
 * <p>{@link Status#SUCCESS} is the only status that advances a sequence. STOP
 * is intentional control flow, DENIED is a failed requirement, and FAILED is
 * a defect or invalid input. No arbitrary metadata map and no execution UUID
 * are allocated for every click.
 *
 * @since 1.20.0
 */
public record ActionResult(@NotNull Status status, @Nullable String reason,
                           @Nullable Throwable error) {
    public enum Status { SUCCESS, STOP, DENIED, FAILED }

    private static final ActionResult SUCCESS = new ActionResult(Status.SUCCESS, null, null);
    private static final ActionResult STOP = new ActionResult(Status.STOP, null, null);

    public static @NotNull ActionResult success() { return SUCCESS; }
    public static @NotNull ActionResult stop() { return STOP; }
    public static @NotNull ActionResult stop(@NotNull String reason) {
        return new ActionResult(Status.STOP, reason, null);
    }
    public static @NotNull ActionResult denied(@NotNull String reason) {
        return new ActionResult(Status.DENIED, reason, null);
    }
    public static @NotNull ActionResult failed(@NotNull String reason) {
        return new ActionResult(Status.FAILED, reason, null);
    }
    public static @NotNull ActionResult failed(@NotNull Throwable error) {
        return new ActionResult(Status.FAILED, error.getMessage(), error);
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean continues() { return status == Status.SUCCESS; }
}
