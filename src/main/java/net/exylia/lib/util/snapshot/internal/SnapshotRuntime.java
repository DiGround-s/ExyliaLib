package net.exylia.lib.util.snapshot.internal;

import net.exylia.lib.database.Codec;
import net.exylia.lib.database.Databases;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.util.snapshot.Snapshot;
import net.exylia.lib.util.snapshot.SnapshotCodec;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The part of the module that belongs to the library rather than to a consumer.
 *
 * <p>Two jobs, and no state a plugin owns: registering the {@link Snapshot}
 * codec, and reporting a stored row that could not be read in full.
 */
@ApiStatus.Internal
public final class SnapshotRuntime {

    private SnapshotRuntime() {
        throw new AssertionError("No instances.");
    }

    private static volatile @Nullable Plugin library;

    /**
     * Problems already reported, so each is said exactly once.
     *
     * <p>Once per message and not once per read. A server that restarted with
     * two hundred players in an arena reads two hundred rows in the same
     * second, and if one item stopped being readable it stopped being readable
     * for all of them. Two hundred identical lines is a warning nobody reads,
     * which is the same as no warning with a larger log file.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Registers the codec, once, before anything can compile a model.
     *
     * <p>Ordering is the whole reason this is a static initialiser of a class
     * the store touches on its way to {@code repository()}: a codec registered
     * after a model has been compiled never reaches it, and the failure looks
     * like "no codec for Snapshot" at a call site that has one.
     */
    static {
        Databases.codec(Snapshot.class, Codec.of(
                SnapshotCodec::encode,
                stored -> SnapshotCodec.decode(stored, SnapshotRuntime::report)));
    }

    /**
     * Notes the library plugin, so a problem has somewhere to be reported.
     *
     * <p>Called by ExyliaLib on enable. Without it the module still works and
     * still skips what it cannot read; it simply says so through the plain
     * logger rather than in the server's colours.
     *
     * @param plugin the library plugin
     */
    public static void init(@NotNull Plugin plugin) {
        library = plugin;
        // Touching the class is what runs the static initialiser above, and
        // doing it here means the codec is registered at enable rather than at
        // the first save — which is the ordering that matters.
        REPORTED.clear();
    }

    /**
     * Says a stored snapshot could not be read in full, once per problem.
     *
     * <p>Reported and skipped rather than thrown: ExyliaCommons caught
     * everything and returned {@code null}, so one item written by a version of
     * the server that no longer exists discarded a player's whole inventory
     * with no line anywhere saying it had happened.
     *
     * @param problem what could not be read
     */
    public static void report(@NotNull String problem) {
        if (!REPORTED.add(problem)) {
            return;
        }
        String line = "A stored snapshot could not be read in full: " + problem
                + ". This is reported once.";
        Plugin plugin = library;
        if (plugin == null) {
            java.util.logging.Logger.getLogger("ExyliaLib").warning(line);
            return;
        }
        Debug.of(plugin).warn(line);
    }

    /** Test seam: forgets what has been reported, so a test starts clean. */
    public static void forgetReportedForTests() {
        REPORTED.clear();
    }

    /** The last stamp handed out, so no two snapshots claim the same instant. */
    private static final java.util.concurrent.atomic.AtomicLong LAST_STAMP =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * When a snapshot was taken, in a way that can be ordered.
     *
     * <p>The wall clock, except never the same value twice and never
     * backwards. Ordering matters: a player restored from every context they
     * are in must end up in the state they were in before any of them, which
     * means the earliest snapshot has to be applied last &mdash; and two
     * snapshots stamped with the same millisecond have no earliest. A player
     * entering two contexts inside one millisecond is unlikely, and "unlikely"
     * is not a basis on which to decide whose inventory they keep.
     *
     * <p>Static rather than per-plugin because the two contexts are usually two
     * plugins: the arena is one and the event is another, and they have to be
     * ordered against each other. Shared across plugins is exactly right here,
     * and the counter is only ever nudged forward by a millisecond at a time.
     *
     * @return the stamp to store
     */
    public static long stamp() {
        return LAST_STAMP.updateAndGet(previous ->
                Math.max(System.currentTimeMillis(), previous + 1));
    }
}
