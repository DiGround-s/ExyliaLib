package net.exylia.lib.database.internal;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.DisplayName;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last conversion before the driver, without a server.
 *
 * <p>Everything that could be decided without Mongo lives in
 * {@link MongoDocuments} and is tested next door. What is left here is the type
 * change into {@code org.bson}, which is short and which nothing else can
 * check: {@link BigDecimal} becoming {@link Decimal128} and back is the
 * difference between a balance of {@code 0.1} and one of
 * {@code 0.09999999999999999}, and a {@code Decimal128} handed to
 * {@link EntityModel} unconverted is a value the record never sees correctly.
 *
 * <p>In {@code internal} on purpose: these seams are package-private because
 * nothing in production should call them, and a test in the public package
 * could not reach them without widening the class's surface for no reason.
 *
 * <p>No test here opens a client. There is no {@code mongod} on a build
 * machine, and a test that needs one is a test that does not run.
 */
class MongoBackendTest {

    enum Rank { DEFAULT, VIP, MVP }

    @Table("player_stats")
    record Stats(
            @Id UUID uuid,
            @Column int elo,
            @Column("kill_streak") int killStreak,
            @Column long playtime,
            @Column double ratio,
            @Column float accuracy,
            @Column boolean banned,
            @Indexed @Column(length = 32) String clan,
            @Column Rank rank,
            @Column BigDecimal balance,
            @Column List<String> tags) {
    }

    private static final UUID KEY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final EntityModel<Stats> model = EntityModel.of(Stats.class);

    private static Stats stats() {
        return new Stats(KEY, 1840, 7, 900L, 1.75d, 0.25f, false, "Nova", Rank.VIP,
                new BigDecimal("12.34"), List.of("a", "b"));
    }

    // ------------------------------------------------------------- documents

    @Test
    @DisplayName("a record becomes a BSON document with its types intact")
    void documentKeepsNativeTypes() {
        Document document = MongoBackend.document(MongoDocuments.toDocument(model, stats()));

        // Read back through the Document API, which is what the server sees.
        assertEquals(KEY.toString(), document.getString("_id"));
        assertEquals(1840, document.getInteger("elo"));
        assertEquals(7, document.getInteger("kill_streak"));
        assertEquals(900L, document.getLong("playtime"));
        assertEquals(1.75d, document.getDouble("ratio"));
        assertEquals(Boolean.FALSE, document.getBoolean("banned"));
        assertEquals("Nova", document.getString("clan"));
        assertEquals("VIP", document.getString("rank"));
    }

    @Test
    @DisplayName("a float is a BSON double, since BSON has no 32-bit float")
    void floatIsADouble() {
        Document document = MongoBackend.document(MongoDocuments.toDocument(model, stats()));
        assertInstanceOf(Double.class, document.get("accuracy"));
        assertEquals(0.25d, document.getDouble("accuracy"));
    }

    @Test
    @DisplayName("a BigDecimal becomes a Decimal128 and not a double")
    void moneyIsADecimal128() {
        // The only reason a column is a BigDecimal. Through a binary double,
        // this is how 0.1 becomes 0.09999999999999999 in somebody's balance.
        Document document = MongoBackend.document(
                Map.of("balance", new BigDecimal("0.1")));
        Object stored = document.get("balance");
        assertInstanceOf(Decimal128.class, stored);
        assertEquals(new BigDecimal("0.1"), ((Decimal128) stored).bigDecimalValue());
    }

    @Test
    @DisplayName("a Decimal128 read back is a BigDecimal the record can hold")
    void decimal128IsUnwrappedOnRead() {
        // Unconverted, EntityModel would hand a Decimal128 to a BigDecimal
        // component and the record's constructor would throw from inside a
        // MethodHandle, naming nothing.
        Document document = new Document()
                .append("_id", KEY.toString())
                .append("balance", new Decimal128(new BigDecimal("12.34")));

        Stats back = MongoBackend.read(model, document);
        assertEquals(new BigDecimal("12.34"), back.balance());
    }

    @Test
    @DisplayName("a document round-trips a whole record")
    void roundTrip() {
        Stats original = stats();
        Document document = MongoBackend.document(MongoDocuments.toDocument(model, original));
        assertEquals(original, MongoBackend.read(model, document));
    }

    @Test
    @DisplayName("reading maps _id back to the key column")
    void readMapsUnderscoreId() {
        Document document = MongoBackend.document(MongoDocuments.toDocument(model, stats()));
        assertFalse(document.containsKey("uuid"));
        assertEquals(KEY, MongoBackend.read(model, document).uuid());
    }

    @Test
    @DisplayName("a nested filter document is converted, not left as a raw map")
    void nestedDocumentsAreConverted() {
        // An $and filter carries its clauses in a list of maps. Left raw, the
        // driver's default codec registry has no encoder for a LinkedHashMap
        // and the query throws at the wire.
        Map<String, Object> filter = MongoDocuments.filter(model,
                List.of("clan", "clan"), List.of("Nova", "Orion"));
        Document converted = MongoBackend.document(filter);

        Object clauses = converted.get("$and");
        assertInstanceOf(List.class, clauses);
        for (Object clause : (List<?>) clauses) {
            assertInstanceOf(Document.class, clause);
        }
        assertEquals("Nova", ((Document) ((List<?>) clauses).get(0)).getString("clan"));
    }

    @Test
    @DisplayName("a document omits the columns that encoded to nothing")
    void nullsAreOmitted() {
        Stats sparse = new Stats(KEY, 0, 0, 0L, 0d, 0f, false, null, Rank.DEFAULT, null, List.of());
        Document document = MongoBackend.document(MongoDocuments.toDocument(model, sparse));

        assertFalse(document.containsKey("clan"));
        assertFalse(document.containsKey("balance"));
        assertNull(MongoBackend.read(model, document).clan());
    }

    @Test
    @DisplayName("a document written without a field the record has still reads")
    void olderDocumentStillReads() {
        Map<String, Object> older = new LinkedHashMap<>();
        older.put("_id", KEY.toString());
        older.put("elo", 1500);

        Stats back = MongoBackend.read(model, new Document(older));
        assertEquals(KEY, back.uuid());
        assertEquals(1500, back.elo());
        assertEquals(0L, back.playtime());
    }

    // --------------------------------------------------------------- redact

    @Test
    @DisplayName("a connection string loses its credentials before it can be logged")
    void credentialsAreRedacted() {
        // This string ends up in toString, which ends up in a debug line on
        // enable, which ends up in whatever pastebin the next support ticket
        // links to.
        String redacted = MongoBackend.redact("mongodb+srv://user:hunter2@cluster0.example.net/practice");
        assertFalse(redacted.contains("hunter2"));
        assertFalse(redacted.contains("user"));
        assertTrue(redacted.startsWith("mongodb+srv://***@cluster0.example.net"));
    }

    @Test
    @DisplayName("a connection string without credentials is left alone")
    void uncredentialedUriIsUntouched() {
        String uri = "mongodb://10.0.0.5:27017/practice";
        assertEquals(uri, MongoBackend.redact(uri));
    }

    @Test
    @DisplayName("settings naming no database are refused before a client is opened")
    void namelessDatabaseIsRefused() {
        // Mongo has no default database, so a client without one can only be
        // asked about the server. Refusing here rather than on the first
        // operation means an operator finds out at startup, while they are
        // still looking at the console.
        //
        // This opens nothing: the check runs before MongoClients.create, so no
        // server is needed and none is contacted.
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> MongoBackend.open(SqlSettings.remote("mongodb", "127.0.0.1", 27017,
                        "  ", "user", "secret"), "tests"));
        assertTrue(refused.getMessage().contains("database"));
    }
}
