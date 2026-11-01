package net.exylia.lib.api.protections;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Reading ExyliaProtections.
 *
 * <pre>{@code
 * boolean claimed = ExyliaAPI.get(ProtectionsService.class)
 *         .map(protections -> protections.protectionAt(spot).isPresent())
 *         .orElse(false);
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaProtections enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server has no protections".
 *
 * @since 1.9.0
 */
public interface ProtectionsService {

    /**
     * The protection whose land holds a spot.
     *
     * @param location the spot
     * @return the protection's id, or empty when no protection covers it
     */
    @NotNull
    Optional<String> protectionAt(@NotNull Location location);

    /**
     * Whether a protection keeps players from flying at a spot: it is under
     * siege or raidable, and the server stops flight during sieges.
     *
     * @param location the spot
     * @return {@code true} when flight must stop there
     */
    boolean deniesFlight(@NotNull Location location);

    /**
     * Whether a player may place blocks at a spot, as the protection covering
     * it sees them: owner, member, trusted clan or a flag open to visitors.
     *
     * <p>Asked by ExyliaLib's {@code BuildZones}. The default answers
     * {@code true}, so an ExyliaProtections build that predates this method
     * never refuses anything through it. Call it on the thread that owns the
     * location.
     *
     * @param player   who would build
     * @param location the spot
     * @return {@code false} only when the protection there refuses it
     * @since 1.432.0
     */
    default boolean canBuild(@NotNull Player player, @NotNull Location location) {
        return true;
    }

    /**
     * Whether a player may break blocks at a spot; see {@link #canBuild}.
     *
     * @since 1.432.0
     */
    default boolean canBreak(@NotNull Player player, @NotNull Location location) {
        return true;
    }

    /**
     * Whether a player may use blocks at a spot: doors, buttons, containers and
     * anything else a protection's interact flag covers; see {@link #canBuild}.
     *
     * @since 1.432.0
     */
    default boolean canInteract(@NotNull Player player, @NotNull Location location) {
        return true;
    }
}
