package net.exylia.lib.util.wizard;

import net.exylia.lib.FakeServer;
import net.exylia.lib.util.wizard.internal.WizardPeek;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two steps that are not questions, and the copies they must keep.
 *
 * <p>A pick and a hand both answer with something the server owns and keeps
 * mutating. The location a Bukkit event hands out is live enough that holding
 * it makes the answer follow the block's chunk around; the stack in a player's
 * hand is usually air by the time the review is confirmed. Both are stored as
 * copies, and that is the whole reason these steps exist as their own kinds
 * rather than as questions with a clever parser.
 */
class WizardStepKindTest {

    private static final WizardKey<Location> SPAWN = WizardKey.location("spawn");
    private static final WizardKey<ItemStack> ICON = WizardKey.item("icon");
    private static final WizardKey<String> ID = WizardKey.text("id");

    private WizardHarness harness;
    private World world;

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Events", "DiGround");
        world = FakeServer.newWorld("arena");
        FakeServer.worlds(world);
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    @DisplayName("a clicked block answers a pick, and the answer is a copy")
    void pickStoresACopy() {
        AtomicReference<WizardValues> collected = new AtomicReference<>();
        Wizard event = harness.wizards().define("event")
                .pick(SPAWN, "Click the spawn block")
                .onFinish(collected::set)
                .build();

        harness.wizards().start(harness.player(), event);
        harness.settle();

        Location clicked = new Location(world, 10, 64, 20);
        assertTrue(WizardPeek.pick(harness.player().getUniqueId(), clicked),
                "a run waiting for a block must consume the click");
        harness.settle();

        Location answered = collected.get().get(SPAWN);
        assertEquals(10, answered.getBlockX());
        assertEquals(64, answered.getBlockY());
        assertEquals(20, answered.getBlockZ());
        assertNotSame(clicked, answered,
                "the live location a Bukkit event hands out must not become the answer");

        // And moving the original afterwards must not move the answer.
        clicked.setX(999);
        assertEquals(10, collected.get().get(SPAWN).getBlockX());
    }

    @Test
    @DisplayName("a click for a player who is in no flow is left alone")
    void pickIgnoresEverybodyElse() {
        // The listener is one for the whole server, so it sees every click on
        // it. A run that is not waiting must not swallow one, or a player would
        // find their blocks stopped breaking.
        assertFalse(WizardPeek.pick(harness.player().getUniqueId(), new Location(world, 1, 1, 1)),
                "a click nobody is waiting for must not be consumed");
    }

    @Test
    @DisplayName("a click is ignored while the flow is waiting on something else")
    void pickIgnoredDuringAQuestion() {
        Wizard event = harness.wizards().define("event")
                .ask(ID, step -> step.id("Event id"))
                .pick(SPAWN, "Click the spawn block")
                .build();

        harness.wizards().start(harness.player(), event);
        harness.settle();

        assertFalse(WizardPeek.pick(harness.player().getUniqueId(), new Location(world, 1, 1, 1)),
                "a click must not answer a step the flow has not reached");
    }

    /**
     * An item that is only a material.
     *
     * <p>Subclassed rather than constructed: {@code new ItemStack(...)} reaches
     * through {@code Bukkit.getUnsafe()} for a real server, which there is not
     * one of here. What a hand step does with a stack is clone it and read its
     * type, so those are the only two things this has to be able to do.
     */
    private static final class Stack extends ItemStack {

        private Material type;

        private Stack(Material type) {
            this.type = type;
        }

        @Override
        public @org.jetbrains.annotations.NotNull Material getType() {
            return type;
        }

        /**
         * Changes what this stack is.
         *
         * <p>Its own method rather than an override of {@code setType}, which
         * Paper deprecates: a test that has to suppress a warning to say
         * "the player put the sword away" is describing the API's problem
         * instead of the contract under test.
         */
        private void becomes(Material replacement) {
            this.type = replacement;
        }

        @Override
        public int getAmount() {
            return 1;
        }

        @Override
        public @org.jetbrains.annotations.NotNull ItemStack clone() {
            return new Stack(type);
        }
    }

    @Test
    @DisplayName("a held item answers a hand step, and the answer is a copy")
    void handStoresACopy() {
        Stack held = new Stack(Material.DIAMOND_SWORD);
        WizardPeek.installHands(player -> held);

        AtomicReference<WizardValues> collected = new AtomicReference<>();
        Wizard event = harness.wizards().define("event")
                .hand(ICON, "Hold the icon and confirm")
                .onFinish(collected::set)
                .build();

        harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("yes");

        ItemStack answered = collected.get().get(ICON);
        assertEquals(Material.DIAMOND_SWORD, answered.getType());
        assertNotSame(held, answered,
                "the live stack changes the moment the player moves it");

        held.becomes(Material.AIR);
        assertEquals(Material.DIAMOND_SWORD, collected.get().get(ICON).getType(),
                "emptying the hand must not empty the answer");
    }

    @Test
    @DisplayName("an empty hand is asked again rather than failing the flow")
    void emptyHandIsAskedAgain() {
        // A mistake a player makes and fixes in a second. Failing the run for
        // it would be punishing them for putting the item down; the run timeout
        // still bounds how long they may keep making it.
        AtomicReference<ItemStack> holding = new AtomicReference<>(new Stack(Material.AIR));
        WizardPeek.installHands(player -> holding.get());

        AtomicReference<WizardValues> collected = new AtomicReference<>();
        Wizard event = harness.wizards().define("event")
                .hand(ICON, "Hold the icon and confirm")
                .onFinish(collected::set)
                .build();

        WizardRun run = harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("yes");

        assertFalse(run.isFinished(), "an empty hand must not end the flow");
        assertTrue(harness.fake().messages().stream()
                        .anyMatch(line -> line.contains("not holding")),
                "the player must be told what went wrong: " + harness.fake().messages());

        holding.set(new Stack(Material.NETHERITE_SWORD));
        harness.answer("yes");

        assertEquals(Material.NETHERITE_SWORD, collected.get().get(ICON).getType());
    }

    @Test
    @DisplayName("declining a hand step cancels rather than storing nothing")
    void decliningAHandCancels() {
        WizardPeek.installHands(player -> new Stack(Material.DIAMOND));
        AtomicReference<WizardOutcome> ended = new AtomicReference<>();
        Wizard event = harness.wizards().define("event")
                .hand(ICON, "Hold the icon and confirm")
                .onCancel(ended::set)
                .build();

        harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("no");
        harness.settle(2);

        assertEquals(WizardOutcome.CANCELLED, ended.get(),
                "saying no to the item is the player stopping, not an empty answer");
        harness.assertNothingLeaked();
    }

    @Test
    @DisplayName("the answers reach the review in the order they were collected")
    void answersKeepTheirOrder() {
        Wizard event = harness.wizards().define("event")
                .ask(ID, step -> step.id("Event id"))
                .pick(SPAWN, "Click the spawn block")
                .summary()
                .build();

        WizardRun run = harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("blitz");
        WizardPeek.pick(harness.player().getUniqueId(), new Location(world, 1, 2, 3));
        harness.settle();

        assertEquals(java.util.List.of("id", "spawn"), WizardPeek.answered(run),
                "the review lists answers in the order the player gave them");
    }
}
