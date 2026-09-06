package net.exylia.lib.api.survival;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One of a player's homes.
 *
 * <p>{@code location} is nullable, and that is the honest answer rather than a
 * gap: a home can point at a world this server has not loaded, or at another
 * server entirely, and there is no {@link Location} for either. Anything
 * teleporting should call
 * {@link SurvivalService#teleportToHome(org.bukkit.entity.Player, String)},
 * which handles both; the location here is for drawing a menu.
 *
 * @param owner        whose home it is
 * @param name         what they called it
 * @param location     where it is, or {@code null} when this server cannot resolve it
 * @param iconMaterial the material a menu draws it with, empty when it has none
 * @since 1.0.0
 */
public record Home(
        @NotNull UUID owner,
        @NotNull String name,
        @Nullable Location location,
        @NotNull String iconMaterial) {
}
