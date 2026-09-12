package net.exylia.lib.economy.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;
import net.exylia.lib.economy.LedgerEntry;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of the ledger: what moved, why, and what the balance read after. */
@Table("exylia_ledger")
@Index(columns = {"player", "currency", "created_at"}, descending = {"created_at"})
public record LedgerRow(
        @Id(generated = true) long id,
        @Indexed @Column(length = 36) String player,
        @Column(length = 32) String currency,
        @Column BigDecimal delta,
        @Column("balance_after") BigDecimal balanceAfter,
        @Column(length = 64) String reason,
        @Column(length = 36) String initiator,
        @Column(length = 64) String server,
        @Column("created_at") long createdAt) {

    public LedgerRow(UUID player, String currency, BigDecimal delta, BigDecimal after,
                     String reason, UUID initiator, String server) {
        this(0L, player.toString(), currency, delta, after, reason,
                initiator == null ? null : initiator.toString(), server, System.currentTimeMillis());
    }

    public LedgerEntry entry() {
        return new LedgerEntry(currency, UUID.fromString(player), delta, balanceAfter, reason,
                initiator == null ? null : UUID.fromString(initiator), server, createdAt);
    }
}
