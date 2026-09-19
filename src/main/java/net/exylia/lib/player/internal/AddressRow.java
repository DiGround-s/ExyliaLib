package net.exylia.lib.player.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * One address a player joined from, for {@code Accounts}: its keyed hash, never
 * the address itself.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id       the player and the hash, {@code <uuid>/<hash>}
 * @param player   the player
 * @param address  the address's keyed hash, hex
 * @param lastSeen the last join from it, in epoch milliseconds
 * @since 1.184.0
 */
@Table("exylia_account_addresses")
public record AddressRow(
        @Id(length = 80) String id,
        @Indexed @Column UUID player,
        @Column(length = 32) String address,
        @Column("last_seen") long lastSeen) {
}
