package net.exylia.lib.database.internal;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * One index a record asks for, compiled and validated.
 *
 * <p>The single thing the schema layers iterate. Both
 * {@link net.exylia.lib.database.Index} on the record and
 * {@link net.exylia.lib.database.Indexed} on a component are compiled into this
 * — the second one simply produces a one-column ascending index — so
 * {@link SqlSchema} and {@link MongoBackend} have exactly one list to walk and
 * one shape to understand.
 *
 * <p>That unification is the point. Two parallel mechanisms would mean every
 * later change (a report line, a metadata comparison, a Mongo compound spec)
 * had to be written twice and kept in step by memory, which is how a
 * single-column index ends up recognised as existing and a composite one gets
 * recreated on every start.
 *
 * <pre>{@code
 * // @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
 * IndexModel index = ...;
 * index.name();      // idx_practice_player_stats_kit_id_elo
 * index.columns();   // [kit_id, elo]
 * index.parts();     // [kit_id ASC, elo DESC]
 * }</pre>
 *
 * <h2>Why the parts are ordered and carry a direction each</h2>
 * A leaderboard asks for "the top ten of this kit by elo, highest first". Only
 * an index whose columns are in that order, with that direction on the sort
 * column, is already in the answer's order; anything else makes the engine sort
 * what the filter left behind. The order and the per-column direction are
 * therefore data, not decoration, and they survive all the way into the
 * {@code CREATE INDEX} and into the Mongo compound spec.
 *
 * <h2>Threads</h2>
 * Immutable, and shared by every thread that touches the model it came from.
 *
 * @param name   the index name, as it will exist in the database
 * @param parts  the columns in order, each with its direction; never empty
 * @param unique whether the database enforces that no two rows share the combination
 * @since 1.24.0
 */
public record IndexModel(@NotNull String name, @NotNull List<Part> parts, boolean unique) {

    /**
     * The longest generated name that fits every engine.
     *
     * <p>Postgres cuts an identifier at 63 bytes and MySQL and MariaDB at 64.
     * Sixty leaves room for the disambiguating suffix a truncated name gets and
     * keeps the same name usable on all of them, which is what lets a metadata
     * lookup recognise the index rather than create a second one under the name
     * the engine happened to store.
     */
    static final int MAX_NAME_LENGTH = 60;

    /**
     * One column of an index, and which way it is sorted.
     *
     * @param column     the column name, as the database has it
     * @param descending whether the index stores this column largest first
     */
    public record Part(@NotNull String column, boolean descending) {

        /** An ascending part, which is what an index column is unless said otherwise. */
        public static @NotNull Part asc(@NotNull String column) {
            return new Part(column, false);
        }

        /** A descending part: a leaderboard's sort column. */
        public static @NotNull Part desc(@NotNull String column) {
            return new Part(column, true);
        }

        @Override
        public String toString() {
            return column + (descending ? " DESC" : " ASC");
        }
    }

    /** Compact constructor, defensive: the list outlives the builder that made it. */
    public IndexModel {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("An index over no columns is not an index.");
        }
        parts = List.copyOf(parts);
    }

    /**
     * The columns this index covers, in order, without their directions.
     *
     * <p>For the comparisons where direction is not the question: whether a
     * live index covers the same columns, and whether a query's filter and sort
     * fall inside one. A fresh list per call is not worth avoiding — every
     * caller is a schema step or a registration, never a per-row path.
     *
     * @return the column names, in index order
     */
    public @NotNull List<String> columns() {
        List<String> names = new ArrayList<>(parts.size());
        for (Part part : parts) {
            names.add(part.column());
        }
        return List.copyOf(names);
    }

    /** Whether this index covers more than one column. */
    public boolean composite() {
        return parts.size() > 1;
    }

    /**
     * The name an index gets when the annotation did not give it one.
     *
     * <p>Derived from the table and the columns rather than from a counter, so
     * that the name is the same on every start and on every server: the schema
     * layer recognises an existing index by the name it would generate, and a
     * name that moved between releases would create a second index over the
     * same columns and leave the first one behind forever.
     *
     * <p>Truncated with a stable hash suffix when it would overrun an engine's
     * identifier limit. Truncating alone is not enough — two long column lists
     * on one table would collapse into the same name, the second
     * {@code CREATE INDEX} would fail as "already exists", and that is precisely
     * the error the schema layer is written to forgive. The index would simply
     * never be created, and nothing anywhere would say so.
     *
     * @param table the table name, as the record declares it
     * @param parts the index columns in order
     * @return a name that fits every engine's identifier limit
     */
    public static @NotNull String derivedName(@NotNull String table, @NotNull List<Part> parts) {
        StringBuilder name = new StringBuilder(32).append("idx_").append(table);
        for (Part part : parts) {
            name.append('_').append(part.column());
        }
        String full = name.toString();
        if (full.length() <= MAX_NAME_LENGTH) {
            return full;
        }
        // String.hashCode is specified, so this suffix is the same on every JVM
        // and every restart — which it has to be, or the name would move.
        String suffix = Integer.toHexString(full.hashCode());
        return full.substring(0, MAX_NAME_LENGTH - suffix.length() - 1) + "_" + suffix;
    }

    @Override
    public String toString() {
        return (unique ? "UNIQUE " : "") + name + parts;
    }
}
