package net.exylia.lib.api.survival.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.UUID;

/**
 * A duel in a duel room has been decided.
 *
 * <p>Fired when one fighter is left standing, or nobody is, and before the
 * room's rewards reach the winner — the hook for counting wins, paying out a
 * quest or posting a result. The room stays closed for its loot time after
 * this, so the players are usually still inside.
 *
 * <p>Only a duel that was fought fires this. One called off during its
 * countdown, because somebody stepped out or the room was edited, never
 * started and has no result to report.
 *
 * <p>Not cancellable: the last fighter has already fallen or fled.
 *
 * <p>Called on whichever thread noticed the end: the rooms' once-a-second
 * timer on the global thread, or the region thread of the player whose death,
 * disconnect or exit decided it. On Folia neither is guaranteed to own the
 * winner, so anything touching a player has to be scheduled on that player.
 *
 * @since 1.3.0
 */
public class DuelRoomEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String roomId;
    private final UUID winner;
    private final List<UUID> participants;

    /**
     * Creates the event.
     *
     * @param roomId       the room the duel was fought in
     * @param winner       the last fighter standing, or {@code null} for a draw
     * @param participants everybody who started the duel
     */
    public DuelRoomEndEvent(@NotNull String roomId, @Nullable UUID winner, @NotNull List<UUID> participants) {
        super(!Bukkit.isPrimaryThread());
        this.roomId = roomId;
        this.winner = winner;
        this.participants = List.copyOf(participants);
    }

    /**
     * The room the duel was fought in.
     *
     * @return the id the room was created with
     */
    @NotNull
    public String getRoomId() {
        return roomId;
    }

    /**
     * Who won.
     *
     * @return the last fighter standing, or {@code null} for a draw: nobody
     *         left, or the fight running out of time with more than one alive
     */
    @Nullable
    public UUID getWinner() {
        return winner;
    }

    /**
     * Everybody who started the duel, the winner included.
     *
     * <p>Some may have left the server since: a player who disconnects forfeits
     * but still took part.
     *
     * @return the participants, in the order they entered
     */
    @NotNull
    @Unmodifiable
    public List<UUID> getParticipants() {
        return participants;
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
