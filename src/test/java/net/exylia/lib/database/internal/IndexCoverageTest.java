package net.exylia.lib.database.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Query;
import net.exylia.lib.database.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warning about a query no index covers.
 *
 * <p>A missing index is invisible until the table is large, which is the whole
 * reason this diagnostic exists — and it is also why the "once" in "once per
 * shape" is not a nicety. A leaderboard menu opened by every player on the
 * server would print the same line every time it is opened, and a warning
 * printed a thousand times is a warning nobody reads: the same as not warning at
 * all, with a bigger log file.
 *
 * <p>In {@code internal} because the coverage type and its test seams are, and a
 * test in the public package could not reach them without widening the class's
 * surface for no reason.
 */
class IndexCoverageTest {

    /** Filter by kit, sort by elo descending: the shape the module exists for. */
    @Table("practice_player_stats")
    @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
    record KitStats(
            @Id String id,
            @Column("kit_id") String kitId,
            @Column int elo,
            @Column int wins,
            @Indexed @Column("played_at") long playedAt) {
    }

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void forget() {
        // The reported-once state is deliberately as long-lived as the record
        // class, so a test asserting that a line fires exactly once must not
        // inherit the "already said that" of the test before it.
        IndexCoverage.forgetAll();
        warnings.clear();
    }

    @AfterEach
    void tidy() {
        IndexCoverage.forgetAll();
    }

    private static EntityModel<KitStats> model() {
        return EntityModel.of(KitStats.class);
    }

    private void check(List<String> filters, List<Query.Sort> order) {
        IndexCoverage.check(model(), filters, order, warnings::add);
    }

    private static Query.Sort desc(String column) {
        return new Query.Sort(column, false);
    }

    private static Query.Sort asc(String column) {
        return new Query.Sort(column, true);
    }

    // ------------------------------------------------------------------ quiet

    @Test
    @DisplayName("a query the composite index covers says nothing")
    void coveredByTheComposite() {
        check(List.of("kit_id"), List.of(desc("elo")));
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a query on the leading column alone is covered")
    void leadingColumnIsCovered() {
        // The leftmost-prefix rule every engine here implements: an index on
        // (kit_id, elo) is ordered by kit_id first, so it answers a filter on
        // kit_id on its own.
        check(List.of("kit_id"), List.of());
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a query on a single @Indexed column is covered")
    void singleColumnIndexIsCovered() {
        check(List.of("played_at"), List.of());
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a query by primary key is covered without any annotation")
    void theKeyIsAlwaysCovered() {
        // Every engine indexes the key, and Mongo indexes _id. Warning about a
        // lookup by id would be warning about the one query that is never a scan.
        check(List.of("id"), List.of());
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a component name is resolved before it is judged")
    void componentNamesAreResolved() {
        // A caller filtering on "kitId" means the component; the column is
        // kit_id. Judging the unresolved name would report a covered query.
        check(List.of("kitId"), List.of(desc("elo")));
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("reading a whole table in no order is not reported")
    void unfilteredUnorderedIsNotReported() {
        // A full scan by definition, and no index changes that. Whether it is a
        // mistake depends entirely on the table, which this cannot know.
        check(List.of(), List.of());
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("a sort on a column already filtered does not need its own index")
    void aFilteredColumnCostsNothingToSort() {
        // kit_id is pinned to one value by the filter, so its position in the
        // index is spent and sorting by it is free.
        check(List.of("kit_id"), List.of(asc("kit_id")));
        assertEquals(List.of(), warnings);
    }

    // ------------------------------------------------------------------- loud

    @Test
    @DisplayName("a query on a column no index covers is reported")
    void uncoveredColumn() {
        check(List.of("wins"), List.of());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("practice_player_stats"), warnings.get(0));
        assertTrue(warnings.get(0).contains("wins"), warnings.get(0));
    }

    @Test
    @DisplayName("a sort on a column that is only the tail of an index is reported")
    void aSortOnTheTailIsNotCovered() {
        // elo is the second column of (kit_id, elo), so its values are scattered
        // through the index. Sorting by it without filtering kit_id reads the
        // whole table — which is the mistake that looks fine in a code review.
        check(List.of(), List.of(desc("elo")));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("elo"), warnings.get(0));
    }

    @Test
    @DisplayName("the message names the annotation that would fix it, with the right direction")
    void theMessageIsActionable() {
        check(List.of("wins"), List.of(desc("played_at")));
        String line = warnings.get(0);
        // A diagnostic that only says "this is slow" sends a developer to read
        // the source of the module. One that spells the annotation does not.
        assertTrue(line.contains("@Index(columns = {\"wins\", \"played_at\"}"), line);
        assertTrue(line.contains("descending = {\"played_at\"}"), line);
        assertTrue(line.contains("KitStats"), line);
    }

    @Test
    @DisplayName("filter columns come before sort columns in the suggestion")
    void filtersComeFirst() {
        // The order an index has to have them in: a database narrows with the
        // filter and then wants what is left already sorted. Suggesting the sort
        // column first would suggest an index that does not help.
        check(List.of("wins"), List.of(asc("played_at")));
        assertTrue(warnings.get(0).contains("[wins,played_at]"), warnings.get(0));
    }

    // ------------------------------------------------------------- exactly once

    @Test
    @DisplayName("the same uncovered query is reported once, however often it runs")
    void reportedOnce() {
        for (int repeat = 0; repeat < 100; repeat++) {
            check(List.of("wins"), List.of());
        }
        // A leaderboard menu every player opens would otherwise print this on
        // every click.
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("two different uncovered shapes are each reported once")
    void oncePerShape() {
        check(List.of("wins"), List.of());
        check(List.of("wins"), List.of());
        check(List.of(), List.of(desc("elo")));
        check(List.of(), List.of(desc("elo")));

        assertEquals(2, warnings.size());
        assertEquals(2, IndexCoverage.of(model()).reportedCount());
    }

    @Test
    @DisplayName("a filter whose leading column is indexed is covered even with more columns")
    void aCoveredPrefixIsEnough() {
        // kit_id narrows to one kit; filtering wins inside that is a handful of
        // rows. Warning here would be warning about a query that is already fine,
        // and a warning that fires when nothing is wrong is a warning that gets
        // muted.
        check(List.of("kit_id", "wins"), List.of());
        assertEquals(List.of(), warnings);
    }

    @Test
    @DisplayName("the same shape written with component names is the same shape")
    void componentAndColumnNamesAreOneShape() {
        // Resolved before the shape is built, so "wins,playedAt" and
        // "wins,played_at" do not each get their own line for one missing index.
        check(List.of("wins", "playedAt"), List.of());
        check(List.of("wins", "played_at"), List.of());
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("reporting is not per repository: two views of one record share it")
    void sharedAcrossRepositories() {
        // The state hangs off the compiled model, which EntityModel caches for
        // the life of the JVM. Two plugins registering the same record — or one
        // plugin reloading — do not double the warning.
        IndexCoverage.check(EntityModel.of(KitStats.class), List.of("wins"), List.of(), warnings::add);
        IndexCoverage.check(EntityModel.of(KitStats.class), List.of("wins"), List.of(), warnings::add);
        assertEquals(1, warnings.size());
    }

    // ------------------------------------------------------------------ safety

    @Test
    @DisplayName("a column that does not exist is left for the storage layer to refuse")
    void unknownColumnIsPassedThrough() {
        // Throwing from a diagnostic would replace the storage layer's message —
        // which names the model and every column it has — with one about indexes.
        check(List.of("nonsense"), List.of());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("nonsense"), warnings.get(0));
    }

    @Test
    @DisplayName("a model with no indexes at all reports its first filtered query")
    void noIndexesAtAll() {
        @Table("plain")
        record Plain(@Id String id, @Column int value) {
        }
        List<String> lines = new CopyOnWriteArrayList<>();
        IndexCoverage.check(EntityModel.of(Plain.class), List.of("value"), List.of(), lines::add);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("plain"), lines.get(0));
    }
}
