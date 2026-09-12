package net.exylia.lib.economy.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One player's balance in one stored currency: the snapshot.
 *
 * <p>Written only by the server the player is on. Every other server that
 * wants to change it writes a {@link PendingRow} instead, which the owner
 * folds in; that ownership is what keeps two servers from overwriting each
 * other's number.
 */
@Table("exylia_balances")
@Index(columns = {"currency", "amount"}, descending = {"amount"})
public record BalanceRow(
        @Id(length = 72) String id,
        @Indexed @Column(length = 36) String player,
        @Column(length = 64) String name,
        @Indexed @Column(length = 32) String currency,
        @Column BigDecimal amount,
        @Column("updated_at") long updatedAt) {

    public BalanceRow(UUID player, String name, String currency, BigDecimal amount) {
        this(id(player, currency), player.toString(), name == null ? "" : name, currency, amount,
                System.currentTimeMillis());
    }

    public static String id(UUID player, String currency) {
        return player + "|" + currency;
    }

    public UUID uuid() {
        return UUID.fromString(player);
    }
}
