package net.exylia.lib.api.practice.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/**
 * What every event ExyliaPracticeCore fires has in common.
 *
 * <h2>Why the async flag is worked out rather than written down</h2>
 * Practice runs a match on the threads that own its arena and its players, and
 * a queue or a party on the thread of the player who clicked. On Paper all of
 * those are the primary thread; on Folia none of them are. Bukkit refuses to
 * fire a synchronous event off the primary thread, so hard-coding either answer
 * breaks one of the two platforms. Asking is one call and is right on both.
 *
 * <p>A handler is therefore told the truth about where it is running. On Folia
 * that means: do not touch a block, a chunk or an entity from a handler without
 * a scheduler hop of your own.
 *
 * <h2>Players are UUIDs wherever they may have gone</h2>
 * An event about one player at a moment they are certainly online hands over
 * the {@code Player}. An event about a match or a party hands over UUIDs, for
 * the reason {@link net.exylia.lib.api.practice.MatchInfo} gives: anybody in one
 * can log out at any moment, and a record holding them would hold their entity.
 *
 * @since 1.3.0
 */
public abstract class PracticeEvent extends Event {

    protected PracticeEvent() {
        super(!Bukkit.isPrimaryThread());
    }
}
