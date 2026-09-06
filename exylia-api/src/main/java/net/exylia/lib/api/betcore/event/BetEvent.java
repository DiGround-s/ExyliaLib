package net.exylia.lib.api.betcore.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/**
 * What every event this plugin fires has in common.
 *
 * <h2>Why the async flag is worked out rather than written down</h2>
 * This plugin decides everything on the global thread, which on Paper <em>is</em>
 * the primary thread and on Folia is not. Bukkit refuses to fire a synchronous
 * event off the primary thread, so hard-coding either answer breaks one of the
 * two platforms. Asking is one call and is right on both.
 *
 * <p>A handler is therefore told the truth about where it is running. On Folia
 * that means: do not touch a block, a chunk or another entity from a handler
 * without a scheduler hop of your own.
 *
 * <h2>The match on these events is a snapshot</h2>
 * Every event that names a match carries a {@link net.exylia.lib.api.betcore.BetMatch},
 * which is the match as it was when the event was made. It is a copy on purpose:
 * the live match belongs to this plugin's own thread and its own classloader, and
 * a handle to it would be neither safe to read from a handler nor visible to the
 * plugin holding one. Ask
 * {@link net.exylia.lib.api.betcore.BetCoreService#match(String)} with
 * {@link net.exylia.lib.api.betcore.BetMatch#id()} when you need the current state.
 *
 * @since 1.0.0
 */
public abstract class BetEvent extends Event {

    protected BetEvent() {
        super(!Bukkit.isPrimaryThread());
    }
}
