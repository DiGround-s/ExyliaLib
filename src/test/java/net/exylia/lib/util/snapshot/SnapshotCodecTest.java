package net.exylia.lib.util.snapshot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.FakeServer;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stored form of a snapshot, which is not ours to choose.
 *
 * <p>Rows written by ExyliaCommons are in production and hold everything the
 * players who were in an arena at the last restart own. So the contract that
 * matters most here is the one nobody can see by reading the code: the keys are
 * its keys, an empty slot is JSON {@code null}, and a row it wrote reads back
 * without losing anything it knew about.
 */
class SnapshotCodecTest {

    private List<String> problems;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        // A real ItemStack cannot be built without a server, so the item form is
        // replaced. Everything about the JSON around it is still real.
        SnapshotCodec.setItems(TestItem.IO);
        problems = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        SnapshotCodec.resetItems();
        FakeServer.reset();
    }

    /** A snapshot with something in every field, so nothing is proved by zero. */
    private static Snapshot full() {
        ItemStack[] inventory = new ItemStack[Snapshot.INVENTORY_SLOTS];
        inventory[0] = TestItem.of("DIAMOND_SWORD");
        inventory[8] = new TestItem("GOLDEN_APPLE", 16);
        ItemStack[] armor = new ItemStack[Snapshot.ARMOR_SLOTS];
        armor[3] = TestItem.of("DIAMOND_HELMET");
        ItemStack[] enderChest = new ItemStack[27];
        enderChest[2] = new TestItem("EMERALD", 12);

        return new Snapshot(GameMode.SURVIVAL, inventory, armor, TestItem.of("SHIELD"),
                enderChest, 18.5d, 24.0d, 17, 3.5f, 42, 0.75f,
                List.of(new Snapshot.Effect("SPEED", 600, 1, false, true, true),
                        new Snapshot.Effect("REGENERATION", 120, 0, true, false, false)),
                true, true, 0.15f,
                new Snapshot.Physical(40, 260, 0.5d, -0.1d, 0.25d, 0.21f, true, true,
                        3.25f, 90, 2, true, 7, 18),
                Map.of("minecraft:scale", 0.5d, "minecraft:max_health", 24.0d),
                4, TestItem.of("ENDER_PEARL"), 2.5f, 6.0d);
    }

    // ------------------------------------------------------------ round trip

    @Test
    @DisplayName("a snapshot encoded and read back is the same snapshot")
    void roundTrip() {
        Snapshot before = full();

        Snapshot after = SnapshotCodec.decode(SnapshotCodec.encode(before), problems::add);

        assertNotNull(after);
        assertEquals(before, after, "every field survives the round trip");
        assertTrue(problems.isEmpty(), "nothing had to be skipped: " + problems);
    }

    @Test
    @DisplayName("every field comes back with the value it went in with")
    void everyFieldSurvives() {
        Snapshot after = SnapshotCodec.decode(SnapshotCodec.encode(full()), problems::add);

        assertNotNull(after);
        assertEquals(GameMode.SURVIVAL, after.gameMode());
        assertEquals(TestItem.of("DIAMOND_SWORD"), after.inventory()[0]);
        assertEquals(new TestItem("GOLDEN_APPLE", 16), after.inventory()[8]);
        assertNull(after.inventory()[1], "an empty slot stays empty");
        assertEquals(TestItem.of("DIAMOND_HELMET"), after.armor()[3]);
        assertEquals(TestItem.of("SHIELD"), after.offHand());
        assertEquals(new TestItem("EMERALD", 12), after.enderChest()[2]);
        assertEquals(18.5d, after.health());
        assertEquals(24.0d, after.maxHealth());
        assertEquals(17, after.foodLevel());
        assertEquals(3.5f, after.saturation());
        assertEquals(42, after.level());
        assertEquals(0.75f, after.exp());
        assertEquals(2, after.potionEffects().size());
        assertEquals(new Snapshot.Effect("SPEED", 600, 1, false, true, true),
                after.potionEffects().get(0));
        assertTrue(after.allowFlight());
        assertTrue(after.flying());
        assertEquals(0.15f, after.flySpeed());
        assertNotNull(after.physical());
        assertEquals(40, after.physical().fireTicks());
        assertEquals(260, after.physical().remainingAir());
        assertEquals(0.5d, after.physical().velocityX());
        assertEquals(0.21f, after.physical().walkSpeed());
        assertTrue(after.physical().invulnerable());
        assertTrue(after.physical().glowing());
        assertNotNull(after.attributes());
        assertEquals(0.5d, after.attributes().get("minecraft:scale"),
                "the shrink a minigame applied is what has to come back off");
        assertEquals(2, after.attributes().size());
    }

    @Test
    @DisplayName("a player at every default stores an empty map, not no map")
    void everyDefaultIsStillCaptured() {
        // The difference decides a restore: an empty map says every attribute
        // goes back to its default, which is how a shrunk player is made normal
        // again. No map at all says nobody looked, and leaves them shrunk.
        Snapshot untouched = new Snapshot(GameMode.SURVIVAL, null, null, null, null,
                20.0d, 20.0d, 20, 5f, 0, 0f, List.of(), false, false, 0.1f, null, Map.of());

        Snapshot after = SnapshotCodec.decode(SnapshotCodec.encode(untouched), problems::add);

        assertNotNull(after);
        assertTrue(after.has(SnapshotPart.ATTRIBUTES));
        assertNotNull(after.attributes());
        assertTrue(after.attributes().isEmpty());
    }

    // ------------------------------------------------- the ExyliaCommons wire

    /**
     * A row exactly as ExyliaCommons wrote it.
     *
     * <p>Hand-written rather than generated, on purpose: generating it from this
     * library's own encoder would make the test agree with itself. The item
     * strings are in the test format so they can be read back here; everything
     * around them is the real shape, keys and all.
     */
    private static final String COMMONS_ROW = """
            {"gameMode":"ADVENTURE",\
            "armor":[null,null,null,"item:DIAMOND_HELMETx1"],\
            "inventory":["item:DIAMOND_SWORDx1",null,null],\
            "offHand":null,\
            "health":19.0,"maxHealth":20.0,\
            "foodLevel":18,"saturation":2.5,\
            "level":30,"exp":0.5,\
            "potionEffects":[{"type":"SPEED","duration":600,"amplifier":1,\
            "ambient":false,"particles":true,"icon":true}],\
            "allowFlight":false,"flying":false,"flySpeed":0.1}""";

    @Test
    @DisplayName("a row ExyliaCommons wrote reads back with everything it knew")
    void commonsRowReads() {
        Snapshot read = SnapshotCodec.decode(COMMONS_ROW, problems::add);

        assertNotNull(read, "a commons row is a snapshot, not a null");
        assertEquals(GameMode.ADVENTURE, read.gameMode());
        assertEquals(TestItem.of("DIAMOND_SWORD"), read.inventory()[0]);
        assertEquals(TestItem.of("DIAMOND_HELMET"), read.armor()[3]);
        assertNull(read.offHand(), "commons wrote an empty off hand as null");
        assertEquals(19.0d, read.health());
        assertEquals(20.0d, read.maxHealth());
        assertEquals(18, read.foodLevel());
        assertEquals(2.5f, read.saturation());
        assertEquals(30, read.level());
        assertEquals(0.5f, read.exp());
        assertEquals(List.of(new Snapshot.Effect("SPEED", 600, 1, false, true, true)),
                read.potionEffects());
        assertFalse(read.allowFlight());
        assertFalse(read.flying());
        assertEquals(0.1f, read.flySpeed());
        assertTrue(problems.isEmpty(), "nothing about a commons row is a problem: " + problems);
    }

    @Test
    @DisplayName("a row with none of the new keys decodes rather than coming back null")
    void commonsRowIsNotNull() {
        // The failure this guards against is silent and total: commons wrapped
        // its whole deserialiser in one catch that returned null, so anything
        // unexpected discarded the player's entire inventory.
        Snapshot read = SnapshotCodec.decode(COMMONS_ROW, problems::add);

        assertNotNull(read);
        assertFalse(read.has(SnapshotPart.ENDER_CHEST),
                "commons never wrote one, so there is nothing to put back");
        assertFalse(read.has(SnapshotPart.PHYSICAL), "the same for the physical state");
        assertFalse(read.has(SnapshotPart.ATTRIBUTES), "the same for the attributes");
        assertNull(read.enderChest());
        assertNull(read.physical(),
                "absent, not zeroed: a zeroed physical state sets walk speed to zero");
        assertTrue(read.has(SnapshotPart.INVENTORY), "what it did write is all there");
    }

    @Test
    @DisplayName("what this library writes carries every key ExyliaCommons wrote")
    void writesEveryCommonsKey() {
        // The other direction: a server still running commons must be able to
        // read a row written here, and its deserialiser reads by key.
        JsonObject written = JsonParser.parseString(SnapshotCodec.encode(full())).getAsJsonObject();

        for (String key : List.of("gameMode", "armor", "inventory", "offHand", "health",
                "maxHealth", "foodLevel", "saturation", "level", "exp", "potionEffects",
                "allowFlight", "flying", "flySpeed")) {
            assertTrue(written.has(key), "commons reads \"" + key + "\" and it must be there");
        }
        JsonObject effect = written.getAsJsonArray("potionEffects").get(0).getAsJsonObject();
        for (String key : List.of("type", "duration", "amplifier", "ambient", "particles", "icon")) {
            assertTrue(effect.has(key), "commons reads effect.\"" + key + "\"");
        }
    }

    @Test
    @DisplayName("an empty slot is written as null, the way a commons row holds it")
    void emptySlotsAreNull() {
        JsonObject written = JsonParser.parseString(SnapshotCodec.encode(full())).getAsJsonObject();

        assertTrue(written.getAsJsonArray("inventory").get(1).isJsonNull(),
                "an empty slot has no representation, so it is absent");
        assertEquals(Snapshot.INVENTORY_SLOTS, written.getAsJsonArray("inventory").size(),
                "the array keeps its length, so slot numbers still line up");
        assertEquals(Snapshot.ARMOR_SLOTS, written.getAsJsonArray("armor").size());
    }

    @Test
    @DisplayName("a snapshot with nothing new in it is written exactly as commons wrote one")
    void nothingNewMeansNoNewKeys() {
        // A row read from commons, restored, and stored again must not grow two
        // keys it never had — otherwise every migrated row gets bigger for a
        // player who never touched an ender chest.
        Snapshot read = SnapshotCodec.decode(COMMONS_ROW, problems::add);
        assertNotNull(read);

        JsonObject rewritten = JsonParser.parseString(SnapshotCodec.encode(read)).getAsJsonObject();

        assertFalse(rewritten.has("enderChest"), "there was none to write");
        assertFalse(rewritten.has("physical"), "there was none to write");
        assertFalse(rewritten.has("attributes"), "there were none to write");
    }

    // ------------------------------------------------------------ resilience

    @Test
    @DisplayName("one item nobody can read costs its own slot and nothing else")
    void oneBadItemDoesNotTakeTheSnapshot() {
        // The single worst bug in the module this replaces: commons caught
        // everything and returned null, so an item written by a version of the
        // server that no longer exists silently discarded the whole inventory.
        String row = """
                {"gameMode":"SURVIVAL",\
                "inventory":["item:DIAMOND_SWORDx1","NOT-A-READABLE-ITEM","item:BREADx3"],\
                "armor":[null,null,null,null],"offHand":null,\
                "health":20.0,"maxHealth":20.0,"foodLevel":20,"saturation":5.0,\
                "level":10,"exp":0.25,"potionEffects":[],\
                "allowFlight":false,"flying":false,"flySpeed":0.1}""";

        Snapshot read = SnapshotCodec.decode(row, problems::add);

        assertNotNull(read, "the snapshot survives");
        assertEquals(TestItem.of("DIAMOND_SWORD"), read.inventory()[0], "the item before it");
        assertNull(read.inventory()[1], "the unreadable one is an empty slot");
        assertEquals(new TestItem("BREAD", 3), read.inventory()[2], "the item after it");
        assertEquals(10, read.level(), "and everything that is not an item");
        assertEquals(1, problems.size(), "reported once, not swallowed: " + problems);
        assertTrue(problems.get(0).contains("slot 1"), "and it says which slot: " + problems);
    }

    @Test
    @DisplayName("an unreadable off hand costs the off hand")
    void oneBadOffHandDoesNotTakeTheSnapshot() {
        String row = """
                {"gameMode":"SURVIVAL","inventory":["item:BREADx1"],"armor":[],\
                "offHand":"NOT-A-READABLE-ITEM",\
                "health":20.0,"maxHealth":20.0,"foodLevel":20,"saturation":5.0,\
                "level":0,"exp":0.0,"potionEffects":[],\
                "allowFlight":false,"flying":false,"flySpeed":0.1}""";

        Snapshot read = SnapshotCodec.decode(row, problems::add);

        assertNotNull(read);
        assertNull(read.offHand());
        assertEquals(TestItem.of("BREAD"), read.inventory()[0], "the rest is intact");
        assertEquals(1, problems.size(), "reported: " + problems);
    }

    @Test
    @DisplayName("a game mode this server has never heard of costs the game mode")
    void unknownGameModeIsSkipped() {
        String row = COMMONS_ROW.replace("\"gameMode\":\"ADVENTURE\"",
                "\"gameMode\":\"HARDCORE_SPECTATOR\"");

        Snapshot read = SnapshotCodec.decode(row, problems::add);

        assertNotNull(read, "the inventory is worth more than the game mode");
        assertNull(read.gameMode());
        assertFalse(read.has(SnapshotPart.GAME_MODE), "so a restore leaves it alone");
        assertEquals(TestItem.of("DIAMOND_SWORD"), read.inventory()[0]);
        assertEquals(1, problems.size(), "reported: " + problems);
    }

    @Test
    @DisplayName("a potion effect with no type is dropped and the others are kept")
    void effectWithoutATypeIsDropped() {
        String row = COMMONS_ROW.replace("\"potionEffects\":[",
                "\"potionEffects\":[{\"duration\":100,\"amplifier\":0},");

        Snapshot read = SnapshotCodec.decode(row, problems::add);

        assertNotNull(read);
        assertEquals(1, read.potionEffects().size(), "the one that named itself survives");
        assertEquals("SPEED", read.potionEffects().get(0).type());
        assertEquals(1, problems.size(), "reported: " + problems);
    }

    @Test
    @DisplayName("text that is not a snapshot at all is a null, and says so")
    void nonsenseIsNull() {
        assertNull(SnapshotCodec.decode("this is not JSON {{{", problems::add));
        assertEquals(1, problems.size(), "reported: " + problems);

        assertNull(SnapshotCodec.decode("[1,2,3]", problems::add),
                "a JSON array is valid JSON and still not a snapshot");
        assertNull(SnapshotCodec.decode(null), "an empty column is not a problem, just empty");
        assertNull(SnapshotCodec.decode("   "));
    }

    @Test
    @DisplayName("a row missing a number reads it as zero, as commons did")
    void missingNumbersAreZero() {
        Snapshot read = SnapshotCodec.decode("{\"gameMode\":\"CREATIVE\"}", problems::add);

        assertNotNull(read);
        assertEquals(GameMode.CREATIVE, read.gameMode());
        assertEquals(0.0d, read.health());
        assertEquals(0, read.level());
        assertEquals(List.of(), read.potionEffects(), "no effects, not a null list");
        assertNull(read.inventory(), "an absent inventory is absent, not empty");
        assertFalse(read.has(SnapshotPart.INVENTORY));
    }

    // -------------------------------------------------------------- the value

    @Test
    @DisplayName("a snapshot cannot be changed through the array it was given")
    void arraysAreCopied() {
        ItemStack[] inventory = new ItemStack[Snapshot.INVENTORY_SLOTS];
        inventory[0] = TestItem.of("DIAMOND_SWORD");
        Snapshot snapshot = new Snapshot(GameMode.SURVIVAL, inventory, null, null, null,
                20, 20, 20, 5, 0, 0, List.of(), false, false, 0.1f, null);

        inventory[0] = TestItem.of("STICK");
        snapshot.inventory()[1] = TestItem.of("STICK");

        assertEquals(TestItem.of("DIAMOND_SWORD"), snapshot.inventory()[0],
                "the array was copied in");
        assertNull(snapshot.inventory()[1], "and is copied out");
    }
}
