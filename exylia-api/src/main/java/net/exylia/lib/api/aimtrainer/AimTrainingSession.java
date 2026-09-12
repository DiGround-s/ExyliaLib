package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A solo session as it was when asked about.
 *
 * @param arenaId the arena it runs in, or {@code null} when the player trains where they stood
 * @since 1.5.0
 */
public record AimTrainingSession(@NotNull UUID id, @NotNull UUID player, @NotNull AimRules rules,
                                 @Nullable String arenaId, long startedAt, @NotNull AimSessionPhase phase) {
}
