package net.exylia.lib.playtime.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * A player's network-wide active playtime, for {@code Playtime}.
 *
 * <p>Public only because the database module compiles records by reflection;
 * nothing outside the library reads or writes it.
 *
 * @param id      the player
 * @param totalMs milliseconds played, every server added together
 * @since 1.341.0
 */
@Table("exylia_playtime")
public record PlaytimeRow(
        @Id UUID id,
        @Column("total_ms") long totalMs) {
}
