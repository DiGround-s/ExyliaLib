package net.exylia.lib.objectives;

import net.exylia.lib.util.SpawnOrigin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One thing a player did, handed to every subscriber that wants it.
 *
 * @param player         who did it
 * @param type           what kind of thing
 * @param thing          what it was about (see each {@link ObjectiveType}); compare with {@link Objectives#matches}
 * @param at             where it happened
 * @param amount         how many, at least 1
 * @param placedByPlayer for {@link ObjectiveType#BREAK_BLOCK}: whether a player placed the block. Answered by
 *                       {@code PlacedBlocks}, so only for materials some plugin tracks there
 * @param origin         for {@link ObjectiveType#KILL_MOB}: where the mob came from; {@code null} otherwise
 * @param victim         for {@link ObjectiveType#KILL_PLAYER}: who was killed; {@code null} otherwise
 * @param customType     for {@link ObjectiveType#CUSTOM}: the type the firing plugin gave; {@code null} otherwise
 * @since 1.620.0
 */
public record ObjectiveProgress(@NotNull Player player, @NotNull ObjectiveType type, @NotNull String thing,
                                @NotNull Location at, int amount, boolean placedByPlayer,
                                @Nullable SpawnOrigin origin, @Nullable UUID victim, @Nullable String customType) {

    public ObjectiveProgress {
        amount = Math.max(1, amount);
    }

    /** Whether the killed mob came from a spawner. */
    public boolean fromSpawner() {
        return origin == SpawnOrigin.SPAWNER;
    }

    /** Whether players made the killed mob on purpose: a spawner, an egg or breeding ({@link SpawnOrigin#playerMade()}). */
    public boolean farmed() {
        return origin != null && origin.playerMade();
    }
}
