package net.exylia.lib.notice.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * One notice waiting for a player, in the table {@code Notices} keeps.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id       a random id, the key a claim deletes by
 * @param plugin   which plugin left it, so a shared database stays legible
 * @param player   who it is for
 * @param category what kind of notice, the key its summary is grouped by
 * @param message  the raw message, rendered when it is shown
 * @param data     the values substituted into it, as a JSON object of strings
 * @param sentAt   when it was sent, in epoch milliseconds
 * @since 1.278.0
 */
@Table("exylia_notices")
@Index(columns = {"plugin", "player"})
public record NoticeRow(
        @Id(length = 64) String id,
        @Column(length = 64) String plugin,
        @Column UUID player,
        @Column(length = 64) String category,
        @Column(length = Column.UNBOUNDED) String message,
        @Column(length = Column.UNBOUNDED) String data,
        @Indexed @Column("sent_at") long sentAt) {

    /** A notice just sent, ready to be written. */
    public static NoticeRow of(String plugin, UUID player, String category, String message, String data,
                               long sentAt) {
        return new NoticeRow(UUID.randomUUID().toString(), plugin, player, category, message, data, sentAt);
    }
}
