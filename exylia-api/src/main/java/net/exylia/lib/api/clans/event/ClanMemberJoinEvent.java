package net.exylia.lib.api.clans.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player has joined a clan.
 *
 * <p>Fired once the membership is written and cached, so every query on
 * {@link net.exylia.lib.api.clans.ClansService} already answers with the new
 * clan. Covers joining an open clan, accepting an invite and an administrator
 * forcing somebody in. The founder of a new clan is not a join: that is
 * {@link ClanCreateEvent}.
 *
 * <p>A notification rather than a request, because an administrator's forced
 * join cannot be refused, and an event that could only sometimes be cancelled
 * is a trap. To keep somebody out of a clan, refuse the invite or the command
 * that leads here.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClanMemberJoinEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String clanId;

    /**
     * Creates the event.
     *
     * @param player who joined
     * @param clanId the clan they joined
     */
    public ClanMemberJoinEvent(@NotNull Player player, @NotNull String clanId) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.clanId = clanId;
    }

    /**
     * The player who joined.
     *
     * @return the new member
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The clan they joined.
     *
     * @return the clan id
     */
    @NotNull
    public String getClanId() {
        return clanId;
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
