package net.exylia.lib.api.clans;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's membership of one clan.
 *
 * <p>A player belongs to at most one clan, so this is the whole of their clan
 * identity: who they are, where they belong, and what they are allowed to do
 * there.
 *
 * @param player the player
 * @param clanId the clan they belong to
 * @param roleId their role within it, resolvable through
 *               {@link ClansService#roleOf(UUID)}
 * @since 1.0.0
 */
public record ClanMember(
        @NotNull UUID player,
        @NotNull String clanId,
        @NotNull String roleId) {
}
