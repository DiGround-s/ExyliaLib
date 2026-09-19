package net.exylia.lib.player.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

/**
 * The network's key for hashing addresses, for {@code Accounts}: one row,
 * created by whichever server asks first.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id      always {@code "address"}
 * @param secret  the key, hex
 * @param reads   how many times a server has asked for it: the counter
 *                {@code increment} creates the row through, which is what keeps
 *                a second server from writing a key over the first one's
 * @since 1.184.0
 */
@Table("exylia_account_keys")
public record AccountKeyRow(
        @Id String id,
        @Column(length = 64) String secret,
        @Column long reads) {
}
