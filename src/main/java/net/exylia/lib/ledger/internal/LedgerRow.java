package net.exylia.lib.ledger.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

/**
 * Money created or sunk in one hour, by one server, plugin, source and
 * currency: one row of {@code exylia_ledger}.
 *
 * <p>Public only because the database module compiles records by reflection.
 * {@code cents} is the amount times one hundred, {@code times} how many
 * records made it; both are written with atomic increments, so servers
 * sharing the database add up instead of overwriting each other.
 *
 * @since 1.478.0
 */
@Table(value = "exylia_ledger", data = DataKind.HISTORY)
public record LedgerRow(
        @Id(length = 255) String id,
        @Indexed @Column("ledger_hour") long hour,
        @Indexed @Column("ledger_day") long day,
        @Column(length = 64) String server,
        @Column(length = 64) String plugin,
        @Column(length = 64) String source,
        @Column(length = 32) String currency,
        @Column(value = "ledger_flow", length = 8) String flow,
        @Column long cents,
        @Column long times) {
}
