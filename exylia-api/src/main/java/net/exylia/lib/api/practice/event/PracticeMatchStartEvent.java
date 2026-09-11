package net.exylia.lib.api.practice.event;

import net.exylia.lib.api.practice.MatchInfo;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A match's countdown has ended and the fight is on.
 *
 * <p>Fired once per match, on its first countdown: a round or a point being
 * reset counts down again and does not fire it a second time. The match is
 * {@link net.exylia.lib.api.practice.MatchStatus#ACTIVE} and its fighters are
 * being handed their kits.
 *
 * <p>The match is a snapshot of that moment. Pair it with
 * {@link PracticeMatchEndEvent} on {@link MatchInfo#id()}; ask
 * {@link net.exylia.lib.api.practice.PracticeService#match(String)} when you need
 * it as it is now.
 *
 * @since 1.3.0
 */
public class PracticeMatchStartEvent extends PracticeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final MatchInfo match;

    public PracticeMatchStartEvent(@NotNull MatchInfo match) {
        this.match = match;
    }

    /** The match, as it was when the fight began. */
    public @NotNull MatchInfo match() {
        return match;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
