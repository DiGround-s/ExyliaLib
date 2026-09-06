package net.exylia.lib.api.totemtrainer;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A physical place two players duel in.
 *
 * <p>Many matches share one arena at the same time — the players are hidden
 * from each other rather than given the space — so an arena is never locked and
 * never busy. What decides whether it can host anything is
 * {@link #isReady()}: an arena missing a spawn, or pointing at a world that is
 * not loaded, cannot take a duel however enabled it is.
 *
 * @param id          the arena id
 * @param displayName what menus call it
 * @param enabled     whether the owner has turned it on
 * @param spawnA      where the first player is put, empty when unset
 * @param spawnB      where the second player is put, empty when unset
 * @since 1.0.0
 */
public record TotemArena(
        @NotNull String id,
        @NotNull String displayName,
        boolean enabled,
        @NotNull Optional<Location> spawnA,
        @NotNull Optional<Location> spawnB) {

    /**
     * Whether this arena can actually host a duel right now.
     *
     * @return {@code true} when it is enabled and both spawns resolve to a
     *         loaded world
     */
    public boolean isReady() {
        return enabled
                && spawnA.map(Location::getWorld).isPresent()
                && spawnB.map(Location::getWorld).isPresent();
    }
}
