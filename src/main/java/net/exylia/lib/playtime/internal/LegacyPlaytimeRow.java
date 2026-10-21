package net.exylia.lib.playtime.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

/**
 * ExyliaSurvivalCore's {@code playtime_data} row, read once to seed
 * {@code exylia_playtime}. Declares every column that plugin declares, so
 * opening it never changes that table's shape. Never written.
 *
 * @since 1.341.0
 */
@Table("playtime_data")
public record LegacyPlaytimeRow(
        @Id(length = 36) String playerUuid,
        @Column("total_playtime_ms") long totalPlaytimeMs,
        @Column(value = "claimed_rewards", length = 4000) String claimedRewards,
        @Column("created_at") long createdAt,
        @Column("updated_at") long updatedAt) {
}
