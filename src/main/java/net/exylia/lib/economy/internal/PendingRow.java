package net.exylia.lib.economy.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A change to a balance the writer could not apply itself.
 *
 * <p>A market sale to somebody on another server, a reward for somebody
 * offline: the server making it does not own that player's snapshot, so it
 * leaves the change here and tells the network. Whoever owns the player — now
 * or on their next join — takes each row by deleting it and folds it in, which
 * is what makes a change applied exactly once however many servers saw the
 * message.
 *
 * @param absolute whether {@code amount} replaces the balance rather than
 *                 adding to it — an admin {@code set} from elsewhere
 */
@Table("exylia_balance_pending")
public record PendingRow(
        @Id(generated = true) long id,
        @Indexed @Column(length = 36) String player,
        @Column(length = 32) String currency,
        @Column BigDecimal amount,
        @Column boolean absolute,
        @Column(length = 64) String reason,
        @Column(length = 36) String initiator,
        @Column("created_at") long createdAt) {

    public PendingRow(UUID player, String currency, BigDecimal amount, boolean absolute,
                      String reason, UUID initiator) {
        this(0L, player.toString(), currency, amount, absolute, reason,
                initiator == null ? null : initiator.toString(), System.currentTimeMillis());
    }
}
