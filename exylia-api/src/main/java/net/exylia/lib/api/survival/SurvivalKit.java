package net.exylia.lib.api.survival;

import org.jetbrains.annotations.NotNull;

/**
 * A kit players can claim.
 *
 * <p>The kit's contents are left out on purpose: they are an
 * {@code ItemStack} array the plugin owns and rewrites when an administrator
 * edits the kit, and handing one out would let a caller change what everybody
 * gets. Claim the kit instead.
 *
 * @param id              the kit id, which is what commands take
 * @param displayName     the name players see
 * @param description     one line of description, empty when it has none
 * @param permission      the permission needed, empty when anybody may claim it
 * @param cooldownMillis  how long between claims, {@code 0} when there is no wait
 * @param maxUses         how many times a player may ever claim it, {@code 0} for no limit
 * @param enabled         whether an administrator allows it to be claimed
 * @since 1.0.0
 */
public record SurvivalKit(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull String description,
        @NotNull String permission,
        long cooldownMillis,
        int maxUses,
        boolean enabled) {
}
