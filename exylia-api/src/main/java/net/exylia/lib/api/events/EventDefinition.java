package net.exylia.lib.api.events;

import org.jetbrains.annotations.NotNull;

/**
 * One configured event, as an admin set it up.
 *
 * <p>A definition is not a run: it is what an event is started <em>from</em>,
 * and it exists whether or not anybody is playing right now. Only the four
 * fields a plugin outside the suite can act on are here — the arena bounds, the
 * spawn points, the reward tables and the per-minigame settings are the
 * plugin's own business and change shape between releases.
 *
 * @param id          the configuration id, as the admin typed it, and the key
 *                    every statistic is filed under
 * @param displayName what a menu shows
 * @param type        which minigame this configures, for example {@code tntrun}
 * @param enabled     whether it may be started at all
 * @since 1.0.0
 */
public record EventDefinition(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String type,
        boolean enabled) {
}
