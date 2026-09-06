package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/**
 * One arena, as it was when you asked.
 *
 * <p>A snapshot: the plugin replaces its arena rows on every edit, so these are
 * the values that were current at the moment of the lookup.
 *
 * <p>The spawn points, the regions and the schematics an arena is regenerated
 * from are not here. They are what the plugin needs to run a match in it, not
 * what another plugin needs to know about it, and every one of them is optional
 * on an arena an admin has not finished setting up.
 *
 * @param id           the arena id, stable for the arena's whole life
 * @param displayName  the name menus show
 * @param enabled      whether matches may be started in it
 * @param priority     the order menus list arenas in, higher first
 * @param iconMaterial the Bukkit material name menus draw it with
 * @param usages       what it may be used for, always the full set for an arena
 *                     the admin has not restricted
 * @since 1.0.0
 */
public record Arena(
        @NotNull String id,
        @NotNull String displayName,
        boolean enabled,
        int priority,
        @NotNull String iconMaterial,
        @NotNull @Unmodifiable Set<ArenaUsage> usages) {

    /**
     * Whether this arena may be used for that.
     *
     * @param usage what it would be used for
     * @return {@code true} when the arena accepts it
     */
    public boolean allows(@NotNull ArenaUsage usage) {
        return usages.contains(usage);
    }
}
