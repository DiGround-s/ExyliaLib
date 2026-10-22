package net.exylia.lib.escrow.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One money stake, in the table {@code Escrow} keeps.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id        a random id: two stakes never share a row, so a second hold can never overwrite a first
 * @param plugin    which plugin holds it, so a shared database stays legible
 * @param server    which server took it; only that server's restart refunds it
 * @param matchId   what it is staked on
 * @param player    who staked it
 * @param currency  the currency id; empty for the default one
 * @param amount    how much
 * @param state     where it stands
 * @param updatedAt when the state last changed, in epoch milliseconds
 * @since 1.345.0
 */
@Table("exylia_escrow_stakes")
@Index(columns = {"plugin", "match_id"})
@Index(columns = {"plugin", "server", "state"})
public record StakeRow(
        @Id(length = 36) String id,
        @Column(length = 64) String plugin,
        @Column(length = 64) String server,
        @Column(value = "match_id", length = 64) String matchId,
        @Column UUID player,
        @Column(length = 64) String currency,
        @Column BigDecimal amount,
        @Column(length = 16) StakeState state,
        @Column("updated_at") long updatedAt) {

    public static StakeRow pending(String plugin, String server, String matchId, UUID player, String currency,
                                   BigDecimal amount) {
        return new StakeRow(UUID.randomUUID().toString(), plugin, server, matchId, player, currency, amount,
                StakeState.PENDING, System.currentTimeMillis());
    }

    public StakeRow with(StakeState next) {
        return new StakeRow(id, plugin, server, matchId, player, currency, amount, next, System.currentTimeMillis());
    }
}
