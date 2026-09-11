package net.exylia.lib.api.capture.event;

import net.exylia.lib.api.capture.CaptureEndReason;
import net.exylia.lib.api.capture.CaptureEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * A capture run is over.
 *
 * <p>Fired once per run, after the mode has settled it — the result broadcast,
 * the end commands run and the winner's rewards handed out — and just before
 * the run stops being listed. While a handler runs,
 * {@link net.exylia.lib.api.capture.CaptureService#scores(String)} still
 * answers for it, which is how to read a result shared among several.
 *
 * <p>Not cancellable: the rewards are already in players' hands.
 *
 * <p>Called on the global thread for a run ended by its zones or its clock, or
 * on the thread that stopped it. On Folia neither is a single main thread, so
 * anything touching the wider world has to be scheduled.
 *
 * @since 1.3.0
 */
public class CaptureEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CaptureEvent event;
    private final UUID winner;
    private final CaptureEndReason reason;

    /**
     * Creates the event.
     *
     * @param event  the run, as it ended
     * @param winner the one player it was ended in favour of, or {@code null}
     * @param reason why it ended
     */
    public CaptureEndEvent(@NotNull CaptureEvent event, @Nullable UUID winner,
                           @NotNull CaptureEndReason reason) {
        super(!Bukkit.isPrimaryThread());
        this.event = event;
        this.winner = winner;
        this.reason = reason;
    }

    /**
     * The run, as it was when it ended.
     *
     * @return a snapshot of the run
     */
    @NotNull
    public CaptureEvent getEvent() {
        return event;
    }

    /**
     * The one player the run was ended in favour of.
     *
     * <p>The player who was just handed the winner rewards. Empty when nobody
     * won, and also when the mode settles its result among several rather than
     * for one — conquest's clans, a payload escorted home — or from the scores
     * of a run that timed out or was stopped. Those modes credit and reward
     * their players themselves, so read the scores for them instead.
     *
     * @return the winner, when the mode names one
     */
    @NotNull
    public Optional<UUID> getWinner() {
        return Optional.ofNullable(winner);
    }

    /**
     * Why it ended.
     *
     * @return the reason
     */
    @NotNull
    public CaptureEndReason getReason() {
        return reason;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
