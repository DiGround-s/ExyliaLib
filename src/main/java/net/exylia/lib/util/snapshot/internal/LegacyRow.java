package net.exylia.lib.util.snapshot.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import net.exylia.lib.util.snapshot.Snapshot;
import org.bukkit.Location;
import org.jetbrains.annotations.ApiStatus;

/**
 * A row as ExyliaCommons wrote it, so the migration can read one.
 *
 * <p>Declared exactly as its {@code PlayerStateRecord} was: keyed on the player
 * alone, {@code contextId} in camel case because commons did not convert names,
 * and {@code created_at} / {@code updated_at} because its {@code Entity} base
 * class put them on every table it made.
 *
 * <p>Read-only in practice. Nothing here ever writes to this table: the
 * migration copies out of it and leaves it alone, so a server that needs to go
 * back to ExyliaCommons still can.
 *
 * @param uuid       the player
 * @param snapshot   their state, in the format the codec already reads
 * @param contextId  what it was taken for, which commons allowed to be absent
 * @param lastLocation where they were
 * @param createdAt  when the row was first written
 * @param updatedAt  when it was last written
 */
@ApiStatus.Internal
@Table("snapshot_player_states")
public record LegacyRow(

        @Id(length = 36) String uuid,

        @Column(length = Column.UNBOUNDED) Snapshot snapshot,

        // Commons' own spelling. Converting it to snake_case here would make
        // the migration read a column that does not exist and find nothing,
        // which looks exactly like "there was nothing to migrate".
        @Column(value = "contextId", length = 64) String contextId,

        @Column(value = "lastLocation") Location lastLocation,

        @Column(value = "created_at") long createdAt,

        @Column(value = "updated_at") long updatedAt) {
}
