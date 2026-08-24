package net.exylia.lib.util.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loot tables written by ExyliaCommons, read unchanged.
 *
 * <p>Every JSON string here is the shape a bare {@code new Gson().toJson(...)}
 * over the old Lombok bean produces, because that is what is sitting in
 * {@code sc_loot_chest_templates}, the spawner tables and the event configs on
 * live servers. Reading it is the feature; a test written against a shape we
 * invented would prove nothing.
 */
class LootCodecTest {

    private final List<String> problems = new ArrayList<>();

    private List<LootEntry> decode(String json) {
        return LootCodec.decode(json, (where, problem) -> problems.add(where + ": " + problem));
    }

    // ------------------------------------------------------------------
    // Reading what is already stored
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an item entry stored by commons reads back whole")
    void legacyItem() {
        List<LootEntry> entries = decode("""
                [{"id":"2f1c8f0e-1c3a-4b6d-9a12-1f2e3d4c5b6a","type":"ITEM",\
                "itemSnapshot":"bytes:rO0ABXNy","minAmount":1,"maxAmount":3,\
                "weight":25.5,"tier":"RARE"}]""");

        assertEquals(1, entries.size());
        LootEntry entry = entries.get(0);
        assertEquals("2f1c8f0e-1c3a-4b6d-9a12-1f2e3d4c5b6a", entry.id());
        assertEquals(LootType.ITEM, entry.type());
        assertEquals("bytes:rO0ABXNy", entry.itemSnapshot());
        assertEquals(1, entry.minAmount());
        assertEquals(3, entry.maxAmount());
        assertTrue(entry.isRanged());
        assertEquals(25.5, entry.weight());
        assertEquals("RARE", entry.tier());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("an entry from before types existed is an item, not a loss")
    void missingTypeIsItem() {
        List<LootEntry> entries = decode("""
                [{"id":"a","itemSnapshot":"BREAD","minAmount":1,"maxAmount":1,"weight":50.0}]""");

        assertEquals(1, entries.size());
        assertEquals(LootType.ITEM, entries.get(0).type());
        assertTrue(entries.get(0).isItem());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("a type this version never heard of costs the payload, not the table")
    void unknownTypeIsReported() {
        List<LootEntry> entries = decode("""
                [{"id":"a","type":"HOLOGRAM","minAmount":1,"maxAmount":1,"weight":50.0},\
                {"id":"b","type":"ITEM","itemSnapshot":"BREAD","minAmount":1,"maxAmount":1,"weight":50.0}]""");

        assertEquals(2, entries.size());
        assertEquals(LootType.ITEM, entries.get(0).type());
        assertEquals("b", entries.get(1).id());
        assertEquals(1, problems.stream().filter(problem -> problem.contains("HOLOGRAM")).count(),
                problems::toString);
    }

    @Test
    @DisplayName("a command entry keeps its command and carries no item")
    void legacyCommand() {
        List<LootEntry> entries = decode("""
                [{"id":"a","type":"COMMAND","minAmount":1,"maxAmount":1,\
                "command":"eco give %player_name% 500","weight":10.0}]""");

        LootEntry entry = entries.get(0);
        assertTrue(entry.isCommand());
        assertEquals("eco give %player_name% 500", entry.command());
        assertNull(entry.itemSnapshot());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("an entry missing its payload is kept and reported, so an editor can fix it")
    void halfConfiguredIsKept() {
        List<LootEntry> entries = decode("""
                [{"id":"a","type":"ITEM","minAmount":1,"maxAmount":1,"weight":50.0}]""");

        assertEquals(1, entries.size());
        assertEquals(1, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("a missing amount reads as one, and a missing weight as commons' default")
    void defaults() {
        List<LootEntry> entries = decode("""
                [{"id":"a","type":"ITEM","itemSnapshot":"BREAD"}]""");

        LootEntry entry = entries.get(0);
        assertEquals(1, entry.minAmount());
        assertEquals(1, entry.maxAmount());
        assertFalse(entry.isRanged());
        assertEquals(50.0, entry.weight());
    }

    @Test
    @DisplayName("a row that is not JSON costs the table, not an exception")
    void malformed() {
        assertTrue(decode("{not json").isEmpty());
        assertEquals(1, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("an empty or absent column reads as no table at all")
    void empty() {
        assertTrue(decode(null).isEmpty());
        assertTrue(decode("").isEmpty());
        assertTrue(decode("   ").isEmpty());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    // ------------------------------------------------------------------
    // Writing what the old module would have written
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an item entry serialises byte for byte to what commons wrote")
    void writesLegacyItem() {
        LootEntry entry = LootEntry.item("bytes:rO0ABXNy")
                .id("2f1c8f0e-1c3a-4b6d-9a12-1f2e3d4c5b6a")
                .amountBetween(1, 3)
                .weight(25.5)
                .tier("RARE")
                .build();

        assertEquals("""
                        {"id":"2f1c8f0e-1c3a-4b6d-9a12-1f2e3d4c5b6a","type":"ITEM",\
                        "itemSnapshot":"bytes:rO0ABXNy","minAmount":1,"maxAmount":3,\
                        "weight":25.5,"tier":"RARE"}""",
                LootCodec.encode(entry));
    }

    @Test
    @DisplayName("a null field is absent, not null, exactly as Gson left it")
    void omitsNulls() {
        String written = LootCodec.encode(LootEntry.item("BREAD").id("a").build());

        assertEquals("""
                {"id":"a","type":"ITEM","itemSnapshot":"BREAD","minAmount":1,\
                "maxAmount":1,"weight":50.0}""", written);
        assertFalse(written.contains("command"));
        assertFalse(written.contains("tier"));
    }

    @Test
    @DisplayName("a command entry carries no itemSnapshot key at all")
    void omitsItemOnCommand() {
        String written = LootCodec.encode(LootEntry.command("say hi").id("a").build());

        assertEquals("""
                {"id":"a","type":"COMMAND","minAmount":1,"maxAmount":1,\
                "command":"say hi","weight":50.0}""", written);
    }

    @Test
    @DisplayName("an empty table stores as NULL, not as []")
    void emptyIsNull() {
        assertNull(LootCodec.encode(List.of()));
    }

    @Test
    @DisplayName("a stored table survives a round trip unchanged, id included")
    void roundTrip() {
        List<LootEntry> written = List.of(
                LootEntry.item("bytes:rO0ABXNy").id("a").amountBetween(2, 5).weight(12.5).tier("EPIC").build(),
                LootEntry.command("eco give %player_name% 10").id("b").weight(1.0).build());

        String stored = LootCodec.encode(written);
        List<LootEntry> read = decode(stored);

        assertEquals(stored, LootCodec.encode(read));
        assertEquals(written, read);
        assertTrue(problems.isEmpty(), problems::toString);
    }
}
