package net.exylia.lib.party;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.UUID;

/**
 * A party, as it was when you asked.
 *
 * <p>A snapshot: members join and leave while the party lives, so ask {@link Parties} again rather
 * than holding one. A party holds nobody by itself; a plugin that takes its members into an activity
 * claims them through {@code Sessions} as usual.
 *
 * @param id      unique for the party's life
 * @param leader  who runs it: the only member who may invite, kick and start things
 * @param members everybody in it, the leader first
 * @param offline the members who left the server and are kept for the rejoin grace period
 * @param maxSize how many members it may hold
 * @since 1.621.0
 */
public record Party(
        @NotNull UUID id,
        @NotNull UUID leader,
        @NotNull @Unmodifiable List<UUID> members,
        @NotNull @Unmodifiable List<UUID> offline,
        int maxSize) {

    /** How many members it has, offline ones included. */
    public int size() {
        return members.size();
    }

    /** Whether a player is in it, leader included. */
    public boolean isMember(@NotNull UUID player) {
        return members.contains(player);
    }

    /** Whether a player leads it. */
    public boolean isLeader(@NotNull UUID player) {
        return leader.equals(player);
    }

    /** Whether a member is on the server right now. */
    public boolean isOnline(@NotNull UUID player) {
        return members.contains(player) && !offline.contains(player);
    }

    /** The members on the server right now, the leader first when online. */
    public @NotNull @Unmodifiable List<UUID> online() {
        return members.stream().filter(m -> !offline.contains(m)).toList();
    }
}
