package net.exylia.lib.util.reward;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewards written by ExyliaCommons, read unchanged.
 *
 * <p>Every JSON string here is the shape a bare {@code new Gson().toJson(...)}
 * over the old Lombok bean produces, because that is what is sitting in
 * {@code capture_pending_rewards} and {@code event_pending_rewards} on live
 * servers. Reading it is the feature; a test that used a shape we invented would
 * prove nothing.
 */
class RewardCodecTest {

    private final List<String> problems = new ArrayList<>();

    private List<RewardEntry> decode(String json) {
        return RewardCodec.decode(json, (where, problem) -> problems.add(where + ": " + problem));
    }

    // ------------------------------------------------------------------
    // Reading what is already stored
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a command reward stored by commons reads back whole")
    void legacyCommand() {
        List<RewardEntry> rewards = decode("""
                [{"id":"2f1c8f0e-1c3a-4b6d-9a12-1f2e3d4c5b6a","name":"Winner payout",\
                "type":"COMMAND","command":"eco give %player_name% 500",\
                "itemAmount":1,"chance":100.0,"priority":0}]""");

        assertEquals(1, rewards.size());
        RewardEntry reward = rewards.get(0);
        assertEquals("2f1c8f0e-1c3a-4b6d-9a12-1f2e3d4c5b6a", reward.id());
        assertEquals("Winner payout", reward.name());
        assertEquals(RewardType.COMMAND, reward.type());
        assertEquals("eco give %player_name% 500", reward.command());
        assertEquals(100.0, reward.chance());
        assertTrue(reward.isGuaranteed());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("an item reward keeps its snapshot, amount and odds")
    void legacyItem() {
        List<RewardEntry> rewards = decode("""
                [{"id":"a1","type":"ITEM","itemSnapshot":"bytes:H4sIAAAA",\
                "itemAmount":16,"chance":25.5,"permission":"event.vip",\
                "condition":"%player_level% >= 10","deliveryMessage":"{success}You won!",\
                "priority":5}]""");

        RewardEntry reward = rewards.get(0);
        assertEquals(RewardType.ITEM, reward.type());
        assertEquals("bytes:H4sIAAAA", reward.itemSnapshot());
        assertEquals(16, reward.itemAmount());
        assertEquals(25.5, reward.chance());
        assertEquals("event.vip", reward.permission());
        assertEquals("%player_level% >= 10", reward.condition());
        assertEquals("{success}You won!", reward.deliveryMessage());
        assertEquals(5, reward.priority());
        assertFalse(reward.isGuaranteed());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("fields commons omitted come back as their defaults")
    void legacyDefaults() {
        List<RewardEntry> rewards = decode("""
                [{"id":"a1","type":"MESSAGE","message":"{primary}Well played"}]""");

        RewardEntry reward = rewards.get(0);
        assertEquals("{primary}Well played", reward.message());
        assertEquals(1, reward.itemAmount());
        assertEquals(100.0, reward.chance());
        assertEquals(0, reward.priority());
        assertEquals(1.0, reward.weight());
        assertNull(reward.command());
        assertNull(reward.itemSnapshot());
        assertFalse(reward.isRanged());
    }

    @Test
    @DisplayName("a whole stored list keeps its order")
    void legacyList() {
        List<RewardEntry> rewards = decode("""
                [{"id":"a","type":"COMMAND","command":"one"},\
                {"id":"b","type":"COMMAND","command":"two"},\
                {"id":"c","type":"COMMAND","command":"three"}]""");

        assertEquals(List.of("one", "two", "three"),
                rewards.stream().map(RewardEntry::command).toList());
    }

    // ------------------------------------------------------------------
    // Writing what the old module has to keep reading
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a legacy-shaped reward writes exactly the keys commons wrote")
    void writesLegacyKeys() {
        RewardEntry reward = RewardEntry.command("eco give %player_name% 500")
                .id("a1")
                .name("Winner payout")
                .build();

        JsonObject json = JsonParser.parseString(RewardCodec.encode(reward)).getAsJsonObject();

        assertEquals(
                List.of("id", "name", "type", "command", "itemAmount", "chance", "priority"),
                List.copyOf(json.keySet()),
                "a reward the old module could have written must serialise to what it wrote");
    }

    @Test
    @DisplayName("nothing this library added appears on a reward that does not use it")
    void newFieldsStayOut() {
        String json = RewardCodec.encode(RewardEntry.item("DIAMOND").itemAmount(4).build());

        assertFalse(json.contains("value"), json);
        assertFalse(json.contains("currency"), json);
        assertFalse(json.contains("minAmount"), json);
        assertFalse(json.contains("maxAmount"), json);
        assertFalse(json.contains("weight"), json);
    }

    @Test
    @DisplayName("a null field is omitted, not written as null")
    void omitsNulls() {
        String json = RewardCodec.encode(RewardEntry.message("hi").id("a1").build());

        assertFalse(json.contains("null"), json);
        assertFalse(json.contains("itemSnapshot"), json);
        assertFalse(json.contains("permission"), json);
    }

    @Test
    @DisplayName("an empty list stores as null, the way commons stored it")
    void emptyIsNull() {
        assertNull(RewardCodec.encode(List.of()));
    }

    @Test
    @DisplayName("a stored list survives a round trip byte for byte")
    void roundTrip() {
        String stored = """
                [{"id":"a1","name":"Winner","type":"ITEM","itemSnapshot":"DIAMOND",\
                "icon":"CHEST","itemAmount":16,"chance":25.5,"condition":"%level% >= 10",\
                "permission":"event.vip","deliveryMessage":"{success}Won","priority":5}]""";

        assertEquals(stored, RewardCodec.encode(decode(stored)));
    }

    // ------------------------------------------------------------------
    // The new fields
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reward that uses a new field writes it")
    void writesNewFields() {
        RewardEntry reward = RewardEntry.economy("2500.50")
                .id("a1")
                .currency("gems")
                .weight(7.5)
                .build();

        JsonObject json = JsonParser.parseString(RewardCodec.encode(reward)).getAsJsonObject();

        assertEquals("ECONOMY", json.get("type").getAsString());
        assertEquals("2500.50", json.get("value").getAsString());
        assertEquals("gems", json.get("currency").getAsString());
        assertEquals(7.5, json.get("weight").getAsDouble());
    }

    @Test
    @DisplayName("an amount range round-trips")
    void amountRange() {
        RewardEntry written = RewardEntry.item("DIAMOND").id("a1").amountBetween(4, 12).build();

        RewardEntry read = decode("[" + RewardCodec.encode(written) + "]").get(0);

        assertTrue(read.isRanged());
        assertEquals(4, read.minAmount());
        assertEquals(12, read.maxAmount());
    }

    @Test
    @DisplayName("a range written backwards is put in order rather than refused")
    void backwardsRange() {
        RewardEntry reward = RewardEntry.item("DIAMOND").amountBetween(12, 4).build();

        assertEquals(4, reward.minAmount());
        assertEquals(12, reward.maxAmount());
    }

    @Test
    @DisplayName("half a range is reported and the fixed amount still stands")
    void halfARange() {
        List<RewardEntry> rewards = decode("""
                [{"id":"a1","type":"ITEM","itemSnapshot":"DIAMOND","itemAmount":3,"minAmount":4}]""");

        assertEquals(1, rewards.size(), "the reward is still given");
        assertFalse(rewards.get(0).isRanged());
        assertEquals(3, rewards.get(0).itemAmount());
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("one end"), problems::toString);
    }

    // ------------------------------------------------------------------
    // What a plugin on the old module sees
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown type costs one reward, not the list")
    void unknownTypeSkipsOne() {
        List<RewardEntry> rewards = decode("""
                [{"id":"a","type":"COMMAND","command":"one"},\
                {"id":"b","type":"TELEPORT","value":"spawn"},\
                {"id":"c","type":"COMMAND","command":"three"}]""");

        assertEquals(2, rewards.size());
        assertEquals(List.of("one", "three"),
                rewards.stream().map(RewardEntry::command).toList());
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("TELEPORT"), problems::toString);
    }

    @Test
    @DisplayName("only the three types commons knew are legacy")
    void legacyTypes() {
        assertTrue(RewardType.COMMAND.isLegacy());
        assertTrue(RewardType.ITEM.isLegacy());
        assertTrue(RewardType.MESSAGE.isLegacy());
        assertFalse(RewardType.ECONOMY.isLegacy());
        assertFalse(RewardType.EXPERIENCE.isLegacy());
        assertFalse(RewardType.POTION.isLegacy());
    }

    // ------------------------------------------------------------------
    // Rows nobody should be able to break
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a row with no type is reported and skipped")
    void missingType() {
        assertTrue(decode("""
                [{"id":"a","command":"one"}]""").isEmpty());
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("no type"), problems::toString);
    }

    @Test
    @DisplayName("a row with no id is given one rather than dropped")
    void missingId() {
        List<RewardEntry> rewards = decode("""
                [{"type":"COMMAND","command":"one"}]""");

        assertEquals(1, rewards.size());
        assertNotNull(rewards.get(0).id());
        assertFalse(rewards.get(0).id().isBlank());
    }

    @Test
    @DisplayName("a malformed column yields nothing and says so")
    void malformed() {
        assertTrue(decode("{not json at all").isEmpty());
        assertEquals(1, problems.size(), problems::toString);
    }

    @Test
    @DisplayName("an empty or absent column yields nothing quietly")
    void empty() {
        assertTrue(decode(null).isEmpty());
        assertTrue(decode("").isEmpty());
        assertTrue(decode("   ").isEmpty());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("a single object is read as a list of one")
    void singleObject() {
        List<RewardEntry> rewards = decode("""
                {"id":"a","type":"COMMAND","command":"one"}""");

        assertEquals(1, rewards.size());
        assertEquals("one", rewards.get(0).command());
    }

    @Test
    @DisplayName("a number stored as a string still reads")
    void looseNumbers() {
        List<RewardEntry> rewards = decode("""
                [{"id":"a","type":"ITEM","itemSnapshot":"DIAMOND",\
                "itemAmount":"16","chance":"25.5"}]""");

        assertEquals(16, rewards.get(0).itemAmount());
        assertEquals(25.5, rewards.get(0).chance());
    }

    @Test
    @DisplayName("a number that is not one falls back rather than throwing")
    void brokenNumbers() {
        List<RewardEntry> rewards = decode("""
                [{"id":"a","type":"ITEM","itemSnapshot":"DIAMOND",\
                "itemAmount":"lots","chance":"often"}]""");

        assertEquals(1, rewards.get(0).itemAmount());
        assertEquals(100.0, rewards.get(0).chance());
    }

    // ------------------------------------------------------------------
    // The legacy command column
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the pre-types commandsJson column still reads")
    void legacyCommandsColumn() {
        List<RewardEntry> rewards = RewardCodec.decodeLegacyCommands(
                """
                ["eco give %player_name% 500","broadcast %player_name% won"]""");

        assertEquals(2, rewards.size());
        assertTrue(rewards.stream().allMatch(reward -> reward.type() == RewardType.COMMAND));
        assertEquals("eco give %player_name% 500", rewards.get(0).command());
        assertEquals("broadcast %player_name% won", rewards.get(1).command());
    }

    @Test
    @DisplayName("an absent or broken commands column yields nothing")
    void legacyCommandsEmpty() {
        assertTrue(RewardCodec.decodeLegacyCommands(null).isEmpty());
        assertTrue(RewardCodec.decodeLegacyCommands("").isEmpty());
        assertTrue(RewardCodec.decodeLegacyCommands("not json").isEmpty());
        assertTrue(RewardCodec.decodeLegacyCommands("{}").isEmpty());
    }
}
