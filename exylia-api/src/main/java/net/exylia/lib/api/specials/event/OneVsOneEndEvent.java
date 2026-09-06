package net.exylia.lib.api.specials.event;

import net.exylia.lib.api.specials.MatchResult;
import net.exylia.lib.api.specials.SpecialsMatch;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A duel finished.
 *
 * <p>Fired after the rewards, the messages and the teleports out have all run,
 * which is the point at which the outcome is final. This is where a statistics
 * or economy plugin hangs its own reaction: the match is decided and neither
 * player is still in the arena's care.
 *
 * <p>There is not always a winner. A draw or a forced end has none, and a
 * disconnect names the player who stayed. The result says which case this is.
 *
 * @since 1.0.0
 */
public class OneVsOneEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SpecialsMatch match;
    private final MatchResult result;
    private final Player winner;

    /**
     * Creates the event.
     *
     * @param match  the duel that finished
     * @param result how it finished
     * @param winner who won, or {@code null} when nobody did
     */
    public OneVsOneEndEvent(@NotNull SpecialsMatch match, @NotNull MatchResult result,
                            @Nullable Player winner) {
        this.match = match;
        this.result = result;
        this.winner = winner;
    }

    /**
     * The duel that finished.
     *
     * @return the match, in its final state
     */
    @NotNull
    public SpecialsMatch match() {
        return match;
    }

    /**
     * How the duel ended.
     *
     * @return the result
     */
    @NotNull
    public MatchResult result() {
        return result;
    }

    /**
     * Who won.
     *
     * <p>Absent on a draw and on a forced end. Present but possibly offline on
     * a disconnect, where the winner is whoever did not leave.
     *
     * @return the winner, or {@code null} when there is none
     */
    @Nullable
    public Player winner() {
        return winner;
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
