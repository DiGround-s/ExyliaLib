package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

/**
 * A drill as the server configured it.
 *
 * @param id          the configured id, which records and boards are keyed by
 * @param displayName the name shown in menus, with its formatting
 * @param kind        what it asks of the player
 * @param weight      how hard it is by itself; multiplies every rating set in it
 * @param duration    seconds a session lasts, or a cap for a reaction drill
 * @param targets     targets on screen at once
 * @param size        target edge in blocks, before a player's size setting
 * @param height      target height in blocks, before a player's size setting
 * @param distanceMin nearest a target is placed, before a player's distance setting
 * @param distanceMax farthest a target is placed
 * @param lifetime    seconds a flick target stays up, or 0 for ever
 * @param ordered     whether only one target counts at a time
 * @param moving      whether the target strafes
 * @since 1.5.0
 */
public record AimDrill(@NotNull String id, @NotNull String displayName, @NotNull AimDrillKind kind, double weight,
                       double duration, int targets, double size, double height, double distanceMin,
                       double distanceMax, double lifetime, boolean ordered, boolean moving) {
}
