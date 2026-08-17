package net.exylia.lib.database.internal;

import net.exylia.lib.database.Query;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether the indexes a record declared can answer the queries it is asked.
 *
 * <p>A query that filters or sorts on columns no index covers is answered by
 * reading every row. That is invisible on a test server with forty rows and it
 * is the entire cost on a live one with four hundred thousand — which is exactly
 * the bug this module exists to prevent, and the one nobody notices until the
 * table is large and the server is already slow.
 *
 * <p>So the columns a query touches are checked against the columns the record's
 * indexes cover, and a query nothing covers is reported once. Reported, not
 * refused: the query is not wrong, it is slow, and a library that refused it
 * would break a plugin over a performance opinion.
 *
 * <h2>Why the work happens at registration</h2>
 * The set of index prefixes is computed once, when the repository is registered,
 * and a query afterwards is one set lookup on a joined string. Deciding this per
 * query — walking every index, comparing column lists — would put the cost of
 * the diagnostic on the hot path it is warning about, which is the same mistake
 * with better intentions.
 *
 * <h2>Why prefixes</h2>
 * A B-tree over {@code (kit_id, elo)} answers a filter on {@code kit_id} alone,
 * and a filter on {@code kit_id} with a sort on {@code elo}. It does not answer
 * a filter on {@code elo} alone: the index is ordered by {@code kit_id} first,
 * so the {@code elo} values are scattered through it. That is the leftmost-prefix
 * rule every engine here implements, and it is what makes the covered set the
 * prefixes of each index rather than its column set.
 *
 * <h2>Threads</h2>
 * Immutable after construction except for the set of names already reported,
 * which is a concurrent set. Reads and writes both come off the background pool,
 * from as many threads as the pool has.
 *
 * @since 1.24.0
 */
public final class IndexCoverage {

    /**
     * Every column sequence an index can answer, joined by {@code ,}.
     *
     * <p>A joined string rather than a {@code List} key: the lookup is one hash
     * of a short string built with a single {@link StringBuilder}, against a
     * {@code hashCode} that a list would compute element by element anyway.
     */
    private final Set<String> covered;

    /**
     * The queries already reported, so each is said exactly once.
     *
     * <p>Once per shape and not once per call. A leaderboard menu opened by
     * every player on the server would otherwise print the same line every time
     * it is opened, and a warning printed a thousand times is a warning nobody
     * reads — which is the same as not warning at all, with a bigger log file.
     */
    private final Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final String table;

    /**
     * One coverage per model, computed the first time the model is registered.
     *
     * <p>Keyed by the model, whose lifetime is the JVM's — {@link EntityModel}
     * caches compiled models for exactly as long, because a record's shape cannot
     * change while the server runs. So this map is bounded by the number of
     * record classes a server has loaded, which is small and fixed.
     *
     * <p>It also means the "already reported" state survives a plugin reload,
     * which is what a developer wants: the warning is about a shape of query, not
     * about an occurrence of one, and repeating it after every reload would be
     * the noise this class is written to avoid.
     */
    private static final java.util.Map<EntityModel<?>, IndexCoverage> BY_MODEL =
            new java.util.concurrent.ConcurrentHashMap<>();

    private IndexCoverage(@NotNull String table, @NotNull Set<String> covered) {
        this.table = table;
        this.covered = covered;
    }

    /**
     * The coverage for a model, computing it the first time.
     *
     * <p>Called from {@code prepare}, so the whole cost lands at registration and
     * a query afterwards is one set lookup. Computing it lazily on the first
     * query would put the diagnostic's cost on the path it is diagnosing.
     *
     * @param model the compiled record model
     * @return the coverage, the same instance every time
     */
    public static @NotNull IndexCoverage of(@NotNull EntityModel<?> model) {
        return BY_MODEL.computeIfAbsent(model, IndexCoverage::compute);
    }

    /**
     * Checks a query against a model's coverage, registering it if it was not.
     *
     * <p>The fallback registration matters for the store implementations, which
     * can be asked to select before anything called {@code prepare} — a
     * repository built against an already-open connection, or a test.
     *
     * @param model    the model the query runs against
     * @param filters  the filter columns, in the order the query named them
     * @param order    the sort, may be empty
     * @param warnings where a line goes
     */
    public static void check(@NotNull EntityModel<?> model,
                             @NotNull List<String> filters,
                             @NotNull List<Query.Sort> order,
                             @NotNull java.util.function.Consumer<String> warnings) {
        of(model).report(model, filters, order, warnings);
    }

    /**
     * Computes what a model's indexes cover.
     *
     * <p>The primary key is covered on its own: every engine indexes it, and
     * Mongo indexes {@code _id}, so a lookup or a sort by key is never a scan.
     */
    private static @NotNull IndexCoverage compute(@NotNull EntityModel<?> model) {
        Set<String> covered = new HashSet<>();
        covered.add(model.id().name());
        for (IndexModel index : model.indexes()) {
            List<String> columns = index.columns();
            StringBuilder prefix = new StringBuilder(32);
            for (String column : columns) {
                if (!prefix.isEmpty()) {
                    prefix.append(',');
                }
                prefix.append(column);
                covered.add(prefix.toString());
            }
        }
        return new IndexCoverage(model.table(), Set.copyOf(covered));
    }

    /**
     * Reports a query no index covers, at most once per shape.
     *
     * <p>The filter columns come first and the sort columns after, because that
     * is the order an index has to have them in: a database narrows with the
     * filter and then wants what is left already in the sorted order. An index
     * on the sort column alone does not help a filtered query, which is the
     * mistake the message names.
     *
     * @param model    the model the query runs against
     * @param filters  the filter columns, in the order the query named them
     * @param order    the sort, may be empty
     * @param warnings where the line goes
     */
    void report(@NotNull EntityModel<?> model,
                @NotNull List<String> filters,
                @NotNull List<Query.Sort> order,
                @NotNull java.util.function.Consumer<String> warnings) {
        if (filters.isEmpty() && order.isEmpty()) {
            // Reading a whole table in no particular order is a full scan by
            // definition and no index changes that. Whether it is a mistake
            // depends entirely on the table, and this class cannot know.
            return;
        }
        List<String> columns = new ArrayList<>(filters.size() + order.size());
        for (String filter : filters) {
            columns.add(resolve(model, filter));
        }
        for (Query.Sort sort : order) {
            String column = resolve(model, sort.column());
            // A column already used as a filter is pinned to one value, so its
            // position in the index is spent and sorting by it is free. Adding
            // it again would ask for an index no sensible person would declare.
            if (!columns.contains(column)) {
                columns.add(column);
            }
        }
        if (columns.isEmpty() || covers(columns)) {
            return;
        }
        String shape = String.join(",", columns);
        if (!reported.add(shape)) {
            return;
        }
        warnings.accept("a query on " + table + " filters or sorts by [" + shape
                + "] and no index covers that. The database will read every row to answer it,"
                + " which is invisible while the table is small and is the whole cost once it is"
                + " not. Declare it: @Index(columns = {" + quoted(columns) + "}"
                + (order.isEmpty() ? "" : ", descending = {" + descending(model, order) + "}")
                + ") on " + model.type().getSimpleName() + ".");
    }

    /**
     * Whether any declared index answers this column sequence.
     *
     * <p>Any prefix of the query's columns is enough. A query filtering
     * {@code kit_id} and sorting {@code elo} is helped by an index on
     * {@code kit_id} alone — it narrows to one kit and sorts a handful of rows —
     * so warning about it would be warning about a query that is already fine,
     * and a warning that fires when nothing is wrong is a warning that gets
     * muted.
     */
    private boolean covers(@NotNull List<String> columns) {
        StringBuilder prefix = new StringBuilder(32);
        for (String column : columns) {
            if (!prefix.isEmpty()) {
                prefix.append(',');
            }
            prefix.append(column);
            if (covered.contains(prefix.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The column a query named, whether it named the column or the component.
     *
     * <p>An unknown name is passed through rather than rejected. The storage
     * layer rejects it a moment later with a message naming the model and every
     * column it has; throwing from a diagnostic would replace that message with
     * one about indexes.
     */
    private static @NotNull String resolve(@NotNull EntityModel<?> model, @NotNull String name) {
        ColumnModel column = model.column(name);
        if (column == null) {
            column = model.byComponent(name);
        }
        return column != null ? column.name() : name;
    }

    private static @NotNull String quoted(@NotNull List<String> columns) {
        StringBuilder list = new StringBuilder(48);
        for (String column : columns) {
            if (!list.isEmpty()) {
                list.append(", ");
            }
            list.append('"').append(column).append('"');
        }
        return list.toString();
    }

    private static @NotNull String descending(@NotNull EntityModel<?> model,
                                              @NotNull List<Query.Sort> order) {
        StringBuilder list = new StringBuilder(24);
        for (Query.Sort sort : order) {
            if (sort.ascending()) {
                continue;
            }
            if (!list.isEmpty()) {
                list.append(", ");
            }
            list.append('"').append(resolve(model, sort.column())).append('"');
        }
        return list.toString();
    }

    /** Test seam: how many distinct query shapes have been reported. */
    int reportedCount() {
        return reported.size();
    }

    /**
     * Test seam: forgets every computed coverage, and with it what was reported.
     *
     * <p>Package-private. Nothing in production should discard this — the state
     * is deliberately as long-lived as the record class — but a test asserting
     * that a warning fires exactly once must not inherit the "already said that"
     * of the test before it.
     */
    static void forgetAll() {
        BY_MODEL.clear();
    }

    @Override
    public String toString() {
        return "IndexCoverage[" + table + ", " + covered.size() + " prefixes]";
    }
}
