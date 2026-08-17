package net.exylia.lib.database;

import net.exylia.lib.database.internal.ColumnModel;
import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import net.exylia.lib.database.internal.IndexModel;
import net.exylia.lib.database.internal.MongoDocuments;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mongo document mapping, without a Mongo.
 *
 * <p>There is no {@code mongod} on a build machine and there will not be one,
 * so everything worth checking about this backend was deliberately pushed into
 * a class the driver does not appear in. What is left in
 * {@code MongoBackend} is round trips to a server; what is here is every
 * decision — which field a column becomes, which BSON family a value lands in,
 * what a filter looks like — and each of these tests fails for a real bug that
 * would otherwise reach production silently:
 *
 * <ul>
 *   <li>an {@code int} written as text sorts 9 above 10 on a leaderboard;</li>
 *   <li>an {@code _id} not mapped back reads every record with a null key;</li>
 *   <li>a filter value not encoded through its column matches nothing, and
 *       Mongo reports that as an empty result rather than as an error.</li>
 * </ul>
 */
class MongoDocumentsTest {

    enum Rank { DEFAULT, VIP, MVP }

    /**
     * Every {@code storedType} the mapper can produce, on one record.
     *
     * <p>{@code int}, {@code long}, {@code double}, {@code boolean},
     * {@code String} and {@code BigDecimal} are the six the task names, and
     * {@code float}, {@code short} and {@code byte} are here because BSON has
     * no such families and something has to decide what happens to them.
     */
    @Table("player_stats")
    record Stats(
            @Id UUID uuid,
            @Column int elo,
            @Column("kill_streak") int killStreak,
            @Column long playtime,
            @Column double ratio,
            @Column float accuracy,
            @Column short level,
            @Column byte prestige,
            @Column boolean banned,
            @Indexed @Column(length = 32) String clan,
            @Column Rank rank,
            @Column BigDecimal balance,
            @Column(length = Column.UNBOUNDED) String notes,
            @Column List<String> tags) {
    }

    private static final UUID KEY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final EntityModel<Stats> model = EntityModel.of(Stats.class);

    private static Stats stats() {
        return new Stats(KEY, 1840, 7, 900L, 1.75d, 0.25f, (short) 12, (byte) 3, false,
                "Nova", Rank.VIP, new BigDecimal("12.34"), "a note", List.of("a", "b"));
    }

    // ------------------------------------------------------------------ _id

    @Nested
    @DisplayName("the key lives in _id")
    class Identity {

        @Test
        @DisplayName("the key is written to _id and not to its own name")
        void keyBecomesUnderscoreId() {
            Map<String, Object> document = MongoDocuments.toDocument(model, stats());
            assertEquals(KEY.toString(), document.get("_id"));
            // Under its own name too, it would be a second copy of every UUID
            // in the collection and a second index to make it findable.
            assertFalse(document.containsKey("uuid"));
        }

        @Test
        @DisplayName("_id is mapped back to the key column when reading")
        void underscoreIdIsMappedBack() {
            Map<String, Object> document = MongoDocuments.toDocument(model, stats());
            assertEquals(KEY, MongoDocuments.fromDocument(model, document).uuid());
        }

        @Test
        @DisplayName("a document read without the mapping has no key at all")
        void withoutTheMappingTheKeyIsLost() {
            // The bug this mapping exists to prevent: EntityModel asks for
            // "uuid", the document has "_id", and the record comes back with a
            // null key and no complaint from anywhere.
            Map<String, Object> document = MongoDocuments.toDocument(model, stats());
            assertNull(model.read(document::get).uuid());
        }

        @Test
        @DisplayName("fieldOf names _id for the key and the column name for the rest")
        void fieldNames() {
            assertEquals("_id", MongoDocuments.fieldOf(model.id()));
            assertEquals("kill_streak",
                    MongoDocuments.fieldOf(MongoDocuments.columnOf(model, "killStreak")));
        }

        @Test
        @DisplayName("a record whose key encodes to nothing is refused, not written")
        void keylessRecordIsRefused() {
            // Writing it would let the server invent an ObjectId for _id, and
            // the record would be unfindable by the key it says it has.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> MongoDocuments.toDocument(model, new Stats(null, 0, 0, 0L, 0d, 0f,
                            (short) 0, (byte) 0, false, null, null, null, null, List.of())));
            assertTrue(refused.getMessage().contains("_id"));
        }
    }

    // --------------------------------------------------------------- types

    @Nested
    @DisplayName("values keep their type")
    class Types {

        @Test
        @DisplayName("every stored type lands in the BSON family that can sort it")
        void storedTypesAreNative() {
            Map<String, Object> document = MongoDocuments.toDocument(model, stats());

            // The whole point of this backend. As text, $sort on elo puts 9
            // above 10 and no index can answer a range query.
            assertInstanceOf(Integer.class, document.get("elo"));
            assertInstanceOf(Integer.class, document.get("kill_streak"));
            assertInstanceOf(Long.class, document.get("playtime"));
            assertInstanceOf(Double.class, document.get("ratio"));
            assertInstanceOf(Boolean.class, document.get("banned"));
            assertInstanceOf(String.class, document.get("clan"));
            assertInstanceOf(BigDecimal.class, document.get("balance"));

            assertEquals(1840, document.get("elo"));
            assertEquals(900L, document.get("playtime"));
            assertEquals(1.75d, document.get("ratio"));
            assertEquals(Boolean.FALSE, document.get("banned"));
        }

        @Test
        @DisplayName("a float is widened to a double, not stringified")
        void floatBecomesDouble() {
            // BSON has no 32-bit float. Widening is exact both ways; text is
            // not, and 0.1f written as "0.10000000149011612" is a value nobody
            // wrote.
            Map<String, Object> document = MongoDocuments.toDocument(model, stats());
            assertInstanceOf(Double.class, document.get("accuracy"));
            assertEquals(0.25f, MongoDocuments.fromDocument(model, document).accuracy());
        }

        @Test
        @DisplayName("short and byte are widened to int32 and narrowed back")
        void smallIntegersWiden() {
            Map<String, Object> document = MongoDocuments.toDocument(model, stats());
            assertInstanceOf(Integer.class, document.get("level"));
            assertInstanceOf(Integer.class, document.get("prestige"));

            Stats back = MongoDocuments.fromDocument(model, document);
            assertEquals((short) 12, back.level());
            assertEquals((byte) 3, back.prestige());
        }

        @Test
        @DisplayName("a UUID is stored as its text, not as a BSON binary")
        void uuidStaysAString() {
            // The codec layer already produced a String and the SQL backends
            // store that same String. A value that round-trips through a
            // different representation on one backend is a value two backends
            // disagree about.
            Object id = MongoDocuments.toDocument(model, stats()).get("_id");
            assertInstanceOf(String.class, id);
            assertEquals(36, ((String) id).length());
            assertEquals(KEY.toString(), id);
        }

        @Test
        @DisplayName("an enum and a list stay the strings their codecs made")
        void codecOutputStaysText() {
            Map<String, Object> document = MongoDocuments.toDocument(model, stats());
            assertEquals("VIP", document.get("rank"));
            assertInstanceOf(String.class, document.get("tags"));
            assertEquals("[\"a\",\"b\"]", document.get("tags"));
        }

        @Test
        @DisplayName("BigDecimal is not routed through a double")
        void moneyKeepsItsScale() {
            // The only reason a column is a BigDecimal. Through a binary double
            // this is how 0.1 becomes 0.09999999999999999 in somebody's
            // balance.
            BigDecimal exact = new BigDecimal("0.1");
            assertSame(exact, MongoDocuments.bsonValue(exact));
        }

        @Test
        @DisplayName("bsonValue passes null through as nothing to store")
        void nullIsNothing() {
            assertNull(MongoDocuments.bsonValue(null));
        }
    }

    // ------------------------------------------------------------ round trip

    @Nested
    @DisplayName("a record survives the round trip")
    class RoundTrip {

        @Test
        @DisplayName("every column comes back as it went in")
        void everyColumnRoundTrips() {
            Stats original = stats();
            Stats back = MongoDocuments.fromDocument(model,
                    MongoDocuments.toDocument(model, original));
            assertEquals(original, back);
        }

        @Test
        @DisplayName("a null column is left out rather than written as null")
        void nullsAreOmitted() {
            Stats sparse = new Stats(KEY, 0, 0, 0L, 0d, 0f, (short) 0, (byte) 0, false,
                    null, Rank.DEFAULT, null, null, List.of());
            Map<String, Object> document = MongoDocuments.toDocument(model, sparse);

            // Indistinguishable to a query, to a unique index and on the way
            // back, and smaller. An empty inventory slot encodes to nothing at
            // all, and there are a lot of those.
            assertFalse(document.containsKey("clan"));
            assertFalse(document.containsKey("balance"));
            assertFalse(document.containsKey("notes"));

            Stats back = MongoDocuments.fromDocument(model, document);
            assertNull(back.clan());
            assertNull(back.balance());
        }

        @Test
        @DisplayName("a document missing a field the record gained reads as absent")
        void anOlderDocumentStillReads() {
            // Mongo is schemaless: a record that gains a component needs no
            // migration, and the documents written before it simply lack the
            // field. This is the normal case, not the exotic one.
            Map<String, Object> older = new LinkedHashMap<>();
            older.put("_id", KEY.toString());
            older.put("elo", 1500);

            Stats back = MongoDocuments.fromDocument(model, older);
            assertEquals(KEY, back.uuid());
            assertEquals(1500, back.elo());
            assertEquals(0L, back.playtime());
            assertNull(back.clan());
            assertEquals(List.of(), back.tags());
        }

        @Test
        @DisplayName("a numeric field a driver hands back as another width still reads")
        void widthsAreCoerced() {
            // A value written by an older version of a document is whatever
            // type it was then, and a $inc on an int32 that overflows becomes
            // an int64 on the server.
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("_id", KEY.toString());
            document.put("elo", 1840L);
            document.put("playtime", 900);
            document.put("ratio", 2);

            Stats back = MongoDocuments.fromDocument(model, document);
            assertEquals(1840, back.elo());
            assertEquals(900L, back.playtime());
            assertEquals(2d, back.ratio());
        }

        @Test
        @DisplayName("idValue encodes a key exactly as the column that stores it does")
        void idValueMatchesTheStoredForm() {
            // A key encoded differently from its field matches nothing, and
            // Mongo reports that as "no such document" rather than as an error.
            assertEquals(MongoDocuments.toDocument(model, stats()).get("_id"),
                    MongoDocuments.idValue(model, KEY));
        }
    }

    // -------------------------------------------------------------- queries

    @Nested
    @DisplayName("filters and sorts")
    class Queries {

        @Test
        @DisplayName("a filter names fields and encodes values through their columns")
        void filterEncodesValues() {
            Map<String, Object> filter = MongoDocuments.filter(model,
                    List.of("uuid", "killStreak", "rank"), List.of(KEY, 7, Rank.MVP));

            assertEquals(KEY.toString(), filter.get("_id"));
            assertEquals(7, filter.get("kill_streak"));
            assertEquals("MVP", filter.get("rank"));
        }

        @Test
        @DisplayName("a filter on a component name is translated to the column name")
        void componentNamesAreTranslated() {
            // Untranslated, this queries a field no document has: Mongo returns
            // an empty result rather than an error, so the query looks like it
            // worked and found nobody.
            Map<String, Object> filter = MongoDocuments.filter(model,
                    List.of("killStreak"), List.of(7));
            assertEquals(Map.of("kill_streak", 7), filter);
        }

        @Test
        @DisplayName("an empty filter is an empty document, which matches everything")
        void emptyFilterMatchesAll() {
            assertEquals(Map.of(), MongoDocuments.filter(model, List.of(), List.of()));
        }

        @Test
        @DisplayName("a null filter value is kept rather than dropped")
        void nullFilterValue() {
            // "Everyone with no clan" is a real query, and Mongo reads
            // {clan: null} as "null or absent" — exactly what the write path
            // produces for an unset column.
            Map<String, Object> filter = MongoDocuments.filter(model,
                    List.of("clan"), java.util.Collections.singletonList(null));
            assertTrue(filter.containsKey("clan"));
            assertNull(filter.get("clan"));
        }

        @Test
        @DisplayName("two filters on one field become an $and, not one that overwrites the other")
        void repeatedFieldBecomesAnd() {
            Map<String, Object> filter = MongoDocuments.filter(model,
                    List.of("clan", "clan"), List.of("Nova", "Orion"));

            assertEquals(List.of("$and"), List.copyOf(filter.keySet()));
            assertEquals(List.of(Map.of("clan", "Nova"), Map.of("clan", "Orion")),
                    filter.get("$and"));
        }

        @Test
        @DisplayName("a filter with mismatched columns and values is refused")
        void mismatchedFilterIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> MongoDocuments.filter(model, List.of("clan"), List.of()));
        }

        @Test
        @DisplayName("a filter on a name the model has not is refused, naming the model")
        void unknownFieldIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> MongoDocuments.filter(model, List.of("nope"), List.of(1)));
            assertTrue(refused.getMessage().contains("nope"));
            assertTrue(refused.getMessage().contains("player_stats"));
        }

        @Test
        @DisplayName("a sort is 1 ascending and -1 descending, in the order given")
        void sortDirections() {
            Map<String, Object> sort = MongoDocuments.sort(model,
                    List.of(Dialect.Sort.desc("elo"), Dialect.Sort.asc("clan")));

            assertEquals(List.of("elo", "clan"), List.copyOf(sort.keySet()));
            assertEquals(-1, sort.get("elo"));
            assertEquals(1, sort.get("clan"));
        }

        @Test
        @DisplayName("a sort on the key sorts _id")
        void sortOnTheKey() {
            assertEquals(Map.of("_id", 1),
                    MongoDocuments.sort(model, List.of(Dialect.Sort.asc("uuid"))));
        }

        @Test
        @DisplayName("no order asked for is an empty sort document")
        void emptySort() {
            assertEquals(Map.of(), MongoDocuments.sort(model, List.of()));
        }
    }

    // -------------------------------------------------------------- indexes

    @Nested
    @DisplayName("index key specs")
    class Indexes {

        @Test
        @DisplayName("an @Indexed column produces a one-field ascending key")
        void indexedColumn() {
            List<IndexModel> indexes = model.indexes();
            assertEquals(1, indexes.size());
            assertEquals("idx_player_stats_clan", indexes.get(0).name());
            assertFalse(indexes.get(0).unique());
            assertEquals(Map.of("clan", 1), MongoDocuments.keySpec(model, indexes.get(0)));
        }

        @Test
        @DisplayName("the key is never given a second index")
        void theKeyIsNotIndexedTwice() {
            // Mongo indexes _id uniquely on every collection whether asked to
            // or not. A second one costs a write per insert and answers nothing
            // the first does not.
            for (IndexModel index : model.indexes()) {
                assertFalse(index.columns().contains("uuid"));
                assertFalse(MongoDocuments.keySpec(model, index).containsKey("_id"));
            }
        }

        @Table("accounts")
        record Account(@Id String id, @Column(unique = true) String name, @Column int coins) {
        }

        @Test
        @DisplayName("a unique column asks for a unique index")
        void uniqueColumn() {
            List<IndexModel> indexes = EntityModel.of(Account.class).indexes();
            assertEquals(1, indexes.size());
            assertEquals("idx_accounts_name", indexes.get(0).name());
            assertTrue(indexes.get(0).unique());
        }

        @Table("plain")
        record Plain(@Id String id, @Column int value) {
        }

        @Test
        @DisplayName("a model asking for nothing gets nothing")
        void noIndexes() {
            assertEquals(List.of(), EntityModel.of(Plain.class).indexes());
        }

        /** The real leaderboard shape: twelve of these on one live table. */
        @Table("practice_player_stats")
        @Index(columns = {"kit_id", "elo"}, descending = {"elo"})
        @Index(columns = {"kit_id", "wins"}, descending = {"wins"})
        record KitStats(
                @Id String id,
                @Column("kit_id") String kitId,
                @Column int elo,
                @Column int wins) {
        }

        @Test
        @DisplayName("a composite index becomes an ordered compound key with per-field direction")
        void compoundKey() {
            List<IndexModel> indexes = EntityModel.of(KitStats.class).indexes();
            assertEquals(2, indexes.size());

            Map<String, Integer> key =
                    MongoDocuments.keySpec(EntityModel.of(KitStats.class), indexes.get(0));
            // Ordered, and asserted as a list of entries rather than as a Map:
            // {kit_id: 1, elo: -1} answers "the top of this kit by elo" and
            // {elo: -1, kit_id: 1} does not, and a Map comparison cannot tell
            // the two apart.
            assertEquals(List.of("kit_id", "elo"), List.copyOf(key.keySet()));
            assertEquals(List.of(1, -1), List.copyOf(key.values()));
        }

        @Test
        @DisplayName("the compound key order follows the annotation, not the record")
        void keyOrderIsTheAnnotationsOrder() {
            // The record declares elo before wins; the second index names wins
            // second on purpose. Reading the order off the record instead would
            // build an index nobody asked for and no query would use.
            EntityModel<KitStats> stats = EntityModel.of(KitStats.class);
            Map<String, Integer> key = MongoDocuments.keySpec(stats, stats.indexes().get(1));
            assertEquals(List.of("kit_id", "wins"), List.copyOf(key.keySet()));
            assertEquals(List.of(1, -1), List.copyOf(key.values()));
        }

        @Test
        @DisplayName("a component name in @Index resolves to the column's field")
        void componentNamesResolve() {
            @Table("resolved")
            record Resolved(
                    @Id String id,
                    @Column("kit_id") String kitId,
                    @Column int elo) {
            }
            // Written as the record reads it: kitId, not kit_id. The document
            // field has to be the column's, or the index covers a field no
            // query ever mentions.
            assertEquals(List.of("kit_id", "elo"),
                    List.copyOf(MongoDocuments.keySpec(EntityModel.of(Resolved.class),
                            new IndexModel("idx",
                                    List.of(IndexModel.Part.asc("kitId"),
                                            IndexModel.Part.asc("elo")),
                                    false)).keySet()));
        }

        @Test
        @DisplayName("a name too long for SQL is still truncated, so both backends agree")
        void namesMatchTheSqlSide() {
            // Deliberately unlike the previous behaviour, which left Mongo names
            // untruncated. Mongo has no length limit, but a SchemaReport that
            // named one index on Postgres and a different one on Mongo would
            // make the two impossible to compare, and telling an operator to
            // look for an index under a name that only exists on one engine is
            // worse than a shorter name.
            String name = IndexModel.derivedName(
                    "a_very_long_collection_name_that_would_never_fit_in_an_sql_identifier",
                    List.of(IndexModel.Part.asc("a_very_long_column_name_as_well")));
            assertTrue(name.length() <= 60, name);
            assertTrue(name.startsWith("idx_"));
        }
    }

    // ------------------------------------------------------------- validate

    @Nested
    @DisplayName("what Mongo cannot store as written")
    class Validation {

        @Test
        @DisplayName("a model with ordinary names has nothing wrong with it")
        void cleanModel() {
            assertEquals(List.of(), MongoDocuments.validate(model));
        }

        @Table("dotted")
        record Dotted(@Id String id, @Column("a.b") String nested) {
        }

        @Test
        @DisplayName("a dot in a column name is reported")
        void dottedName() {
            // Accepted by the server since 3.6 and still a trap: every query,
            // sort and index spec addresses fields by dotted path, so a field
            // named a.b is unreachable by the only syntax for reaching it.
            List<String> problems = MongoDocuments.validate(EntityModel.of(Dotted.class));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("a.b"));
        }

        @Table("shadowed")
        record Shadowed(@Id String id, @Column("_id") String other) {
        }

        @Test
        @DisplayName("a non-key column called _id is reported")
        void shadowedId() {
            // The two would overwrite each other on every write.
            List<String> problems = MongoDocuments.validate(EntityModel.of(Shadowed.class));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("_id"));
        }

        @Table("operators")
        record Operators(@Id String id, @Column("$gt") String bad) {
        }

        @Test
        @DisplayName("a leading $ in a column name is reported")
        void operatorName() {
            List<String> problems = MongoDocuments.validate(EntityModel.of(Operators.class));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).contains("$"));
        }
    }

    @Test
    @DisplayName("columnOf finds a column by either of its names")
    void columnLookup() {
        ColumnModel byColumn = MongoDocuments.columnOf(model, "kill_streak");
        ColumnModel byComponent = MongoDocuments.columnOf(model, "killStreak");
        assertSame(byColumn, byComponent);
    }
}
