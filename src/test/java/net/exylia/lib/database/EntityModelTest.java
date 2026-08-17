package net.exylia.lib.database;

import net.exylia.lib.database.internal.ColumnModel;
import net.exylia.lib.database.internal.EntityModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the compiled mapper.
 *
 * <p>No server: every type exercised here is one the mapper handles without
 * Bukkit, which is most of them. The item and location formats are covered by
 * the codec tests, where a server is unavoidable.
 */
class EntityModelTest {

    enum Rank { DEFAULT, VIP }

    @Table("stats")
    record Stats(
            @Id UUID uuid,
            @Column int elo,
            @Column("kill_streak") int killStreak,
            @Column long playtime,
            @Column double ratio,
            @Column float accuracy,
            @Column boolean banned,
            @Indexed @Column Rank rank,
            @Column BigDecimal balance,
            @Column(nullable = false) String name,
            @Column List<String> tags,
            @Column List<UUID> friends,
            @Column List<Integer> scores,
            String derived) {
    }

    private static Stats sample() {
        return new Stats(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1200, 3, 900L, 1.5d, 0.25f, true, Rank.VIP, new BigDecimal("12.34"),
                "Steve", List.of("a", "b"),
                List.of(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                List.of(1, 2, 3), "not stored");
    }

    @Test
    @DisplayName("an unannotated component is skipped, not a column")
    void skipsUnannotated() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        assertEquals("stats", model.table());
        assertEquals(13, model.columns().size());
        assertNull(model.byComponent("derived"));
    }

    @Test
    @DisplayName("@Column(value) renames, @Id is the key and is unique and not nullable")
    void metadata() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        assertEquals("kill_streak", model.byComponent("killStreak").name());
        assertEquals("uuid", model.id().name());
        assertTrue(model.id().unique());
        assertTrue(model.column("uuid").id());
        assertEquals(false, model.column("uuid").nullable());
        assertEquals(false, model.column("name").nullable());
        assertTrue(model.column("rank").indexed());
        assertEquals(false, model.column("elo").indexed());
    }

    @Test
    @DisplayName("a codec column is stored as text, a direct one keeps its type")
    void storedTypes() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        assertEquals(int.class, model.column("elo").storedType());
        assertEquals(BigDecimal.class, model.column("balance").storedType());
        assertEquals(String.class, model.column("uuid").storedType());
        assertEquals(String.class, model.column("rank").storedType());
        assertEquals(String.class, model.column("tags").storedType());
    }

    @Test
    @DisplayName("a record round-trips through an ordered array")
    void roundTripByPosition() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        Stats original = sample();
        Stats read = model.read(model.values(original));

        assertEquals(original.uuid(), read.uuid());
        assertEquals(original.elo(), read.elo());
        assertEquals(original.killStreak(), read.killStreak());
        assertEquals(original.playtime(), read.playtime());
        assertEquals(original.ratio(), read.ratio());
        assertEquals(original.accuracy(), read.accuracy());
        assertEquals(original.banned(), read.banned());
        assertEquals(Rank.VIP, read.rank());
        assertEquals(new BigDecimal("12.34"), read.balance());
        assertEquals("Steve", read.name());
        assertEquals(List.of("a", "b"), read.tags());
        assertEquals(original.friends(), read.friends());
        assertEquals(List.of(1, 2, 3), read.scores());
        assertNull(read.derived());
    }

    @Test
    @DisplayName("a record round-trips through a row addressed by column name")
    void roundTripByName() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        Map<String, Object> row = new HashMap<>(model.valuesByName(sample()));
        Stats read = model.read(row::get);
        assertEquals(3, read.killStreak());
        assertEquals(Rank.VIP, read.rank());
        assertEquals("Steve", read.name());
    }

    @Test
    @DisplayName("enums are stored by name, so reordering constants cannot reinterpret a row")
    void enumsByName() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        assertEquals("VIP", model.column("rank").encode(Rank.VIP));
        assertEquals(Rank.DEFAULT, model.column("rank").decode("DEFAULT"));
    }

    @Test
    @DisplayName("a list of codec elements is a JSON array of encoded strings, as Commons wrote it")
    void commonsListWireFormat() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        UUID one = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertEquals("[\"" + one + "\"]", model.column("friends").encode(List.of(one)));
        // Strings and numbers are what Gson makes of the collection directly,
        // which is the branch Commons fell through to.
        assertEquals("[\"a\",\"b\"]", model.column("tags").encode(List.of("a", "b")));
        assertEquals("[1,2]", model.column("scores").encode(List.of(1, 2)));
    }

    @Test
    @DisplayName("an absent or unreadable list reads as empty, never null")
    void listsNeverNull() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        assertEquals(List.of(), model.column("tags").decode(null));
        assertEquals(List.of(), model.column("tags").decode(""));
        assertEquals(List.of(), model.column("friends").decode("not json"));
        // A stale UUID inside a good array drops that element, not the column.
        assertEquals(List.of(), model.column("friends").decode("[\"nonsense\"]"));
    }

    @Test
    @DisplayName("a driver's number type does not have to match the declared one")
    void numberWidening() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        assertEquals(900L, model.column("playtime").decode(900));                  // Integer -> long
        assertEquals(1200, model.column("elo").decode(1200L));                     // Long -> int
        assertEquals(1.5d, model.column("ratio").decode(new BigDecimal("1.5")));   // BigDecimal -> double
        assertEquals(0.25f, model.column("accuracy").decode(0.25d));               // Double -> float
        assertEquals(new BigDecimal("12.34"), model.column("balance").decode("12.34"));
        assertEquals(42, model.column("elo").decode("42.0"));                      // text from a widened column
    }

    @Test
    @DisplayName("a boolean survives an engine that has no boolean type")
    void booleanShapes() {
        ColumnModel banned = EntityModel.of(Stats.class).column("banned");
        assertEquals(true, banned.decode(1));
        assertEquals(false, banned.decode(0));
        assertEquals(true, banned.decode("true"));
        assertEquals(false, banned.decode("false"));
    }

    @Test
    @DisplayName("a null in a primitive column is the type's zero, not an NPE")
    void nullsInPrimitives() {
        EntityModel<Stats> model = EntityModel.of(Stats.class);
        assertEquals(0, model.column("elo").decode(null));
        assertEquals(0L, model.column("playtime").decode(null));
        assertEquals(false, model.column("banned").decode(null));
        assertNull(model.column("name").decode(null));

        // A whole row of nulls is what a table looks like after a column is
        // added, and it must still build a record.
        Stats read = model.read(column -> null);
        assertEquals(0, read.elo());
        assertNull(read.uuid());
        assertEquals(List.of(), read.tags());
    }

    // ------------------------------------------------------------ registration

    record NotARecordAtAll() { }

    @Test
    void rejectsNonRecord() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(String.class)).getMessage().contains("not a record"));
    }

    record NoTable(@Id String id) { }

    @Test
    void rejectsMissingTable() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(NoTable.class)).getMessage().contains("@Table"));
    }

    @Table("t")
    record NoId(@Column String name) { }

    @Test
    void rejectsMissingId() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(NoId.class)).getMessage().contains("no @Id"));
    }

    @Table("t")
    record TwoIds(@Id String a, @Id String b) { }

    @Test
    void rejectsTwoIds() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(TwoIds.class)).getMessage().contains("more than one @Id"));
    }

    @Table("t")
    record Duplicated(@Id String id, @Column("id") String other) { }

    @Test
    void rejectsDuplicateColumnName() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(Duplicated.class)).getMessage().contains("already maps to"));
    }

    @Table("t")
    record LengthOnNumber(@Id String id, @Column(length = 10) int elo) { }

    @Test
    void rejectsLengthOnNonString() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(LengthOnNumber.class)).getMessage().contains("length"));
    }

    @Table("t")
    record UnboundedKey(@Id(length = Column.UNBOUNDED) String id) { }

    @Test
    void rejectsUnboundedKey() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(UnboundedKey.class)).getMessage().contains("unbounded key"));
    }

    @Table("t")
    record Unsupported(@Id String id, @Column Thread thread) { }

    @Test
    void rejectsTypeWithNoCodec() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(Unsupported.class)).getMessage().contains("nothing knows how to store"));
    }

    @Table("t")
    record RawList(@Id String id, @Column List<Thread> threads) { }

    @Test
    void rejectsListOfUnsupportedElement() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(RawList.class)).getMessage().contains("List of"));
    }

    @Table("t")
    record BothAnnotations(@Id @Column String id) { }

    @Test
    void rejectsIdAndColumnTogether() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> EntityModel.of(BothAnnotations.class)).getMessage().contains("both @Id and @Column"));
    }

    @Test
    @DisplayName("a class compiles once and is handed back from the cache after")
    void compiledOnce() {
        assertTrue(EntityModel.of(Stats.class) == EntityModel.of(Stats.class));
    }
}
