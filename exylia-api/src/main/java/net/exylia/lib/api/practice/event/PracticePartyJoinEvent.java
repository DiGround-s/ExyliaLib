package net.exylia.lib.api.practice.event;

import net.exylia.lib.api.practice.Party;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is joining a party.
 *
 * <p>Fired once practice has agreed they may — they are free and in no party,
 * the party has room, and it is open or they hold an invitation — and before
 * they are added, so cancelling it keeps them out. Nobody is told anything when
 * it is cancelled, and the invitation, if they had one, is still theirs.
 *
 * <p>Not fired for the leader creating a party: nobody joins one that did not
 * exist a moment ago.
 *
 * @since 1.3.0
 */
public class PracticePartyJoinEvent extends PracticeEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Party party;

    private boolean cancelled;

    public PracticePartyJoinEvent(@NotNull Player player, @NotNull Party party) {
        this.player = player;
        this.party = party;
    }

    /** Who is joining. */
    public @NotNull Player player() {
        return player;
    }

    /** The party, as it was before they joined. */
    public @NotNull Party party() {
        return party;
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
