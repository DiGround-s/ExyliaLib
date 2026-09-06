package net.exylia.lib.api.capture;

import org.jetbrains.annotations.NotNull;

/**
 * An event as an administrator configured it, whether or not it is running.
 *
 * <p>{@code type} is a plain string rather than an enum because the event types
 * are a registry the plugin can be extended with: KOTH, conquest, payload and
 * the rest are what ships, not what is possible. Compare it against
 * {@link CaptureService#eventTypes()} rather than a constant of your own.
 *
 * <p>The zone, the reward tables and the mode's own settings are left out: they
 * are the plugin's own geometry and reward types, and none of them are
 * something a third party can act on without also owning the mode.
 *
 * @param id           the config id, which is also how the event is started
 * @param displayName  the name players see
 * @param type         the event type, from the registry
 * @param enabled      whether an administrator allows it to run
 * @param iconMaterial the material a menu draws it with
 * @param maxDurationMillis how long a run lasts, {@code 0} when it runs until somebody wins
 * @since 1.0.0
 */
public record CaptureConfig(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String type,
        boolean enabled,
        @NotNull String iconMaterial,
        long maxDurationMillis) {
}
