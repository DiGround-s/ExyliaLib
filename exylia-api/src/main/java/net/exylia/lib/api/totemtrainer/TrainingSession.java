package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's solo session, as it was when you asked.
 *
 * <p>Solo is physical: the player is moved into an arena and hidden from
 * everybody, so a plugin that finds a session here should treat that player as
 * unavailable rather than merely busy.
 *
 * @param id        the session id
 * @param player    whose session it is
 * @param rules     what it is running under
 * @param arenaId   where they are
 * @param startedAt when it began, in epoch milliseconds
 * @since 1.0.0
 */
public record TrainingSession(
        @NotNull UUID id,
        @NotNull UUID player,
        @NotNull TrainingRules rules,
        @NotNull String arenaId,
        long startedAt) {
}
