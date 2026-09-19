package net.exylia.lib.player.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * When the network first saw a player, for {@code Accounts}.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id        the player
 * @param firstSeen the earliest join any server recorded, in epoch milliseconds
 * @since 1.184.0
 */
@Table("exylia_accounts")
public record AccountRow(
        @Id UUID id,
        @Column("first_seen") long firstSeen) {
}
