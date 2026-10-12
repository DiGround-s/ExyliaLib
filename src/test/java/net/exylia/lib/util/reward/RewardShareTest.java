package net.exylia.lib.util.reward;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("in a weighted draw the share counts the weight")
    void weightedShareCountsWeight() {
        RewardEntry heavy = RewardEntry.command("say hi").weight(10).build();
        RewardEntry light = RewardEntry.command("say hi").weight(1).build();
        List<RewardEntry> list = List.of(heavy, light, RewardEntry.command("say hi").weight(9).build());
        assertEquals("50", RewardDescriptor.share(heavy, list, true));
        assertEquals("5", RewardDescriptor.share(light, list, true));
        assertEquals(RewardDescriptor.share(heavy, list, false), RewardDescriptor.share(light, list, false));
    }

    @Test
    @DisplayName("a weight steps by tenths below 1 and never reaches zero")
    void weightSteps() {
        assertEquals(2.0, RewardDescriptor.stepWeight(1.0, true));
        assertEquals(0.9, RewardDescriptor.stepWeight(1.0, false));
        assertEquals(0.1, RewardDescriptor.stepWeight(0.1, false));
        assertEquals(15.0, RewardDescriptor.stepWeight(10.0, true));
        assertEquals(9.0, RewardDescriptor.stepWeight(10.0, false));
    }

    @Test
    @DisplayName("sorting puts the heaviest first, and a second press the lightest")
    void sortToggles() {
        RewardEntry a = RewardEntry.command("a").weight(1).build();
        RewardEntry b = RewardEntry.command("b").weight(5).build();
        RewardEntry c = RewardEntry.command("c").weight(3).build();
        List<RewardEntry> once = RewardDescriptor.sorted(List.of(a, b, c));
        assertEquals(List.of(b, c, a), once);
        assertEquals(List.of(a, c, b), RewardDescriptor.sorted(once));
    }
}
