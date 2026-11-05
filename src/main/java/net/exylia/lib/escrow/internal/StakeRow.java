package net.exylia.lib.escrow.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Owner;
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
@Table(value = "exylia_escrow_stakes", data = DataKind.PROGRESS)
@Index(columns = {"plugin", "match_id"})
@Index(columns = {"plugin", "server", "state"})
public record StakeRow(
        @Id(length = 36) String id,
        @Column(length = 64) String plugin,
        @Column(length = 64) String server,
        @Column(value = "match_id", length = 64) String matchId,
        @Owner @Column UUID player,
        @Column(length = 64) String currency,
        @Column BigDecimal amount,
        @Column(length = 16) StakeState state,
        @Column("updated_at") long updatedAt) {

    public static StakeRow pending(String plugin, String server, String matchId, UUID player, String currency,
                                   BigDecimal amount) {
        return new StakeRow(UUID.randomUUID().toString(), plugin, server, matchId, player, currency, amount,
                StakeState.PENDING, System.currentTimeMillis());
    }

    /**
     * A persistent hold's pending row. Its id is derived from the plugin and
     * the hold id, so one hold id names one row however often it is written.
     */
    public static StakeRow pendingKept(String plugin, String server, String holdId, UUID player, String currency,
                                       BigDecimal amount) {
        return new StakeRow(keptId(plugin, holdId), plugin, server, holdId, player, currency, amount,
                StakeState.PENDING, System.currentTimeMillis());
    }

    /** The row id of a persistent hold. */
    public static String keptId(String plugin, String holdId) {
        return UUID.nameUUIDFromBytes((plugin + "\0kept\0" + holdId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    /**
     * The row with a new amount and state, its {@code updatedAt} strictly
     * later than this one's: a compare-and-set on {@code updatedAt} then
     * fails for every caller that read the row before this write.
     */
    public StakeRow next(BigDecimal nextAmount, StakeState next) {
        return new StakeRow(id, plugin, server, matchId, player, currency, nextAmount, next,
                Math.max(System.currentTimeMillis(), updatedAt + 1));
    }

    public StakeRow with(StakeState next) {
        return new StakeRow(id, plugin, server, matchId, player, currency, amount, next, System.currentTimeMillis());
    }
}
