package net.exylia.lib.util.snapshot.internal;

import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.util.snapshot.Snapshot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Moves rows written by ExyliaCommons into the table this module owns.
 *
 * <h2>What it does, and what it refuses to do</h2>
 * It copies. It never deletes, never rewrites and never so much as opens
 * {@code snapshot_player_states} for writing, so a server that wants to go back
 * to ExyliaCommons finds its rows exactly as it left them. The price of that is
 * a table left behind on disk, which is the cheapest possible insurance against
 * a migration that goes wrong at three in the morning.
 *
 * <h2>How it knows it has already run</h2>
 * By a marker row in the <em>new</em> table, keyed with the nil {@code UUID} and
 * the context {@code $legacy-import}. A real key is
 * {@code <uuid>:<contextId>}, so the only snapshot that could collide with the
 * marker is one taken of the player {@code 00000000-0000-0000-0000-000000000000}
 * in a context somebody named {@code $legacy-import}, and that player does not
 * exist.
 *
 * <p>The marker lives in the new table rather than in a file because the thing
 * being migrated is in the database: two servers sharing one MySQL must not each
 * decide, from their own disk, that the copy still needs doing.
 *
 * <h2>Why it is safe to run twice anyway</h2>
 * A legacy row is skipped when the new table already holds that player and
 * context. So even with the marker lost &mdash; restored from a backup, dropped
 * by hand &mdash; a second run cannot overwrite a newer snapshot with the stale
 * one it superseded. The marker saves the work; the check is what makes it
 * correct.
 */
@ApiStatus.Internal
public final class LegacyImport {

    /** The player half of the marker key: nobody's {@code UUID}. */
    private static final UUID MARKER_PLAYER = new UUID(0L, 0L);

    /** The context half. The {@code $} is what keeps it out of a config file. */
    private static final String MARKER_CONTEXT = "$legacy-import";

    private LegacyImport() {
        throw new AssertionError("No instances.");
    }

    /** The key of the marker row, exposed so a test can look for it. */
    public static @NotNull String markerKey() {
        return SnapshotRow.key(MARKER_PLAYER, MARKER_CONTEXT);
    }

    /**
     * Copies whatever ExyliaCommons left behind, once.
     *
     * <p>Entirely in the background: every step is a future, nothing here waits
     * on anything, and a failure at any point leaves both tables as they were
     * and reports why. The worst outcome is that the copy has not happened yet,
     * which is also the state the server was in a moment ago.
     *
     * <p>Note that reading the old table creates it if it is missing, because
     * asking a repository for rows is the only way to ask. On a server that
     * never ran ExyliaCommons that leaves one empty table behind, once, which
     * is safe to drop. A plugin that would rather not have it calls
     * {@code importLegacy(false)}.
     *
     * @param database the plugin's database view
     * @param rows     the new table
     * @param debug    where to report
     * @return completes when the copy is done, or immediately when it was
     *         already done
     */
    public static @NotNull CompletableFuture<Integer> run(@NotNull PluginDatabase database,
                                                          @NotNull Repository<SnapshotRow> rows,
                                                          @NotNull Debug debug) {
        return rows.exists(markerKey()).thenCompose(alreadyDone -> {
            if (alreadyDone) {
                return CompletableFuture.completedFuture(0);
            }
            return copy(database, rows, debug);
        }).exceptionally(failure -> {
            debug.error("The ExyliaCommons snapshot table could not be imported."
                    + " Nothing was changed, and the old rows are untouched;"
                    + " it will be tried again on the next start.", failure);
            return 0;
        });
    }

    private static CompletableFuture<Integer> copy(PluginDatabase database,
                                                   Repository<SnapshotRow> rows,
                                                   Debug debug) {
        Repository<LegacyRow> legacy = database.repository(LegacyRow.class);
        return legacy.findAll().thenCompose(found -> {
            if (found.isEmpty()) {
                return mark(rows, debug, 0);
            }
            return rows.findAll().thenCompose(existing -> {
                List<SnapshotRow> converted = convert(found, existing, debug);
                if (converted.isEmpty()) {
                    return mark(rows, debug, 0);
                }
                // One batch rather than a write per player: a server coming back
                // after a crash can have hundreds of these, and a round trip
                // each is a start that takes a minute for no reason.
                return rows.saveAll(converted).thenCompose(ignored ->
                        mark(rows, debug, converted.size()));
            });
        });
    }

    private static List<SnapshotRow> convert(List<LegacyRow> legacy, List<SnapshotRow> existing,
                                             Debug debug) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (SnapshotRow row : existing) {
            taken.add(row.key());
        }
        List<SnapshotRow> converted = new java.util.ArrayList<>(legacy.size());
        int unreadable = 0;
        for (LegacyRow row : legacy) {
            UUID uuid = parse(row.uuid());
            if (uuid == null) {
                unreadable++;
                continue;
            }
            Snapshot snapshot = row.snapshot();
            if (snapshot == null) {
                unreadable++;
                continue;
            }
            // Commons let the context be absent. A snapshot with no context
            // still belongs to somebody, and dropping it would lose exactly the
            // inventory this whole exercise exists to keep.
            String context = row.contextId() == null || row.contextId().isBlank()
                    ? "legacy" : row.contextId();
            String key = SnapshotRow.key(uuid, context);
            if (!taken.add(key)) {
                continue;
            }
            long savedAt = row.updatedAt() > 0 ? row.updatedAt() : row.createdAt();
            converted.add(SnapshotRow.of(uuid, context, snapshot, row.lastLocation(),
                    savedAt > 0 ? savedAt : System.currentTimeMillis()));
        }
        if (unreadable > 0) {
            debug.warn(unreadable + " row(s) in snapshot_player_states could not be read and"
                    + " were left where they are. They are still in that table, unchanged.");
        }
        return converted;
    }

    private static CompletableFuture<Integer> mark(Repository<SnapshotRow> rows, Debug debug,
                                                   int moved) {
        SnapshotRow marker = new SnapshotRow(markerKey(), MARKER_PLAYER, MARKER_CONTEXT,
                null, null, System.currentTimeMillis());
        return rows.save(marker).thenApply(ignored -> {
            if (moved > 0) {
                debug.success("Imported " + moved + " snapshot(s) from ExyliaCommons."
                        + " snapshot_player_states is untouched and can be dropped once"
                        + " the new table has been seen to work.");
            }
            return moved;
        });
    }

    private static UUID parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * Whether a row is the marker rather than somebody's snapshot.
     *
     * <p>Every read of the new table filters it out, because a marker returned
     * to a caller asking for a player's snapshots is a snapshot that restores
     * nothing onto nobody.
     *
     * @param row the row
     * @return whether to ignore it
     */
    public static boolean isMarker(@NotNull SnapshotRow row) {
        return MARKER_CONTEXT.equals(row.contextId());
    }

    /** Convenience for the optional a find returns. */
    public static boolean isMarker(@NotNull Optional<SnapshotRow> row) {
        return row.isPresent() && isMarker(row.get());
    }
}
