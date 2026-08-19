package net.exylia.lib.util.reward;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.reward.internal.ItemGiver;
import net.exylia.lib.util.reward.internal.Rolls;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handing rewards to a player.
 *
 * <p>The dice are replaced rather than repeated: a test that rolls a
 * fifty-percent reward a thousand times to prove it works is a test that fails
 * on a Friday, and one that proves nothing about the reward on the Monday.
 *
 * <p>Three of these exist because ExyliaCommons got them wrong. Each says so.
 */
class RewardDeliveryTest {

    private Plugin plugin;
    private PluginRewards rewards;
    private FakePlayer player;

    /** Dice that always say yes, and always roll the low end of a range. */
    private static final Rolls.Dice CERTAIN = new Rolls.Dice() {
        @Override
        public double next(double bound) {
            return 0.0;
        }

        @Override
        public int between(int min, int max) {
            return min;
        }
    };

    /** Dice that always say no. */
    private static final Rolls.Dice NEVER = new Rolls.Dice() {
        @Override
        public double next(double bound) {
            return bound - 0.000_001;
        }

        @Override
        public int between(int min, int max) {
            return max;
        }
    };

    private Bag bag;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("RewardTest");
        World world = FakeServer.newWorld("world");
        FakeServer.worlds(world);
        player = new FakePlayer("Steve").at(new Location(world, 0, 64, 0));
        FakeServer.online(player.player());
        rewards = Rewards.of(plugin);
        Rewards.dice(rewards, CERTAIN);
        bag = new Bag();
        Rewards.items(rewards, bag);
    }

    @AfterEach
    void tearDown() {
        Rewards.releaseAll();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // The basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a command reward runs on the console without its slash")
    void command() {
        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.command("/eco give Steve 500").build()));

        assertEquals(1, delivery.given());
        assertEquals(List.of("eco give Steve 500"), FakeServer.consoleCommands());
    }

    @Test
    @DisplayName("a message reward reaches the player")
    void message() {
        rewards.give(player.player(), List.of(RewardEntry.message("Well played").build()));

        assertEquals(List.of("Well played"), player.messages());
    }

    @Test
    @DisplayName("an item reward lands in the inventory")
    void item() {
        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").itemAmount(16).build()));

        assertEquals(1, delivery.given());
        assertEquals(List.of("DIAMOND x16"), bag.given);
    }

    @Test
    @DisplayName("an experience reward is granted")
    void experience() {
        rewards.give(player.player(), List.of(RewardEntry.experience(250).build()));

        assertEquals(250, player.experience());
    }

    @Test
    @DisplayName("a delivery message is sent when the reward lands")
    void deliveryMessage() {
        rewards.give(player.player(), List.of(
                RewardEntry.item("DIAMOND").deliveryMessage("You got a diamond").build()));

        assertEquals(List.of("You got a diamond"), player.messages());
    }

    @Test
    @DisplayName("no delivery message when the reward did not land")
    void noMessageWhenSkipped() {
        Rewards.dice(rewards, NEVER);

        rewards.give(player.player(), List.of(
                RewardEntry.item("DIAMOND").chance(50.0).deliveryMessage("You got one").build()));

        assertTrue(player.messages().isEmpty(), player.messages()::toString);
    }

    // ------------------------------------------------------------------
    // Commons bug 1: an item nobody had room for was destroyed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an item with nowhere to go is dropped, not destroyed")
    void overflowDrops() {
        bag.roomFor(0);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").itemAmount(3).build()));

        assertEquals(1, delivery.given(), "the player still got it, on the ground");
        assertEquals(List.of("DIAMOND x3"), bag.dropped, "commons destroyed this item");
    }

    @Test
    @DisplayName("with FAIL, an item with nowhere to go is reported")
    void overflowFails() {
        bag.roomFor(0);
        rewards.overflow(OverflowPolicy.FAIL);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").build()));

        assertEquals(0, delivery.given());
        assertEquals(RewardOutcome.NO_ROOM, delivery.results().get(0).outcome());
        assertTrue(bag.dropped.isEmpty(), "FAIL drops nothing");
    }

    @Test
    @DisplayName("with QUEUE, an item with nowhere to go waits in the store")
    void overflowQueues() {
        bag.roomFor(0);
        RecordingStore store = new RecordingStore();
        rewards.overflow(OverflowPolicy.QUEUE).pending(store);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").itemAmount(7).build()));

        assertEquals(RewardOutcome.QUEUED, delivery.results().get(0).outcome());
        assertEquals(1, store.kept.size());
        assertEquals(7, store.kept.get(0).itemAmount(), "the leftover, not the original");
        assertTrue(store.kept.get(0).isGuaranteed(),
                "a reward already earned must not be re-rolled when it is finally given");
    }

    @Test
    @DisplayName("with QUEUE but no store, the item is dropped rather than lost")
    void overflowQueueWithoutStore() {
        bag.roomFor(0);
        rewards.overflow(OverflowPolicy.QUEUE);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").itemAmount(3).build()));

        assertEquals(RewardOutcome.GIVEN, delivery.results().get(0).outcome());
        assertEquals(List.of("DIAMOND x3"), bag.dropped,
                "asking to queue is asking not to lose it; an unreachable store"
                        + " does not change what the plugin asked for");
    }

    @Test
    @DisplayName("with QUEUE and a store that throws, the item is still dropped")
    void overflowQueueWithBrokenStore() {
        bag.roomFor(0);
        rewards.overflow(OverflowPolicy.QUEUE).pending(new PendingRewards() {
            @Override
            public void keep(java.util.UUID id, List<RewardEntry> owed) {
                throw new IllegalStateException("the database is on fire");
            }

            @Override
            public List<RewardEntry> claim(java.util.UUID id) {
                return List.of();
            }
        });

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").itemAmount(3).build()));

        assertEquals(RewardOutcome.GIVEN, delivery.results().get(0).outcome());
        assertEquals(List.of("DIAMOND x3"), bag.dropped);
    }

    // ------------------------------------------------------------------
    // Commons bug 2: the roll ran before the permission and the condition
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing permission is reported as such, not as a lost roll")
    void permissionOutcomeIsDistinct() {
        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").permission("event.vip").build()));

        assertEquals(RewardOutcome.NO_PERMISSION, delivery.results().get(0).outcome());
        assertTrue(bag.given.isEmpty());
    }

    @Test
    @DisplayName("a rare reward still reports a missing permission as such")
    void permissionOutranksTheRoll() {
        Rewards.dice(rewards, NEVER);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").chance(1.0).permission("event.vip").build()));

        assertEquals(RewardOutcome.NO_PERMISSION, delivery.results().get(0).outcome(),
                "commons rolled first, so a rare reward reported a lost roll and the"
                        + " server owner never learned the permission was wrong");
    }

    @Test
    @DisplayName("a rare reward still reports a failed condition as such")
    void conditionOutranksTheRoll() {
        Rewards.dice(rewards, NEVER);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").chance(1.0).condition("3 >= 5").build()));

        assertEquals(RewardOutcome.CONDITION_FAILED, delivery.results().get(0).outcome());
    }

    @Test
    @DisplayName("a granted permission lets the reward through")
    void permissionGranted() {
        player.grant("event.vip");

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").permission("event.vip").build()));

        assertEquals(1, delivery.given());
    }

    @Test
    @DisplayName("a lost roll is a skip, not a failure")
    void lostRollIsNotAFailure() {
        Rewards.dice(rewards, NEVER);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").chance(50.0).build()));

        assertEquals(RewardOutcome.NOT_ROLLED, delivery.results().get(0).outcome());
        assertTrue(delivery.results().get(0).outcome().isSkipped());
        assertFalse(delivery.results().get(0).outcome().isFailure());
        assertEquals(0, delivery.failed(), "commons counted this as a failure");
        assertEquals(0, rewards.failedCount());
    }

    @Test
    @DisplayName("a guaranteed reward does not roll at all")
    void guaranteedNeverRolls() {
        Rewards.dice(rewards, NEVER);

        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").build()));

        assertEquals(1, delivery.given());
    }

    // ------------------------------------------------------------------
    // Commons bug 3: an unreadable condition silently deleted the reward
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a condition that holds lets the reward through")
    void conditionHolds() {
        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("10 >= 5").build()));

        assertEquals(1, delivery.given());
    }

    @Test
    @DisplayName("a condition that does not hold withholds the reward")
    void conditionFails() {
        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("3 >= 5").build()));

        assertEquals(RewardOutcome.CONDITION_FAILED, delivery.results().get(0).outcome());
    }

    @Test
    @DisplayName("an unreadable condition gives the reward rather than eating it")
    void unreadableConditionFailsOpen() {
        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("this is not a comparison").build()));

        assertEquals(1, delivery.given(),
                "commons returned false here, so a typo deleted the reward in silence");
    }

    @Test
    @DisplayName("a condition comparing something that is not a number fails open")
    void conditionOnNonNumberFailsOpen() {
        RewardDelivery delivery = rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("%missing% >= 5").build()));

        assertEquals(1, delivery.given());
    }

    @Test
    @DisplayName("string comparison is case-insensitive on both operators")
    void stringComparison() {
        assertEquals(1, rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("Gold == gold").build())).given());
        assertEquals(0, rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("gold != GOLD").build())).given());
    }

    @Test
    @DisplayName(">= is tried before >, so a boundary is not read as greater-than")
    void operatorOrder() {
        assertEquals(1, rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("5 >= 5").build())).given());
        assertEquals(0, rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").condition("5 > 5").build())).given());
    }

    // ------------------------------------------------------------------
    // Isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one broken reward costs only itself")
    void oneBadRewardDoesNotStopTheRest() {
        RewardDelivery delivery = rewards.give(player.player(), List.of(
                RewardEntry.command("eco give Steve 500").build(),
                RewardEntry.of(RewardType.ITEM).build(),
                RewardEntry.message("Well played").build()));

        assertEquals(2, delivery.given());
        assertEquals(1, delivery.failed());
        assertFalse(delivery.isClean());
        assertEquals(1, delivery.failures().size());
        assertNotNull(delivery.failures().get(0).detail());
    }

    @Test
    @DisplayName("higher priority is given first, and equal priority keeps its order")
    void priorityOrder() {
        rewards.give(player.player(), List.of(
                RewardEntry.command("third").priority(0).build(),
                RewardEntry.command("first").priority(10).build(),
                RewardEntry.command("fourth").priority(0).build(),
                RewardEntry.command("second").priority(5).build()));

        assertEquals(List.of("first", "second", "third", "fourth"), FakeServer.consoleCommands());
    }

    @Test
    @DisplayName("a broken reward is reported once, not once per player")
    void reportsOnce() {
        List<String> logged = net.exylia.lib.debug.DebugCapture.start();
        try {
            RewardEntry broken = RewardEntry.of(RewardType.ITEM).build();

            rewards.give(player.player(), List.of(broken));
            rewards.give(player.player(), List.of(broken));
            rewards.give(player.player(), List.of(broken));

            assertEquals(1, logged.size(), logged::toString);
            assertEquals(3, rewards.failedCount(), "every failure is still counted");
        } finally {
            net.exylia.lib.debug.DebugCapture.stop();
        }
    }

    @Test
    @DisplayName("a reward broken for everyone is reported once, not once per player")
    void reportsOncePerProblemNotPerPlayer() {
        // A failure detail carries the values of the player it happened to,
        // which is what an operator needs to read and exactly what must not
        // become the key: keyed on that, one broken reward prints a line per
        // player and the remembered set grows without bound.
        // A real failure detail names the command as it ran, with that player's
        // values already substituted, so it differs per player.
        net.exylia.lib.placeholder.Placeholders.register(plugin, "who",
                request -> request.requireViewer().getName());
        FakeServer.consoleRejectsCommands();
        List<String> logged = net.exylia.lib.debug.DebugCapture.start();
        try {
            RewardEntry broken = RewardEntry.command("eco give %who% 500").build();

            rewards.give(player.player(), List.of(broken));
            rewards.give(new FakePlayer("Alex").player(), List.of(broken));
            rewards.give(new FakePlayer("Sam").player(), List.of(broken));

            // The stack trace is per occurrence, deliberately: three failures
            // really did happen. What must not repeat is the warning naming the
            // reward, which is the line an operator reads.
            List<String> warnings = logged.stream()
                    .filter(line -> line.contains("Reward \""))
                    .toList();
            assertEquals(1, warnings.size(),
                    "keyed on the detail this prints once per player: " + warnings);
        } finally {
            net.exylia.lib.debug.DebugCapture.stop();
            net.exylia.lib.placeholder.Placeholders.unregisterAll(plugin.getName());
        }
    }

    @Test
    @DisplayName("a broken condition is reported once, not once per player")
    void conditionReportedOncePerProblem() {
        // The message names the value this player's placeholder produced, which
        // is what an operator needs and exactly what must not become the key.
        net.exylia.lib.placeholder.Placeholders.register(plugin, "who",
                request -> request.requireViewer().getName());
        List<String> logged = net.exylia.lib.debug.DebugCapture.start();
        try {
            RewardEntry broken = RewardEntry.item("DIAMOND").condition("%who% >= 5").build();

            rewards.give(player.player(), List.of(broken));
            rewards.give(new FakePlayer("Alex").player(), List.of(broken));
            rewards.give(new FakePlayer("Sam").player(), List.of(broken));

            assertEquals(1, logged.size(),
                    "keyed on the resolved message this prints once per player: " + logged);
        } finally {
            net.exylia.lib.debug.DebugCapture.stop();
            net.exylia.lib.placeholder.Placeholders.unregisterAll(plugin.getName());
        }
    }

    @Test
    @DisplayName("forgetting problems makes a reload complain afresh")
    void forgetProblems() {
        List<String> logged = net.exylia.lib.debug.DebugCapture.start();
        try {
            RewardEntry broken = RewardEntry.of(RewardType.ITEM).build();

            rewards.give(player.player(), List.of(broken));
            rewards.forgetProblems();
            rewards.give(player.player(), List.of(broken));

            assertEquals(2, logged.size(), logged::toString);
        } finally {
            net.exylia.lib.debug.DebugCapture.stop();
        }
    }

    @Test
    @DisplayName("an empty list does nothing at all")
    void emptyList() {
        assertEquals(RewardDelivery.EMPTY.results(),
                rewards.give(player.player(), List.of()).results());
    }

    // ------------------------------------------------------------------
    // Amount ranges
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a ranged item gives an amount within its range")
    void rangedAmount() {
        Rewards.dice(rewards, NEVER);

        rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").amountBetween(4, 12).build()));

        assertEquals(List.of("DIAMOND x12"), bag.given, "NEVER rolls the high end");
    }

    @Test
    @DisplayName("a range wins over the fixed amount")
    void rangeBeatsFixed() {
        rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").itemAmount(64).amountBetween(4, 12).build()));

        assertEquals(List.of("DIAMOND x4"), bag.given, "CERTAIN rolls the low end");
    }

    @Test
    @DisplayName("an item never gives fewer than one")
    void neverZero() {
        rewards.give(player.player(),
                List.of(RewardEntry.item("DIAMOND").itemAmount(0).build()));

        assertEquals(List.of("DIAMOND x1"), bag.given);
    }

    // ------------------------------------------------------------------
    // Weighted rolls
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a weighted roll gives exactly one of the group")
    void weightedRollGivesOne() {
        RewardDelivery delivery = rewards.roll(player.player(), List.of(
                RewardEntry.command("common").weight(90).build(),
                RewardEntry.command("rare").weight(10).build()));

        assertEquals(1, delivery.results().size());
        assertEquals(List.of("common"), FakeServer.consoleCommands(),
                "CERTAIN rolls zero, which lands in the first entry's share");
    }

    @Test
    @DisplayName("weight decides which, and the roll walks the whole range")
    void weightPicks() {
        List<RewardEntry> group = List.of(
                RewardEntry.command("a").weight(1).build(),
                RewardEntry.command("b").weight(1).build(),
                RewardEntry.command("c").weight(1).build());

        assertEquals("a", Rolls.pick(group, fixed(0.5)).command());
        assertEquals("b", Rolls.pick(group, fixed(1.5)).command());
        assertEquals("c", Rolls.pick(group, fixed(2.5)).command());
    }

    @Test
    @DisplayName("an entry of zero weight is never picked")
    void zeroWeightNeverWins() {
        List<RewardEntry> group = List.of(
                RewardEntry.command("never").weight(0).build(),
                RewardEntry.command("always").weight(5).build());

        for (double roll = 0.0; roll < 5.0; roll += 0.5) {
            assertEquals("always", Rolls.pick(group, fixed(roll)).command());
        }
    }

    @Test
    @DisplayName("a negative weight does not steal another entry's share")
    void negativeWeight() {
        // A server owner typing -5 must not shift the odds of everything after
        // it: subtracting a negative would push the roll past every remaining
        // entry and hand the win to the last one instead of the right one.
        List<RewardEntry> group = List.of(
                RewardEntry.command("broken").weight(-5).build(),
                RewardEntry.command("first").weight(1).build(),
                RewardEntry.command("second").weight(1).build());

        assertEquals("first", Rolls.pick(group, fixed(0.5)).command());
        assertEquals("second", Rolls.pick(group, fixed(1.5)).command());
    }

    @Test
    @DisplayName("a group where nothing can win gives nothing")
    void allZeroWeights() {
        RewardDelivery delivery = rewards.roll(player.player(), List.of(
                RewardEntry.command("a").weight(0).build(),
                RewardEntry.command("b").weight(0).build()));

        assertTrue(delivery.results().isEmpty());
        assertTrue(FakeServer.consoleCommands().isEmpty());
    }

    @Test
    @DisplayName("the winner's own chance still applies")
    void winnerStillRolls() {
        Rewards.dice(rewards, NEVER);

        RewardDelivery delivery = rewards.roll(player.player(),
                List.of(RewardEntry.command("rare").chance(1.0).weight(1).build()));

        assertEquals(RewardOutcome.NOT_ROLLED, delivery.results().get(0).outcome());
    }

    // ------------------------------------------------------------------
    // Pending rewards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rewards owed to an absent player go to the plugin's store")
    void giveLater() {
        RecordingStore store = new RecordingStore();
        rewards.pending(store);

        assertTrue(rewards.giveLater(player.player().getUniqueId(),
                List.of(RewardEntry.command("one").build())));
        assertEquals(1, store.kept.size());
    }

    @Test
    @DisplayName("owing rewards with no store reports rather than losing them")
    void giveLaterWithoutStore() {
        assertFalse(rewards.giveLater(player.player().getUniqueId(),
                List.of(RewardEntry.command("one").build())));
    }

    @Test
    @DisplayName("a store that throws does not take the caller down with it")
    void storeThatThrows() {
        rewards.pending(new PendingRewards() {
            @Override
            public void keep(java.util.UUID id, List<RewardEntry> owed) {
                throw new IllegalStateException("the database is on fire");
            }

            @Override
            public List<RewardEntry> claim(java.util.UUID id) {
                return List.of();
            }
        });

        assertFalse(rewards.giveLater(player.player().getUniqueId(),
                List.of(RewardEntry.command("one").build())));
    }

    @Test
    @DisplayName("claiming hands over everything that was owed")
    void claim() {
        RecordingStore store = new RecordingStore();
        store.kept.add(RewardEntry.command("owed one").build());
        store.kept.add(RewardEntry.command("owed two").build());
        rewards.pending(store);

        // Two ticks: the read is scheduled off the main thread and hands the
        // delivery back onto the player's, which is a second task.
        rewards.claim(player.player(), delivery -> assertEquals(2, delivery.given()));
        FakeServer.tick(2);

        assertEquals(List.of("owed one", "owed two"), FakeServer.consoleCommands());
        assertTrue(store.kept.isEmpty(), "a claimed reward must not be handed out twice");
    }

    @Test
    @DisplayName("claiming with nothing owed does nothing")
    void claimNothing() {
        rewards.pending(new RecordingStore());

        rewards.claim(player.player(), delivery -> {
            throw new AssertionError("nothing was owed");
        });
        FakeServer.tick(2);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same plugin gets the same view")
    void sameView() {
        assertEquals(rewards, Rewards.of(plugin));
    }

    @Test
    @DisplayName("a disabled plugin's view is forgotten")
    void release() {
        assertEquals(1, Rewards.registered());

        Rewards.release(plugin.getName());

        assertEquals(0, Rewards.registered());
    }

    private static Rolls.Dice fixed(double value) {
        return new Rolls.Dice() {
            @Override
            public double next(double bound) {
                return value;
            }

            @Override
            public int between(int min, int max) {
                return min;
            }
        };
    }

    /**
     * An inventory with a known amount of room.
     *
     * <p>Standing in for the one seam that needs a running server, which is
     * exactly why it is a seam: everything the runtime decides about an item is
     * decided from a name and a count.
     */
    private static final class Bag implements ItemGiver {

        private final List<String> given = new java.util.ArrayList<>();
        private final List<String> dropped = new java.util.ArrayList<>();
        private int free = Integer.MAX_VALUE;

        void roomFor(int amount) {
            this.free = amount;
        }

        @Override
        public int give(org.bukkit.entity.Player player, String snapshot, int amount) {
            if (snapshot.equals("UNREADABLE")) {
                return ItemGiver.UNREADABLE;
            }
            int fits = Math.min(amount, free);
            if (fits > 0) {
                given.add(snapshot + " x" + fits);
                free -= fits;
            }
            return amount - fits;
        }

        @Override
        public void drop(org.bukkit.entity.Player player, String snapshot, int amount) {
            dropped.add(snapshot + " x" + amount);
        }
    }

    /** A pending store that keeps everything in a list. */
    private static final class RecordingStore implements PendingRewards {

        private final List<RewardEntry> kept = new java.util.ArrayList<>();

        @Override
        public void keep(java.util.UUID player, List<RewardEntry> owed) {
            kept.addAll(owed);
        }

        @Override
        public List<RewardEntry> claim(java.util.UUID player) {
            List<RewardEntry> owed = List.copyOf(kept);
            kept.clear();
            return owed;
        }
    }
}
