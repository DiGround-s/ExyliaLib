package net.exylia.lib.util.teleport;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * The answer to accepting a request: what happened, and the teleport it made.
 *
 * <pre>{@code
 * TpaAcceptance accepted = teleports.accept(player, sender);
 *
 * accepted.teleport().ifPresent(request -> request
 *         .warmup(config.tpaWarmup())
 *         .cooldown("tpa", config.tpaCooldown())
 *         .onArrive(config.tpaArrived())
 *         .start());
 * }</pre>
 *
 * <h2>Why the teleport is not started</h2>
 * The module knows who moves and where; it has no idea whether this server
 * makes people stand still for three seconds first, charges a cooldown, or
 * plays anything. Handing back a started teleport would answer all three
 * questions on the caller's behalf and be wrong on most servers, so the
 * request arrives described and unstarted and the caller finishes it.
 *
 * <p>The eager form exists for the callers with nothing to add, and it is
 * written in terms of this one rather than beside it.
 *
 * @param outcome  what happened
 * @param teleport the teleport to configure and start, present only when the
 *                 outcome is {@link TpaOutcome#ACCEPTED}
 * @since 1.34.0
 */
public record TpaAcceptance(@NotNull TpaOutcome outcome,
                            @NotNull Optional<TeleportRequest> teleport) {

    public TpaAcceptance {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(teleport, "teleport");
    }

    /** An acceptance that produced nothing, because nothing was accepted. */
    static @NotNull TpaAcceptance of(@NotNull TpaOutcome outcome) {
        return new TpaAcceptance(outcome, Optional.empty());
    }

    /** An accepted request, with the teleport it turned into. */
    static @NotNull TpaAcceptance accepted(@NotNull TeleportRequest request) {
        return new TpaAcceptance(TpaOutcome.ACCEPTED, Optional.of(request));
    }

    /** Whether a teleport was produced. */
    public boolean isAccepted() {
        return outcome == TpaOutcome.ACCEPTED;
    }
}
