package net.exylia.lib.util.wizard;

import net.exylia.lib.FakeServer;
import net.exylia.lib.util.wizard.internal.WizardPeek;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-question shortcuts.
 *
 * <p>What they exist for is the cancel path: every plugin wrote this flow by
 * hand and each wired that differently, so the contract worth pinning is that
 * {@code abandoned} runs when the player backed out and <b>never</b> after a
 * finish — a caller reopening its menu in both places would otherwise draw over
 * the screen its own success callback just opened.
 */
class WizardAskTest {

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
    @DisplayName("askStand answers with where the player stood, facing included")
    void standAnswersWithThePlace() {
        AtomicReference<Location> answered = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();

        Location standing = new Location(world, 10.5, 64, 20.5, 90f, 12f);
        harness.fake().at(standing);
        harness.wizards().askStand(harness.player(), "{primary}&lLOBBY SPAWN",
                "Stand where it goes and sneak-click", answered::set, () -> abandoned.set(true));
        harness.settle();

        assertTrue(WizardPeek.interact(harness.player().getUniqueId(), null, true),
                "sneak and click must answer a standing pick");
        harness.settle();

        assertNotNull(answered.get(), "the flow must have finished");
        assertEquals(10.5, answered.get().getX());
        assertEquals(90f, answered.get().getYaw(),
                "a spawn keeps the direction the admin was facing");
        assertFalse(abandoned.get(),
                "a finished flow must not run the callback for backing out");
    }

    @Test
    @DisplayName("backing out runs the abandoned callback and nothing else")
    void cancellingReopensTheMenu() {
        AtomicReference<Location> answered = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();

        WizardRun run = harness.wizards().askStand(harness.player(), "{primary}&lLOBBY SPAWN",
                "Stand where it goes", answered::set, () -> abandoned.set(true));
        harness.settle();

        run.cancel();
        harness.settle();

        assertTrue(abandoned.get(), "backing out must reopen whatever asked");
        assertNull(answered.get(), "nothing was chosen, so nothing is handed back");
    }

    @Test
    @DisplayName("a title becomes an id a log line can print")
    void titlesBecomeReadableIds() {
        WizardRun run = harness.wizards().askStand(harness.player(),
                "{primary}&lLOBBY SPAWN <gradient:#fff:#000>Park</gradient>",
                "Stand where it goes", location -> {}, null);
        harness.settle();

        assertEquals("lobby-spawn-park", run.wizard().id(),
                "colours and tags are not part of an id, the words are");
    }

    @Test
    @DisplayName("two shortcuts in a row replace each other rather than stacking")
    void asecondAskReplacesTheFirst() {
        AtomicBoolean firstAbandoned = new AtomicBoolean();
        harness.wizards().askStand(harness.player(), "First", "Stand", location -> {},
                () -> firstAbandoned.set(true));
        harness.settle();

        harness.wizards().askPoint(harness.player(), "Second", "Click", location -> {}, null);
        harness.settle();

        assertFalse(firstAbandoned.get(),
                "a plugin replacing its own question is not the player backing out, "
                        + "and reopening a menu here is how two screens end up fighting");
    }
}
