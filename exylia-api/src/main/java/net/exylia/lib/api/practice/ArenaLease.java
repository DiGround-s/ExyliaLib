package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;

/**
 * One copy of an arena, reserved for the plugin that asked for it.
 *
 * <p>From {@link PracticeService#acquireArena} until it is handed back with
 * {@link PracticeService#releaseArena}, practice will not start a match in
 * this copy. Hand it back when the event ends, also when it ends early: a lease
 * that is never released keeps the copy out of rotation until the server
 * restarts. Releasing regenerates the copy, as the end of a match does.
 *
 * @param arenaId the arena this is a copy of
 * @param layout  where things are in this copy
 * @param handle  practice's own reference to the reservation; opaque, only
 *                meaningful to {@link PracticeService#releaseArena}
 * @since 3.5.0
 */
public record ArenaLease(
        @NotNull String arenaId,
        @NotNull ArenaLayout layout,
        @NotNull Object handle) {
}
