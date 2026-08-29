package net.exylia.lib.util.worldguard;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * One WorldGuard region, identified by world <em>and</em> id.
 *
 * <p>WorldGuard keeps a separate region manager per world, so the id alone is
 * not an identity: {@code test} may exist in {@code world} and in
 * {@code world_nether} as two unrelated regions. Every lookup here carries the
 * world for that reason.
 *
 * @param world    the Bukkit world name
 * @param id       the region id, lower-cased as WorldGuard stores it
 * @param priority the region priority; higher wins on overlap
 * @since 1.74.0
 */
public record WorldGuardRegion(@NotNull String world, @NotNull String id, int priority) {

    public WorldGuardRegion {
        id = id.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns {@code world:id}, the form config files use to name one region.
     */
    public @NotNull String qualified() {
        return world + ":" + id;
    }
}
