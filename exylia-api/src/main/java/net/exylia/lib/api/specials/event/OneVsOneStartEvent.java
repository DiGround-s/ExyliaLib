package net.exylia.lib.api.specials.event;

import net.exylia.lib.api.specials.SpecialsMatch;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A duel started.
 *
 * <p>Fired once both players have been prepared, teleported into the arena and
 * the fight phase has begun. Not cancellable: by the time the arena is claimed
 * and the players are standing in it, refusing the duel would leave both of
 * them somewhere they did not choose to be. A plugin that wants to stop duels
 * should stop the item instead.
 *
 * @since 1.0.0
 */
public class OneVsOneStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SpecialsMatch match;

    /**
     * Creates the event.
     *
     * @param match the duel that started
     */
    public OneVsOneStartEvent(@NotNull SpecialsMatch match) {
        this.match = match;
    }

    /**
     * The duel that started.
     *
     * @return the match, as it was when the event was fired
     */
    @NotNull
    public SpecialsMatch match() {
        return match;
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
