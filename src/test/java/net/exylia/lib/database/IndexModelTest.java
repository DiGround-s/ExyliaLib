package net.exylia.lib.database;

import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.IndexModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiling {@link Index} and {@link Indexed} into one list.
 *
 * <p>Every failure asserted here is a mistake in code, identical on every row
 * and every server, so it is worth exactly one loud failure at registration
 * where the developer is watching. The alternative for each of them is the same
 * and is worse: an index that is quietly not the one that was asked for, whose
 * only symptom is a table scan on a server that is already busy.
 */
class IndexModelTest {

    // ------------------------------------------------------------- unification

    @Nested
    @DisplayName("one list, whichever annotation asked")
    class Unified {

        /**
         * Both mechanisms on one record, which is what the ecosystem's entities
         * look like: a couple of single-column lookups and the composite index
         * the leaderboard needs.
         */
        @Table("practice_player_stats")
        @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
        record Mixed(
                @Id UUID uuid,
                @Column("kit_id") String kitId,
                @Column int elo,
                @Indexed @Column("played_at") long playedAt,
                @Column(unique = true) String handle) {
        }

        @Test
        @DisplayName("@Indexed, @Column(unique) and @Index all arrive in indexes()")
        void everythingIsInOneList() {
            // The requirement that made this a rewrite rather than an addition:
            // the schema layer must have exactly one thing to iterate. Two
            // parallel mechanisms mean every later change has to be written
            // twice and kept in step by memory.
            List<IndexModel> indexes = EntityModel.of(Mixed.class).indexes();
            List<List<String>> covered = new ArrayList<>();
            for (IndexModel index : indexes) {
                covered.add(index.columns());
            }
            assertEquals(List.of(
                            List.of("played_at"),
                            List.of("handle"),
                            List.of("kit_id", "elo")),
                    covered);
        }

        @Test
        @DisplayName("a single-column @Indexed is the same type as a composite @Index")
        void singleColumnIsNotASpecialCase() {
            List<IndexModel> indexes = EntityModel.of(Mixed.class).indexes();
            IndexModel single = indexes.get(0);
            IndexModel composite = indexes.get(2);

            assertFalse(single.composite());
            assertTrue(composite.composite());
            // Same class, same accessors, same name shape. Nothing downstream
            // has to ask which annotation produced it.
            assertEquals("idx_practice_player_stats_played_at", single.name());
            assertEquals("idx_practice_player_stats_kit_id_elo", composite.name());
            assertEquals(List.of(IndexModel.Part.asc("played_at")), single.parts());
        }

        @Test
        @DisplayName("component-level indexes come first, then the record's, each in order")
        void orderIsDeclarationOrder() {
            // Stable because a SchemaReport lists what it created, and a report
            // whose order moved between starts is a report nobody can diff.
            EntityModel<Mixed> model = EntityModel.of(Mixed.class);
            assertEquals(model.indexes(), EntityModel.of(Mixed.class).indexes());
        }

        @Test
        @DisplayName("@Column(unique) produces a unique index and @Indexed does not")
        void uniquenessCarriesThrough() {
            List<IndexModel> indexes = EntityModel.of(Mixed.class).indexes();
            assertFalse(indexes.get(0).unique());
            assertTrue(indexes.get(1).unique());
            assertFalse(indexes.get(2).unique());
        }

        @Table("keyed")
        @Index(columns = {"uuid", "elo"})
        record Keyed(@Id UUID uuid, @Column int elo) {
        }

        @Test
        @DisplayName("the primary key gets no index of its own, but may lead a composite one")
        void theKeyIsNotIndexedAlone() {
            // Two different things. A second index over the key alone is a write
            // per insert for nothing, since every engine indexes it. A composite
            // starting with the key is a real index the key's own cannot answer.
            List<IndexModel> indexes = EntityModel.of(Keyed.class).indexes();
            assertEquals(1, indexes.size());
            assertEquals(List.of("uuid", "elo"), indexes.get(0).columns());
        }
    }

    // -------------------------------------------------------------- directions

    @Nested
    @DisplayName("per-column direction")
    class Directions {

        @Table("boards")
        @Index(columns = {"season", "kit_id", "elo"}, descending = {"elo"})
        @Index(columns = {"season", "elo"}, descending = {"elo", "season"})
        record Boards(
                @Id String id,
                @Column int season,
                @Column("kit_id") String kitId,
                @Column int elo) {
        }

        @Test
        @DisplayName("only the named columns are descending, and they keep their position")
        void namedColumnsAreDescending() {
            IndexModel index = EntityModel.of(Boards.class).indexes().get(0);
            assertEquals(List.of(
                            IndexModel.Part.asc("season"),
                            IndexModel.Part.asc("kit_id"),
                            IndexModel.Part.desc("elo")),
                    index.parts());
        }

        @Test
        @DisplayName("descending is named, not positional, so several columns can be listed")
        void severalDescendingColumns() {
            // The reason descending() is a list of names rather than an array of
            // 1 and -1 as commons had it: a reader can see which column is
            // descending without counting.
            IndexModel index = EntityModel.of(Boards.class).indexes().get(1);
            assertEquals(List.of(
                            IndexModel.Part.desc("season"),
                            IndexModel.Part.desc("elo")),
                    index.parts());
        }

        @Table("components")
        @Index(columns = {"kitId", "playedAt"}, descending = {"playedAt"})
        record Components(
                @Id String id,
                @Column("kit_id") String kitId,
                @Column("played_at") long playedAt) {
        }

        @Test
        @DisplayName("a component name is accepted and normalised to the column name")
        void componentNamesAreNormalised() {
            // A developer writing an index next to the components reads kitId,
            // while the database has kit_id. Normalising here is what keeps the
            // CREATE INDEX and the metadata lookup addressing the same column.
            IndexModel index = EntityModel.of(Components.class).indexes().get(0);
            assertEquals(List.of("kit_id", "played_at"), index.columns());
            assertTrue(index.parts().get(1).descending());
            assertEquals("idx_components_kit_id_played_at", index.name());
        }
    }

    // -------------------------------------------------------------- validation

    @Nested
    @DisplayName("validation at compilation")
    class Validation {

        @Table("unknown")
        @Index(columns = {"kit_id", "rating"})
        record UnknownColumn(@Id String id, @Column("kit_id") String kitId, @Column int elo) {
        }

        @Test
        @DisplayName("an index over a column that does not exist is refused")
        void unknownColumn() {
            // Skipping it would build an index over fewer columns than were
            // asked for, which does not answer the query it exists for, and the
            // only symptom is a table scan on a live server.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> EntityModel.of(UnknownColumn.class));
            assertTrue(refused.getMessage().contains("rating"), refused.getMessage());
            assertTrue(refused.getMessage().contains("kit_id"), refused.getMessage());
        }

        @Table("unstored")
        @Index(columns = {"derived"})
        record UnstoredColumn(@Id String id, @Column int elo, String derived) {
        }

        @Test
        @DisplayName("an index over an unannotated component is refused, naming why")
        void unstoredComponent() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> EntityModel.of(UnstoredColumn.class));
            assertTrue(refused.getMessage().contains("not stored"), refused.getMessage());
        }

        @Table("stray")
        @Index(columns = {"kit_id"}, descending = {"elo"})
        record StrayDescending(@Id String id, @Column("kit_id") String kitId, @Column int elo) {
        }

        @Test
        @DisplayName("a descending column outside the index is refused")
        void descendingMustBeASubset() {
            // Either a typo or a misunderstanding, and both produce an index
            // sorted the wrong way for the query it was written for.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> EntityModel.of(StrayDescending.class));
            assertTrue(refused.getMessage().contains("descending"), refused.getMessage());
            assertTrue(refused.getMessage().contains("elo"), refused.getMessage());
        }

        @Table("dupes")
        @Index(columns = {"kit_id", "elo"}, name = "idx_same")
        @Index(columns = {"kit_id", "wins"}, name = "idx_same")
        record DuplicateName(
                @Id String id,
                @Column("kit_id") String kitId,
                @Column int elo,
                @Column int wins) {
        }

        @Test
        @DisplayName("two indexes under one name are refused")
        void duplicateName() {
            // The failure this prevents is silent: the second CREATE INDEX would
            // read as "already exists", which the schema layer forgives, so one
            // of the two indexes would simply never be created.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> EntityModel.of(DuplicateName.class));
            assertTrue(refused.getMessage().contains("idx_same"), refused.getMessage());
        }

        @Table("shadowed")
        @Index(columns = {"clan"}, name = "idx_shadowed_clan")
        record ShadowsAnIndexed(@Id String id, @Indexed @Column(length = 32) String clan) {
        }

        @Test
        @DisplayName("an @Index colliding with an @Indexed name is refused too")
        void duplicateAcrossAnnotations() {
            // The unified list is what makes this detectable at all. With two
            // parallel mechanisms the collision would only appear in the
            // database, as one of the two indexes silently missing.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> EntityModel.of(ShadowsAnIndexed.class));
            assertTrue(refused.getMessage().contains("idx_shadowed_clan"), refused.getMessage());
        }

        @Table("unbounded")
        @Index(columns = {"kit_id", "notes"})
        record UnboundedInComposite(
                @Id String id,
                @Column("kit_id") String kitId,
                @Column(length = Column.UNBOUNDED) String notes) {
        }

        @Test
        @DisplayName("a composite index over an unbounded text column is refused")
        void unboundedTextInAComposite() {
            // There is no length at which a LONGTEXT or TEXT column can be one
            // part of a multi-column key, on any engine. Reported rather than
            // attempted: MySQL would refuse the statement and MariaDB would
            // build a prefix of it and say nothing.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> EntityModel.of(UnboundedInComposite.class));
            assertTrue(refused.getMessage().contains("unbounded"), refused.getMessage());
            assertTrue(refused.getMessage().contains("notes"), refused.getMessage());
        }

        @Table("single_unbounded")
        @Index(columns = {"notes"})
        record UnboundedAlone(
                @Id String id,
                @Column(length = Column.UNBOUNDED) String notes) {
        }

        @Test
        @DisplayName("a single-column index over unbounded text compiles, and the dialect reports it")
        void unboundedTextAloneIsTheDialectsToReport() {
            // Deliberately not refused here. Whether a lone index on a long text
            // column is possible depends on the engine — a prefix index is a real
            // thing on MySQL — so the dialect reports it with its own limit in
            // the message, and it is a warning rather than a failure.
            EntityModel<UnboundedAlone> model = EntityModel.of(UnboundedAlone.class);
            assertEquals(1, model.indexes().size());
        }

        @Table("twice")
        @Index(columns = {"elo", "elo"})
        record RepeatedColumn(@Id String id, @Column int elo) {
        }

        @Test
        @DisplayName("naming one column twice in an index is refused")
        void repeatedColumn() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> EntityModel.of(RepeatedColumn.class));
            assertTrue(refused.getMessage().contains("twice"), refused.getMessage());
        }

        @Table("empty")
        @Index(columns = {})
        record NoColumns(@Id String id, @Column int elo) {
        }

        @Test
        @DisplayName("an index over no columns is refused")
        void noColumns() {
            assertThrows(IllegalArgumentException.class, () -> EntityModel.of(NoColumns.class));
        }
    }

    // -------------------------------------------------------------------- name

    @Nested
    @DisplayName("derived names")
    class Names {

        @Test
        @DisplayName("a name is derived from the table and every column, in order")
        void derivedFromTableAndColumns() {
            assertEquals("idx_stats_kit_id_elo", IndexModel.derivedName("stats",
                    List.of(IndexModel.Part.asc("kit_id"), IndexModel.Part.desc("elo"))));
        }

        @Test
        @DisplayName("the direction does not change the name")
        void directionIsNotInTheName() {
            // On purpose: an index whose direction changed between releases is
            // the same index rebuilt, not a second one, and the schema layer has
            // to recognise it under the name it already carries.
            assertEquals(
                    IndexModel.derivedName("stats", List.of(IndexModel.Part.asc("elo"))),
                    IndexModel.derivedName("stats", List.of(IndexModel.Part.desc("elo"))));
        }

        @Test
        @DisplayName("a long name is truncated to 60 characters with a stable hash suffix")
        void truncation() {
            String table = "practice_player_statistics_history_by_season";
            List<IndexModel.Part> parts = List.of(
                    IndexModel.Part.asc("kit_identifier_column"),
                    IndexModel.Part.desc("elo_rating_column"));

            String name = IndexModel.derivedName(table, parts);
            // 60, not 64: Postgres cuts at 63 bytes and MySQL at 64, and the
            // margin leaves room for the suffix.
            assertTrue(name.length() <= 60, name);
            assertEquals(name, IndexModel.derivedName(table, parts));
            assertTrue(name.startsWith("idx_" + table));
        }

        @Test
        @DisplayName("two names that truncate to the same prefix stay distinct")
        void truncationDoesNotCollide() {
            // The failure mode without the suffix: two indexes collapse into one
            // name, the second CREATE INDEX reads as "already exists", the schema
            // layer forgives it, and the index is never created. Silently, on
            // every start, forever.
            String table = "practice_player_statistics_history_by_season";
            String one = IndexModel.derivedName(table,
                    List.of(IndexModel.Part.asc("kit_identifier_column_one")));
            String two = IndexModel.derivedName(table,
                    List.of(IndexModel.Part.asc("kit_identifier_column_two")));

            assertNotEquals(one, two);
            assertTrue(one.length() <= 60);
            assertTrue(two.length() <= 60);
        }

        @Test
        @DisplayName("a name exactly at the limit is not truncated")
        void nameAtTheLimit() {
            // "idx_" + 52 + "_" + 3 = 60
            String name = IndexModel.derivedName("t".repeat(52),
                    List.of(IndexModel.Part.asc("elo")));
            assertEquals(60, name.length());
            assertEquals("idx_" + "t".repeat(52) + "_elo", name);
        }

        @Test
        @DisplayName("an index over no parts is not an index")
        void noParts() {
            assertThrows(IllegalArgumentException.class,
                    () -> new IndexModel("idx", List.of(), false));
        }
    }
}
