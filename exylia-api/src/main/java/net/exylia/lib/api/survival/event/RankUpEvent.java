package net.exylia.lib.api.survival.event;

import net.exylia.lib.api.survival.Rank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A player is moving up the rank ladder.
 *
 * <p>Fired once they meet every requirement of the next rank and before
 * anything is charged, which is the point of firing it here: cancelling never
 * takes a player's money for a promotion they did not get.
 *
 * <p>An uncancelled event is not a promise of promotion. Payment is taken after
 * this and can still fail — an economy plugin that has gone away, or a balance
 * that moved between the check and the charge — in which case the player stays
 * where they are. Watch {@link #getTo()} against
 * {@link net.exylia.lib.api.survival.SurvivalService#currentRank(java.util.UUID)}
 * if you need to know it landed.
 *
 * <p>Cancelling tells the player nothing, so a handler that refuses a rank-up
 * should say why itself.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.0.0
 */
public class RankUpEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Rank from;
    private final Rank to;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player being promoted
     * @param from   the rank they hold, or {@code null} when they are on none yet
     * @param to     the rank they are moving to
     */
    public RankUpEvent(@NotNull Player player, @Nullable Rank from, @NotNull Rank to) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.from = from;
        this.to = to;
    }

    /**
     * The player being promoted.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The rank they are leaving.
     *
     * <p>Empty for a player who has never ranked up: the first promotion starts
     * from no rank at all rather than from a bottom rung, and there is no
     * sensible {@link Rank} to name for it.
     *
     * @return the rank they hold, empty when they are on none yet
     */
    @NotNull
    public Optional<Rank> getFrom() {
        return Optional.ofNullable(from);
    }

    /**
     * The rank they are moving to.
     *
     * @return the new rank
     */
    @NotNull
    public Rank getTo() {
        return to;
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
