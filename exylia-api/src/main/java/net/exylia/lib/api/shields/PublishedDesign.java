package net.exylia.lib.api.shields;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A design in the shared library, as its author published it.
 *
 * <p>The library is what the in-game browser lists: designs players chose to
 * share, ranked by how many other players took a copy. A copy is counted once
 * per player, so taking the same design twice does not make it look twice as
 * popular.
 *
 * @param id        the library id, which is what
 *                  {@link ShieldsService#importDesign(org.bukkit.entity.Player, long)}
 *                  takes
 * @param owner     who published it
 * @param ownerName their name when they did; kept as text because the player
 *                  may have changed it since, and a browser showing the name
 *                  under which the design became popular is the honest one
 * @param uses      how many players have taken a copy
 * @param design    the design itself
 * @since 1.0.0
 */
public record PublishedDesign(
        long id,
        @NotNull UUID owner,
        @NotNull String ownerName,
        int uses,
        @NotNull ShieldDesign design) {
}
