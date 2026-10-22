package net.exylia.lib.escrow.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * Items one player put into one match, in the table {@code Escrow} keeps.
 *
 * <p>Public only because the database module compiles records by reflection.
 *
 * @param id        a random id, one per hold
 * @param plugin    which plugin holds them
 * @param server    which server took them; only that server's restart refunds them
 * @param matchId   what they are staked on
 * @param player    whose they were
 * @param items     the items, encoded
 * @param state     {@code HELD}, {@code SETTLED} or {@code REFUNDED}
 * @param updatedAt when the state last changed, in epoch milliseconds
 * @since 1.345.0
 */
@Table("exylia_escrow_items")
@Index(columns = {"plugin", "match_id"})
@Index(columns = {"plugin", "server", "state"})
public record ItemRow(
        @Id(length = 36) String id,
        @Column(length = 64) String plugin,
        @Column(length = 64) String server,
        @Column(value = "match_id", length = 64) String matchId,
        @Column UUID player,
        @Column(length = Column.UNBOUNDED) String items,
        @Column(length = 16) StakeState state,
        @Column("updated_at") long updatedAt) {

    public static ItemRow held(String plugin, String server, String matchId, UUID player, String items) {
        return new ItemRow(UUID.randomUUID().toString(), plugin, server, matchId, player, items, StakeState.HELD,
                System.currentTimeMillis());
    }

    public ItemRow with(StakeState next) {
        return new ItemRow(id, plugin, server, matchId, player, items, next, System.currentTimeMillis());
    }
}
