package net.exylia.lib.api.survival;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A server warp.
 *
 * <p>{@code permission} is empty for a warp anybody may use, and
 * {@code cost} is {@code 0} for a free one — neither is a null. Whether a
 * particular player may use it also depends on their cooldown, so ask
 * {@link SurvivalService#canUseWarp(org.bukkit.entity.Player, String)} and
 * {@link SurvivalService#warpCooldownMillis(UUID, String)} rather than deciding
 * from these two fields alone.
 *
 * @param id          the warp id, which is what commands take
 * @param displayName the name players see
 * @param enabled     whether an administrator allows it to be used
 * @param cost        what using it charges, {@code 0} when it is free
 * @param permission  the permission needed, empty when anybody may use it
 * @param location    where it leads, or {@code null} when this server cannot resolve it
 * @since 1.0.0
 */
public record Warp(
        @NotNull String id,
        @NotNull String displayName,
        boolean enabled,
        double cost,
        @NotNull String permission,
        @Nullable Location location) {
}
