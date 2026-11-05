package net.exylia.lib.ledger.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Owner;
import net.exylia.lib.database.Table;

/**
 * Money one player received or spent in one hour and currency, across every
 * server and source: one row of {@code exylia_ledger_players}. Since
 * 1.482.0 a row is also per plugin; rows written before carry no plugin and
 * only count toward the every-plugin totals.
 *
 * <p>Public only because the database module compiles records by reflection.
 *
 * @since 1.478.0
 */
@Table(value = "exylia_ledger_players", data = DataKind.HISTORY)
public record LedgerPlayerRow(
        @Id(length = 128) String id,
        @Indexed @Column("ledger_hour") long hour,
        @Indexed @Column("ledger_day") long day,
        @Owner @Indexed @Column(length = 36) String player,
        @Column(length = 32) String currency,
        @Column(value = "ledger_flow", length = 8) String flow,
        @Column long cents,
        @Column(length = 64) String plugin) {
}
