package net.exylia.lib.api.clans.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A clan no longer exists.
 *
 * <p>Fired after everything is gone — members, roles, relations, land and
 * logs — so a lookup by {@link #getClanId()} already finds nothing. That is why
 * the name travels with the event: this is the last place it can be read.
 *
 * <p>Fired for every disband, the leader's own and an administrator's, and not
 * cancellable for that reason: an administrator's command is not a request.
 * The members are not sent a {@link ClanMemberLeaveEvent} each, and the land is
 * announced by {@link ClanUnclaimEvent} just before this.
 *
 * <p>Called on the thread that ran the disband, which on Folia is a region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClanDisbandEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String clanId;
    private final String name;
    private final UUID actor;

    /**
     * Creates the event.
     *
     * @param clanId the id the clan had
     * @param name   the name the clan went by
     * @param actor  the leader who disbanded it, or {@code null} when an
     *               administrator did
     */
    public ClanDisbandEvent(@NotNull String clanId, @NotNull String name, @Nullable UUID actor) {
        super(!Bukkit.isPrimaryThread());
        this.clanId = clanId;
        this.name = name;
        this.actor = actor;
    }

    /**
     * The id the clan had.
     *
     * @return the clan id, which no longer resolves
     */
    @NotNull
    public String getClanId() {
        return clanId;
    }

    /**
     * The name the clan went by.
     *
     * @return the name, now free for another clan to take
     */
    @NotNull
    public String getName() {
        return name;
    }

    /**
     * Who disbanded the clan.
     *
     * @return the leader's id, or {@code null} when an administrator did it
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
