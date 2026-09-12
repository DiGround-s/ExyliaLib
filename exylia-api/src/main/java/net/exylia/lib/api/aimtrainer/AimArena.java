package net.exylia.lib.api.aimtrainer;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A place to shoot from. Any number of players share one; each sees only
 * their own targets.
 *
 * @since 1.5.0
 */
public record AimArena(@NotNull String id, @NotNull String displayName, boolean enabled,
                       @NotNull Optional<Location> spawn) {

    public boolean isReady() {
        return enabled && spawn.isPresent() && spawn.get().getWorld() != null;
    }
}
