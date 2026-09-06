package net.exylia.lib.api.chatcosmetics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * The one answer everything reads: does this player have this cosmetic now.
 *
 * <p>A permission node and any number of grants can each say yes on their own,
 * so this is the resolved verdict rather than a list to work through. The
 * grants are still here for an integration that wants to show where the
 * ownership came from.
 *
 * @param owned        whether the player has it right now
 * @param permanent    whether it is theirs for good — a node, or a grant with
 *                     no expiry
 * @param expiresAt    when the last active grant runs out in epoch millis, or
 *                     {@code 0} when it is permanent or not owned
 * @param byPermission whether the permission node alone would have answered yes
 * @param grants       the stored grants that are active right now, empty when
 *                     only a node grants it
 * @since 1.0.0
 */
public record Ownership(
        boolean owned,
        boolean permanent,
        long expiresAt,
        boolean byPermission,
        @NotNull @Unmodifiable List<Entitlement> grants) {

    /** Nobody owns anything: what a lookup for an unknown cosmetic answers. */
    public static final Ownership NONE = new Ownership(false, false, 0L, false, List.of());

    public Ownership {
        grants = List.copyOf(grants);
    }
}
