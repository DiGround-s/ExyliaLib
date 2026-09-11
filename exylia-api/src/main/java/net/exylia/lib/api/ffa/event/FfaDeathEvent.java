package net.exylia.lib.api.ffa.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player died in an arena, with or without somebody to credit for it.
 *
 * <p>Fired after the plugin has counted the death and the kill, moved the kill
 * streaks and turned the victim into a spectator, so every number read from
 * {@link net.exylia.lib.api.ffa.FfaService} inside a handler already includes
 * this death. Not cancellable: the vanilla death has been consumed by then and
 * the counters written, and there is nothing left to refuse.
 *
 * <p>The killer is whoever landed the final blow, or failing that the last
 * player to hit the victim while both were alive in the arena. A player who
 * logs out while combat tagged is counted as a death in their statistics but
 * does not fire this event: nobody is standing in the arena to die.
 *
 * <p>Synchronous, on the thread that owns the victim.
 *
 * @since 1.3.0
 */
public class FfaDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final Player killer;
    private final String arenaId;
    private final int killerStreak;
    private final int endedStreak;

    /**
     * Creates the event.
     *
     * @param victim       the player who died
     * @param killer       who is credited with the kill, or {@code null}
     * @param arenaId      the arena it happened in
     * @param killerStreak the killer's live streak including this kill, {@code 0} without a killer
     * @param endedStreak  the victim's live streak before they died
     */
    public FfaDeathEvent(@NotNull Player victim, @Nullable Player killer, @NotNull String arenaId,
                         int killerStreak, int endedStreak) {
        this.victim = victim;
        this.killer = killer;
        this.arenaId = arenaId;
        this.killerStreak = killerStreak;
        this.endedStreak = endedStreak;
    }

    /**
     * The player who died.
     *
     * @return the victim
     */
    @NotNull
    public Player victim() {
        return victim;
    }

    /**
     * Who is credited with the kill.
     *
     * @return the killer, or {@code null} for a death nobody caused
     */
    @Nullable
    public Player killer() {
        return killer;
    }

    /**
     * The arena it happened in.
     *
     * @return the arena id
     */
    @NotNull
    public String arenaId() {
        return arenaId;
    }

    /**
     * The killer's live streak, this kill included.
     *
     * <p>{@code 0} without a killer and on a server with kill streaks turned
     * off. The same counter as
     * {@link net.exylia.lib.api.ffa.FfaService#currentStreak(java.util.UUID)}.
     *
     * @return the streak
     */
    public int killerStreak() {
        return killerStreak;
    }

    /**
     * The streak the victim had going before this death.
     *
     * <p>Only reset by the death when the server resets streaks on death, so a
     * non-zero value here does not by itself mean the streak is gone.
     *
     * @return the streak, {@code 0} when they had none
     */
    public int endedStreak() {
        return endedStreak;
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
