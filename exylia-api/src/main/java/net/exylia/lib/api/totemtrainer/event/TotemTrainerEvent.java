package net.exylia.lib.api.totemtrainer.event;

import org.bukkit.event.Event;

/**
 * Base of every event this plugin fires. All of them are synchronous and fire
 * on the thread that owns the player they are about, which on Folia is that
 * player's region thread.
 *
 * <h2>The match on these events is a snapshot</h2>
 * Every event that names a match carries a
 * {@link net.exylia.lib.api.totemtrainer.TotemMatch}, which is the match as it
 * was when the event was made. It is a copy on purpose: the live match belongs
 * to this plugin's own classloader and its own thread, so a handle to it would
 * be neither visible to the plugin holding one nor safe to read. Ask
 * {@link net.exylia.lib.api.totemtrainer.TotemTrainerService#match(java.util.UUID)}
 * when you need the current state.
 *
 * @since 1.0.0
 */
public abstract class TotemTrainerEvent extends Event {
}
