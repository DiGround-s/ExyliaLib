package net.exylia.lib.util.reward;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The share line: what a reward is worth against the rest of its list.
 *
 * <p>The point of the line is that the same chance means different things in
 * different lists, so the tests are two lists holding the same row.
 */
class RewardShareTest {

    private RewardDescriptor descriptor;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Plugin plugin = FakeServer.newPlugin("RewardShareTest");
        descriptor = new RewardDescriptor(plugin);
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private static RewardEntry at(double chance) {
        return RewardEntry.command("say hi").chance(chance).build();
    }

    private String share(RewardEntry entry, List<RewardEntry> siblings) {
        return descriptor.lore(entry, siblings).stream()
                .filter(line -> line.contains("Real"))
                .findFirst()
                .orElse("");
    }

    @Test
    @DisplayName("the same chance is a different share in a bigger list")
    void sharesAreRelative() {
        RewardEntry entry = at(20.0);
        List<RewardEntry> five = List.of(entry, at(20.0), at(20.0), at(20.0), at(20.0));
        List<RewardEntry> twenty = new java.util.ArrayList<>(List.of(entry));
        while (twenty.size() < 20) {
            twenty.add(at(20.0));
        }
        assertTrue(share(entry, five).contains("20%"), share(entry, five));
        assertTrue(share(entry, twenty).contains("5%"), share(entry, twenty));
    }

    @Test
    @DisplayName("a rarer reward is a smaller share than its common siblings")
    void raritySharesLess() {
        RewardEntry rare = at(5.0);
        List<RewardEntry> list = List.of(rare, at(95.0));
        assertTrue(share(rare, list).contains("5%"), share(rare, list));
    }

    @Test
    @DisplayName("a list of one has no share to draw, and neither has a dead list")
    void nothingUsefulIsNotDrawn() {
        RewardEntry only = at(40.0);
        assertTrue(share(only, List.of(only)).isEmpty());
        RewardEntry dead = at(0.0);
        assertTrue(share(dead, List.of(dead, at(0.0))).isEmpty());
    }
}
