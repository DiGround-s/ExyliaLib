package net.exylia.lib.api.chatcosmetics;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * One grant of one cosmetic to one player.
 *
 * <p>A row per grant rather than one expiry per cosmetic, because a player can
 * hold a purchase and an event reward for the same tag and taking one away must
 * not take the other. Revocation is a timestamp rather than a deletion, so a
 * revoked grant is still here to be audited.
 *
 * @param id        the grant id, what {@link CosmeticsService#revoke(long)} takes
 * @param player    who holds it
 * @param cosmetic  what was granted
 * @param source    where it came from
 * @param sourceRef what the source calls it — an order id, an event name
 * @param grantedBy who did it: a player uuid or a plugin name
 * @param grantedAt when, in epoch millis
 * @param expiresAt when it runs out in epoch millis, or {@code 0} for never
 * @param revokedAt when it was taken away in epoch millis, or {@code 0} for
 *                  never
 * @param note      whatever the granter wrote down
 * @since 1.0.0
 */
public record Entitlement(
        long id,
        @NotNull UUID player,
        @NotNull CosmeticKey cosmetic,
        @NotNull EntitlementSource source,
        @NotNull Optional<String> sourceRef,
        @NotNull Optional<String> grantedBy,
        long grantedAt,
        long expiresAt,
        long revokedAt,
        @NotNull Optional<String> note) {

    /**
     * Whether this grant never runs out.
     *
     * @return {@code true} when it has no expiry
     */
    public boolean permanent() {
        return expiresAt == 0L;
    }

    /**
     * Whether this grant was taken away.
     *
     * @return {@code true} when it has been revoked
     */
    public boolean revoked() {
        return revokedAt != 0L;
    }

    /**
     * Whether this grant still counts.
     *
     * <p>Takes the time rather than reading the clock, so a listing rendered
     * from one sweep does not disagree with itself halfway down.
     *
     * @param now epoch millis to judge it at
     * @return {@code true} when it is neither revoked nor expired
     */
    public boolean active(long now) {
        return !revoked() && (permanent() || expiresAt > now);
    }
}
