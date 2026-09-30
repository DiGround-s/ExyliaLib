package net.exylia.lib.util.reward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One batch of rewards a player is still owed, as a store holds it.
 *
 * <p>What {@link PendingRewards#peek} hands back so a staff screen can show a
 * queue without emptying it, and what {@link PendingRewards#take} names when
 * one batch is handed over or cancelled on its own.
 *
 * @param id      the store's own key for the batch
 * @param owedAt  when it was owed, in epoch milliseconds; {@code 0} when the
 *                store never recorded it
 * @param source  what it was owed for, such as an event's name; {@code null}
 *                when the store does not know
 * @param rewards what it holds
 * @since 1.220.0
 */
public record PendingBatch(@NotNull String id, long owedAt, @Nullable String source,
                           @NotNull List<RewardEntry> rewards) {

    public PendingBatch {
        rewards = List.copyOf(rewards);
    }
}
