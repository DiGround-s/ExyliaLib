package net.exylia.lib.api.ffa.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player's kill streak reached a milestone the server rewards.
 *
 * <p>Fired only for a streak an administrator configured, not for every kill,
 * and before the announcement and the reward commands run. Cancelling skips
 * both; the streak itself still counts, so the next milestone is reached as
 * usual. That is the hook an anti-farming or event plugin wants: withhold the
 * reward without rewriting anybody's numbers.
 *
 * <p>Synchronous, on the thread that owns the victim of the kill that reached
 * the milestone.
 *
 * @since 1.3.0
 */
public class FfaKillStreakEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String arenaId;
    private final int streak;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player  the player on the streak
     * @param arenaId the arena the kill was made in
     * @param streak  the streak reached
     */
    public FfaKillStreakEvent(@NotNull Player player, @NotNull String arenaId, int streak) {
        this.player = player;
        this.arenaId = arenaId;
        this.streak = streak;
    }

    /**
     * The player on the streak.
     *
     * @return the player
     */
    @NotNull
    public Player player() {
        return player;
    }

    /**
     * The arena the kill was made in.
     *
     * @return the arena id
     */
    @NotNull
    public String arenaId() {
        return arenaId;
    }

    /**
     * The streak reached.
     *
     * @return the number of kills in a row
     */
    public int streak() {
        return streak;
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
