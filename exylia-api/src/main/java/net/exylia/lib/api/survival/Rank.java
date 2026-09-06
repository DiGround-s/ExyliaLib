package net.exylia.lib.api.survival;

import org.jetbrains.annotations.NotNull;

/**
 * One rung of the rank ladder.
 *
 * <p>The requirements are left out: they are read from configuration into the
 * plugin's own types, and one of them can be an arbitrary placeholder
 * expression that only the plugin knows how to evaluate. Ask
 * {@link SurvivalService#canRankUp(org.bukkit.entity.Player)} instead of trying
 * to work the answer out.
 *
 * @param id          the rank id
 * @param displayName the name players see
 * @param order       where it sits on the ladder, lower first
 * @since 1.0.0
 */
public record Rank(
        @NotNull String id,
        @NotNull String displayName,
        int order) {
}
