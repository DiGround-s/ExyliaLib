package net.exylia.lib.database.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

/**
 * One lock of {@code RowLocks}: who holds it, until when, and the version every
 * change of hands compares against.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id        the namespace and key, {@code auctions:42}
 * @param holder    the server holding it, empty when free
 * @param expiresAt when the holder's lease ends, in epoch milliseconds
 * @param version   bumped by every acquire and release
 * @since 1.163.0
 */
@Table("exylia_row_locks")
public record LockRow(
        @Id String id,
        @Column String holder,
        @Column("expires_at") long expiresAt,
        @Column long version) {
}
