package net.exylia.lib.party;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * What a plugin wants to hear about every party on the server.
 *
 * <p>Registered with {@link Parties#watch}; every method has an empty default. Calls arrive on the
 * thread of the change (a command's player, the global sweep), never under a lock, so a watcher may
 * call {@link Parties} back. Hop to a player's thread before touching them.
 *
 * @since 1.621.0
 */
public interface PartyWatcher {

    /** A player joined a party. */
    default void joined(@NotNull Party party, @NotNull UUID player) {
    }

    /**
     * A member is no longer in a party.
     *
     * @param party  the party after they went; when it ended, its last members
     * @param player who went
     * @param reason why
     */
    default void left(@NotNull Party party, @NotNull UUID player, @NotNull PartyLeave reason) {
    }

    /** The lead changed hands. */
    default void leaderChanged(@NotNull Party party, @Nullable UUID previous) {
    }

    /** A member left the server and is kept for the rejoin grace period. */
    default void quit(@NotNull Party party, @NotNull UUID player) {
    }

    /** A member came back within the grace period. */
    default void rejoined(@NotNull Party party, @NotNull UUID player) {
    }

    /** The party ended: disbanded, or down to one member with nobody invited. */
    default void ended(@NotNull Party party) {
    }
}
