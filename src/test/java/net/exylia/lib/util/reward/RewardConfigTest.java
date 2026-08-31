package net.exylia.lib.util.reward;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewards written in a file, and the seams a future editor menu will hold on to.
 *
 * <p>ExyliaCommons carried a hardcoded menu for editing rewards. It is not here
 * yet, so what is tested is that everything such a menu needs already works
 * without a server: reading a row, drawing it, editing it, duplicating it and
 * writing it back.
 */
class RewardConfigTest {

    private final List<String> problems = new ArrayList<>();

    private List<RewardEntry> read(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception invalid) {
            throw new IllegalStateException("test yaml is not valid", invalid);
        }
        return Rewards.read(config, "rewards", (where, problem) -> problems.add(where + ": " + problem));
    }

    // ------------------------------------------------------------------
    // Reading a file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reward list reads its fields by their stored names")
    void readsFields() {
        List<RewardEntry> rewards = read("""
                rewards:
                  - type: ITEM
                    itemSnapshot: DIAMOND
                    itemAmount: 4
                    chance: 25.0
                  - type: COMMAND
                    command: "eco give %player_name% 500"
                """);

        assertEquals(2, rewards.size());
        assertEquals(RewardType.ITEM, rewards.get(0).type());
        assertEquals("DIAMOND", rewards.get(0).itemSnapshot());
        assertEquals(4, rewards.get(0).itemAmount());
        assertEquals(25.0, rewards.get(0).chance());
        assertEquals("eco give %player_name% 500", rewards.get(1).command());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("a bare string is a command, because hundreds of files write them that way")
    void bareStringIsACommand() {
        List<RewardEntry> rewards = read("""
                rewards:
                  - "eco give %player_name% 500"
                  - "broadcast %player_name% won"
                """);

        assertEquals(2, rewards.size());
        assertTrue(rewards.stream().allMatch(reward -> reward.type() == RewardType.COMMAND));
        assertEquals("eco give %player_name% 500", rewards.get(0).command());
    }

    @Test
    @DisplayName("a new type reads from a file too")
    void newTypes() {
        List<RewardEntry> rewards = read("""
                rewards:
                  - type: ECONOMY
                    value: "2500.50"
                    currency: gems
                  - type: EXPERIENCE
                    value: 250
                  - type: POTION
                    value: "SPEED:1:300"
                """);

        assertEquals(RewardType.ECONOMY, rewards.get(0).type());
        assertEquals("2500.50", rewards.get(0).value());
        assertEquals("gems", rewards.get(0).currency());
        assertEquals("250", rewards.get(1).value());
        assertEquals("SPEED:1:300", rewards.get(2).value());
    }

    @Test
    @DisplayName("a range reads from a file")
    void ranges() {
        List<RewardEntry> rewards = read("""
                rewards:
                  - type: ITEM
                    itemSnapshot: DIAMOND
                    minAmount: 4
                    maxAmount: 12
                """);

        assertTrue(rewards.get(0).isRanged());
        assertEquals(4, rewards.get(0).minAmount());
        assertEquals(12, rewards.get(0).maxAmount());
    }

    @Test
    @DisplayName("a lowercase type still reads, because a file is typed by a human")
    void lowercaseType() {
        assertEquals(RewardType.COMMAND, read("""
                rewards:
                  - type: command
                    command: "say hi"
                """).get(0).type());
    }

    @Test
    @DisplayName("an absent list is not a problem")
    void absent() {
        assertTrue(read("something-else: 5").isEmpty());
        assertTrue(problems.isEmpty(), problems::toString);
    }

    @Test
    @DisplayName("one unreadable row is reported and the rest still read")
    void oneBadRow() {
        List<RewardEntry> rewards = read("""
                rewards:
                  - type: COMMAND
                    command: "one"
                  - type: NONSENSE
                    value: "?"
                  - type: COMMAND
                    command: "three"
                """);

        assertEquals(2, rewards.size());
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).startsWith("rewards[1]"), problems::toString);
    }

    // ------------------------------------------------------------------
    // What an editor menu will need
    // ------------------------------------------------------------------

    @Test
    @DisplayName("editing a reward keeps its identity")
    void editKeepsIdentity() {
        RewardEntry before = RewardEntry.item("DIAMOND").name("Prize").build();

        RewardEntry after = before.toBuilder().name("Grand prize").chance(10.0).build();

        assertEquals(before.id(), after.id(), "an edit changes the reward, not which reward it is");
        assertEquals(before, after, "identity is what equality means here");
        assertEquals("Grand prize", after.name());
        assertEquals(10.0, after.chance());
        assertEquals("Prize", before.name(), "the original is untouched");
    }

    @Test
    @DisplayName("duplicating a reward gives it a new identity")
    void copyIsANewReward() {
        RewardEntry original = RewardEntry.item("DIAMOND").name("Prize").build();

        RewardEntry copy = original.copy();

        assertNotEquals(original.id(), copy.id());
        assertNotEquals(original, copy);
        assertEquals(original.name(), copy.name());
        assertEquals(original.itemSnapshot(), copy.itemSnapshot());
    }

    @Test
    @DisplayName("every reward can be drawn without a server")
    void drawable() {
        assertEquals("COMMAND_BLOCK", RewardEntry.command("say hi").build().resolvedIcon());
        assertEquals("PAPER", RewardEntry.message("hi").build().resolvedIcon());
        assertEquals("GOLD_INGOT", RewardEntry.economy("500").build().resolvedIcon());
        assertEquals("EXPERIENCE_BOTTLE", RewardEntry.experience(5).build().resolvedIcon());
        assertEquals("DIAMOND", RewardEntry.item("DIAMOND").build().resolvedIcon());
        assertEquals("bytes:AAAA", RewardEntry.item("bytes:AAAA").build().resolvedIcon(),
                "a serialised item is handed over whole, for the item module to draw as itself");
    }

    @Test
    @DisplayName("an explicit icon wins over the item")
    void explicitIcon() {
        assertEquals("NETHER_STAR",
                RewardEntry.item("DIAMOND").icon("NETHER_STAR").build().resolvedIcon());
    }

    @Test
    @DisplayName("a head icon is handed over whole, for the item module to draw")
    void headIcon() {
        assertEquals("playerhead-Steve",
                RewardEntry.item("DIAMOND").icon("playerhead-Steve").build().resolvedIcon());
    }

    @Test
    @DisplayName("a reward with no name describes what it gives")
    void unnamedDescribesItself() {
        assertEquals("say hi", RewardEntry.command("say hi").build().displayName());
        assertEquals("4x diamond",
                RewardEntry.item("DIAMOND").itemAmount(4).build().displayName());
        assertEquals("4-12x diamond",
                RewardEntry.item("DIAMOND").amountBetween(4, 12).build().displayName());
        assertEquals("500 gems",
                RewardEntry.economy("500").currency("gems").build().displayName());
        assertEquals("250 XP", RewardEntry.experience(250).build().displayName());
    }

    @Test
    @DisplayName("a named reward shows its name")
    void namedShowsItsName() {
        assertEquals("{primary}&lGRAND PRIZE",
                RewardEntry.item("DIAMOND").name("{primary}&lGRAND PRIZE").build().displayName());
    }

    @Test
    @DisplayName("a half-configured reward says so rather than showing nothing")
    void halfConfigured() {
        assertEquals("(not set)", RewardEntry.of(RewardType.COMMAND).build().displayName());
        assertEquals("(no item)", RewardEntry.of(RewardType.ITEM).build().displayName());
    }

    @Test
    @DisplayName("a list survives being read, edited and written back")
    void editRoundTrip() {
        List<RewardEntry> rewards = new ArrayList<>(read("""
                rewards:
                  - type: COMMAND
                    command: "one"
                  - type: ITEM
                    itemSnapshot: DIAMOND
                """));

        rewards.set(0, rewards.get(0).toBuilder().chance(50.0).build());
        rewards.add(rewards.get(1).copy());

        List<RewardEntry> written = RewardCodec.decode(RewardCodec.encode(rewards));

        assertEquals(3, written.size());
        assertEquals(50.0, written.get(0).chance());
        assertEquals(rewards.get(0).id(), written.get(0).id());
        assertNotEquals(written.get(1).id(), written.get(2).id());
        assertFalse(written.get(2).isRanged());
    }
}
