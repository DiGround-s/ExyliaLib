package net.exylia.lib.api.clans.event;

import net.exylia.lib.api.clans.LeaveReason;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A player is no longer a member of a clan.
 *
 * <p>Fired once the membership is deleted, so every query on
 * {@link net.exylia.lib.api.clans.ClansService} already answers "no clan" for
 * them. Covers leaving, being kicked and being banned, by a member or by an
 * administrator. A disband is not here: see {@link ClanDisbandEvent}.
 *
 * <p>The player is a {@link UUID} rather than a {@code Player} because members
 * are kicked and banned from menus while offline. Look them up if you need
 * them, and expect {@code null}.
 *
 * <p>A notification rather than a request, because an administrator's removal
 * cannot be refused.
 *
 * <p>Called on the thread that ran the removal, which on Folia is a region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClanMemberLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String clanId;
    private final LeaveReason reason;
    private final UUID actor;

    /**
     * Creates the event.
     *
     * @param player who left, who may be offline
     * @param clanId the clan they left
     * @param reason why they left
     * @param actor  the member who removed them, or {@code null} when they left
     *               on their own or an administrator removed them
     */
    public ClanMemberLeaveEvent(@NotNull UUID player, @NotNull String clanId,
                                @NotNull LeaveReason reason, @Nullable UUID actor) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.clanId = clanId;
        this.reason = reason;
        this.actor = actor;
    }

    /**
     * The player who is no longer a member.
     *
     * @return their id, who may be offline
     */
    @NotNull
    public UUID getPlayer() {
        return player;
    }

    /**
     * The clan they left.
     *
     * @return the clan id, which still resolves
     */
    @NotNull
    public String getClanId() {
        return clanId;
    }

    /**
     * Why they are no longer a member.
     *
     * @return the reason
     */
    @NotNull
    public LeaveReason getReason() {
        return reason;
    }

    /**
     * Who removed them.
     *
     * @return the member who kicked or banned them, or {@code null} when they
     *         left on their own or an administrator removed them
     */
    @Nullable
    public UUID getActor() {
        return actor;
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
