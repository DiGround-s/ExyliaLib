package net.exylia.lib.api.practice.event;

import net.exylia.lib.api.practice.Party;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player is leaving a party.
 *
 * <p>Fired just before they are removed, whichever way they go. A party that is
 * disbanded fires one of these per member, the leader included, and a leader
 * leaving a party with nobody else in it is a {@link Reason#LEFT} that also ends
 * the party.
 *
 * <p>The player is a UUID because a kicked player may not be online.
 *
 * @since 1.3.0
 */
public class PracticePartyLeaveEvent extends PracticeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * Why they are leaving.
     *
     * @since 1.3.0
     */
    public enum Reason {
        /** They left. A leader who leaves hands the party to somebody else first. */
        LEFT,
        /** The leader removed them. */
        KICKED,
        /** The leader ended the party. */
        DISBANDED,
        /** They logged out. */
        DISCONNECTED
    }

    private final UUID player;
    private final Party party;
    private final Reason reason;

    public PracticePartyLeaveEvent(@NotNull UUID player, @NotNull Party party, @NotNull Reason reason) {
        this.player = player;
        this.party = party;
        this.reason = reason;
    }

    /** Who is leaving. */
    public @NotNull UUID player() {
        return player;
    }

    /** The party, as it was with them still in it. */
    public @NotNull Party party() {
        return party;
    }

    /** Why they are leaving. */
    public @NotNull Reason reason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
