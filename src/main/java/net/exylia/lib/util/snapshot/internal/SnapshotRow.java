package net.exylia.lib.util.snapshot.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;
import net.exylia.lib.util.snapshot.Snapshot;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One stored snapshot: a player, a context, and where they were.
 *
 * <h2>Why the key is a derived string and not two columns</h2>
 * What identifies a row here is the pair (player, context). A player in an FFA
 * arena who then joins an event has two snapshots, and each must survive the
 * other being restored. ExyliaCommons keyed on the player alone, so entering the
 * second context overwrote the first: the player left the event, got their FFA
 * kit back, and the inventory they actually owned was gone. That is the single
 * worst bug in the module this replaces.
 *
 * <p>The library's {@code @Id} is one component, on purpose &mdash; a find, a
 * delete, a generated key and a Mongo {@code _id} all mean one value. So the
 * pair is folded into one: {@code uuid + ":" + contextId}. It is derived,
 * nobody ever types it, and {@link #key(UUID, String)} is the only thing that
 * builds it. A colon cannot appear in a {@code UUID}, so the two halves can
 * always be told apart again.
 *
 * <p>Both halves are also stored as their own columns, indexed together, so
 * {@code where("uuid", …)} is a real index lookup rather than a table scan with
 * a {@code LIKE}. That costs a repeated {@code UUID} per row and buys "every
 * snapshot this player has", which is the question a join handler asks.
 *
 * <h2>Why {@code contextId} keeps its camel case</h2>
 * Because that is the column ExyliaCommons created, and the migration reads its
 * table. Keeping the spelling means the old table and the new one differ in the
 * key and in nothing else, which is the only difference worth having.
 *
 * @param key          {@code uuid:contextId}, derived and never typed
 * @param uuid         the player, indexed so their rows can be listed
 * @param contextId    what the snapshot was taken for
 * @param snapshot     the state itself, through the registered codec
 * @param lastLocation where they were when it was taken, with the server;
 *                     rows from before 1.108.0 hold a six-part Location and
 *                     read back as a place on whichever server reads them
 * @param savedAt      when it was taken, in epoch milliseconds
 */
@ApiStatus.Internal
@Table("exylia_snapshots")
// Both halves of the identity, in the order a lookup asks for them: every read
// that is not by key is "this player's snapshots", sometimes narrowed to one
// context. A single index answers both.
@Index(columns = {"uuid", "contextId"})
public record SnapshotRow(

        @Id(length = 128) String key,

        @Column(length = 36, nullable = false) @Indexed UUID uuid,

        @Column(length = 64, nullable = false) String contextId,

        // Unbounded rather than the 65535 Commons declared. A shulker box full
        // of shulker boxes encodes larger than that, and MySQL's TEXT counts
        // bytes rather than characters, so an over-long value is truncated
        // instead of refused — a corrupt snapshot rather than a failed write.
        @Column(length = Column.UNBOUNDED) Snapshot snapshot,

        @Column ExyliaLocation lastLocation,

        @Column long savedAt) {

    /**
     * The stored key for a player in a context.
     *
     * @param uuid      the player
     * @param contextId the context
     * @return the key
     */
    public static @NotNull String key(@NotNull UUID uuid, @NotNull String contextId) {
        return uuid + ":" + contextId;
    }

    /**
     * Builds a row from a captured snapshot.
     *
     * @param uuid      the player
     * @param contextId the context
     * @param snapshot  their state
     * @param where     where they were, or {@code null}
     * @param savedAt   when it was taken
     * @return the row
     */
    public static @NotNull SnapshotRow of(@NotNull UUID uuid, @NotNull String contextId,
                                          @NotNull Snapshot snapshot, @Nullable ExyliaLocation where,
                                          long savedAt) {
        return new SnapshotRow(key(uuid, contextId), uuid, contextId, snapshot, where, savedAt);
    }
}
