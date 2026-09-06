package net.exylia.lib.api.ffa;

import org.jetbrains.annotations.NotNull;

/**
 * An arena, as it was configured when you asked.
 *
 * <p>A snapshot of the stored row. Anything that changes while players fight —
 * who is inside, how many are alive, how long until the arena regenerates — is
 * a method on {@link FfaService} instead, so reading an arena stays a cache hit.
 *
 * <p>Only the fields a third party can act on are here. The arena's region,
 * protected-zone shape and rule set are deliberately left out: they are
 * expressed in the plugin's own geometry types, and an integration that needs
 * to know whether a location is protected should ask
 * {@link FfaService#isProtected(org.bukkit.Location)} rather than rebuild the
 * test.
 *
 * @param id                 the arena id, stable for the arena's whole life
 * @param displayName        the name players see
 * @param maxPlayers         how many may be alive at once
 * @param kitMode            how the arena hands out kits
 * @param enabled            whether players may join it at all
 * @param regenerationSeconds how often the arena rebuilds itself, {@code 0} when it never does
 * @param permission         the permission needed to join, empty when anybody may
 * @since 1.0.0
 */
public record FfaArena(
        @NotNull String id,
        @NotNull String displayName,
        int maxPlayers,
        @NotNull FfaKitMode kitMode,
        boolean enabled,
        int regenerationSeconds,
        @NotNull String permission) {
}
