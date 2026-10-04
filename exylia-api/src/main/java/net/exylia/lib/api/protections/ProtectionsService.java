package net.exylia.lib.api.protections;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Location;
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
}
