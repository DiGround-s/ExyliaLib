package net.exylia.lib.api.capture;

import org.jetbrains.annotations.NotNull;

/**
 * A running event, as it was when you asked.
 *
 * <p>A snapshot: the plugin ticks these several times a second, so the elapsed
 * and remaining times here are the ones that were current at the moment of the
 * lookup. Ask again rather than holding one across ticks.
 *
 * <p>A run carries the id of the config it was started from, and only one run
 * of a config exists at a time — so the id you pass to
 * {@link CaptureService#stop(String)} is the one you passed to
 * {@link CaptureService#start(String)}.
 *
 * @param id              the run's id, which is its config's id
 * @param displayName     the name players see
 * @param type            the event type, from the registry
 * @param state           where the run is in its life
 * @param elapsedMillis   how long it has run
 * @param remainingMillis how long is left, or how long it has run when it has no limit
 * @param infiniteDuration whether it runs until somebody wins rather than to a clock
 * @param participants    how many players have been inside a zone at least once
 * @since 1.0.0
 */
public record CaptureEvent(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String type,
        @NotNull CaptureState state,
        long elapsedMillis,
        long remainingMillis,
        boolean infiniteDuration,
        int participants) {
}
