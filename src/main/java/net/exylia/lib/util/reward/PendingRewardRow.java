package net.exylia.lib.util.reward;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.util.List;
import java.util.UUID;

/**
 * One batch of rewards waiting for a player, in the table
 * {@link PendingRewards#database} keeps.
 *
 * <p>A row rather than a column on the player: a plugin that owes somebody
 * three separate things owes them on three separate occasions, and one row per
 * occasion is what lets the oldest be handed over first and a single failure be
 * left behind rather than taking the rest with it.
 *
 * <p>The table is created in the consumer plugin's own database, which is
 * whatever {@code plugins/<Plugin>/database.yml} says — H2 by default.
 * ExyliaLib has no database of its own and never opens one.
 *
 * @param id       the row id
 * @param plugin   which plugin owes it, so a shared database stays legible
 * @param owner    who is owed, as a string
 * @param payload  the rewards, in {@link RewardCodec}'s encoding
 * @param owedAt   when they were owed, in epoch milliseconds
 * @since 1.128.0
 */
@Table("exylia_pending_rewards")
public record PendingRewardRow(
        @Id(length = 64) String id,
        @Indexed @Column(length = 64) String plugin,
        @Indexed @Column(length = 64) String owner,
        @Column(length = 16384) String payload,
        @Column long owedAt) {

    /** A batch just owed, ready to be written. */
    public PendingRewardRow(String plugin, UUID owner, List<RewardEntry> owed) {
        this(UUID.randomUUID().toString(), plugin, owner.toString(),
                RewardCodec.encode(owed), System.currentTimeMillis());
    }

    /** What this row holds. Empty when the text in it cannot be read. */
    public List<RewardEntry> rewards() {
        return RewardCodec.decode(payload);
    }
}
