package net.exylia.lib.api.practice.event;

import net.exylia.lib.api.practice.MatchMode;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.UUID;

/**
 * A match is about to be built.
 *
 * <p>Fired for every kind of match — a queue pairing, an accepted duel, a party
 * fight, practice against bots — once its players are chosen and claimed, and
 * before an arena is looked for. Nothing exists yet: no arena, no match id, and
 * nobody has moved. That is what makes it the one moment a match can be refused
 * cleanly, and what a tournament holding players for its own bracket, or a
 * server closing practice ahead of a restart, listens to.
 *
 * <p>Cancelling it sends every player back to the lobby, as a match that found
 * no arena does, and tells none of them anything: the reason is the canceller's
 * to give. A player the queue had paired is not put back in the queue.
 *
 * <p>Letting it through is not a promise either. A match that finds no free
 * arena, or loses a player while its arena is copied, never exists, and nothing
 * further is fired for it. {@link PracticeMatchStartEvent} is the first event
 * that carries a match id.
 *
 * @since 1.3.0
 */
public class PracticeMatchCreateEvent extends PracticeEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MatchMode mode;
    private final String kitId;
    private final List<UUID> players;
    private final boolean ranked;

    private boolean cancelled;

    public PracticeMatchCreateEvent(@NotNull MatchMode mode, @NotNull String kitId,
                                    @NotNull List<UUID> players, boolean ranked) {
        this.mode = mode;
        this.kitId = kitId;
        this.players = List.copyOf(players);
        this.ranked = ranked;
    }

    /** How the match is being put together. */
    public @NotNull MatchMode mode() {
        return mode;
    }

    /** The kit it will be fought with. */
    public @NotNull String kitId() {
        return kitId;
    }

    /**
     * Everybody who will fight in it. Only the players: the opponents in a bot
     * mode are not here, because they have not been spawned yet.
     */
    public @NotNull @Unmodifiable List<UUID> players() {
        return players;
    }

    /** Whether the result will move ELO. */
    public boolean ranked() {
        return ranked;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
