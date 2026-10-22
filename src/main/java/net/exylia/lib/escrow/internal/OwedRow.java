package net.exylia.lib.escrow.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Money or items the escrow could not hand over yet, delivered on the player's next join.
 *
 * <p>Public only because the database module compiles records by reflection.
 *
 * @param id        a random id, the key a delivery deletes by
 * @param plugin    which plugin owes it
 * @param player    who it is owed to
 * @param currency  the currency id, for money; empty for items (or the default currency)
 * @param amount    how much money; zero for items
 * @param items     the items, encoded; empty for money
 * @param reason    why it is owed, for the economy history
 * @param createdAt when it was written, in epoch milliseconds
 * @since 1.345.0
 */
@Table("exylia_escrow_owed")
@Index(columns = {"plugin", "player"})
public record OwedRow(
        @Id(length = 36) String id,
        @Column(length = 64) String plugin,
        @Column UUID player,
        @Column(length = 64) String currency,
        @Column BigDecimal amount,
        @Column(length = Column.UNBOUNDED) String items,
        @Column(length = 32) String reason,
        @Column("created_at") long createdAt) {

    public static OwedRow money(String plugin, UUID player, String currency, BigDecimal amount, String reason) {
        return new OwedRow(UUID.randomUUID().toString(), plugin, player, currency, amount, "", reason,
                System.currentTimeMillis());
    }

    public static OwedRow items(String plugin, UUID player, String items, String reason) {
        return new OwedRow(UUID.randomUUID().toString(), plugin, player, "", BigDecimal.ZERO, items, reason,
                System.currentTimeMillis());
    }

    public boolean isItems() {
        return !items.isEmpty();
    }
}
