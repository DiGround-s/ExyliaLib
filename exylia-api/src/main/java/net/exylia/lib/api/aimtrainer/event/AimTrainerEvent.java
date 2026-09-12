package net.exylia.lib.api.aimtrainer.event;

import org.bukkit.event.Event;

/**
 * Base of every event ExyliaAimTrainer fires. All of them are synchronous and
 * fire on the thread that owns the player concerned.
 *
 * @since 1.5.0
 */
public abstract class AimTrainerEvent extends Event {
}
