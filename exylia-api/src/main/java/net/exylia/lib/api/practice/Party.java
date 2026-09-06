package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.UUID;

/**
 * A party, as it was when you asked.
 *
 * <p>A snapshot: members join and leave while the party exists, so ask again
 * rather than holding one.
 *
 * <p>A party holds nothing by itself — its members are still free until it
 * queues for something. That is why the practice plugin advises rather than
 * refuses when another mode wants to take one of them: see
 * {@link PracticeService#state}, which reports
 * {@link PracticeState#IN_PARTY} for a party that has not queued yet.
 *
 * @param id      the party id, unique for the party's life
 * @param leader  who runs it, and the only member who may start anything
 * @param members everybody in it, the leader included
 * @param open    whether anybody may join without an invite
 * @param maxSize how many members it may hold
 * @since 1.0.0
 */
public record Party(
        @NotNull UUID id,
        @NotNull UUID leader,
        @NotNull @Unmodifiable List<UUID> members,
        boolean open,
        int maxSize) {

    /**
     * How many players are in the party.
     *
     * @return the member count, never below one
     */
    public int size() {
        return members.size();
    }

    /**
     * Whether a player is in this party.
     *
     * @param player the player
     * @return {@code true} when they are a member, leader included
     */
    public boolean isMember(@NotNull UUID player) {
        return members.contains(player);
    }
}
