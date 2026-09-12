package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One played round; an empty winner is a draw that was replayed.
 *
 * @since 1.5.0
 */
public record AimRound(int number, long startedAt, long endedAt, @NotNull Optional<UUID> winner,
                       @NotNull @Unmodifiable Map<UUID, AimPerformance> performances) {

    public AimRound {
        performances = Map.copyOf(performances);
    }
}
