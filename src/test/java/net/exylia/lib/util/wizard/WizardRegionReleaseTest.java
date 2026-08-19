package net.exylia.lib.util.wizard;

import net.exylia.lib.FakeServer;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.SelectionSession;
import net.exylia.lib.region.SelectionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing a run leaks that outlives the run: the player's block selector.
 *
 * <p>The other three things a flow holds are its own. A leaked boss bar is this
 * plugin's bar, a leaked question is this plugin's question, and a leaked wizard
 * slot only stops this library's wizards. The selector is not: {@code
 * PluginRegions.beginSelection} is one selector per player across the whole
 * server, and it refuses with an {@link IllegalStateException} while somebody
 * owns it. A run that ends on a region step without letting go leaves that
 * player unable to select a block for <em>any</em> plugin &mdash; WorldGuard
 * claims, arena setup, a shop region &mdash; until they reconnect. It is the
 * only leak in the module a player can carry out of the plugin that caused it.
 *
 * <p>So each ending is asserted the way the next plugin would find out: by
 * claiming the selector again, from a different plugin, and requiring that to
 * work. The session's own {@code CANCELLED} state and the empty owner lookup are
 * asserted too, but they are the internal echo &mdash; the retake is the
 * contract.
 *
 * <p>Five endings, one per test rather than a loop, because when this breaks the
 * useful thing to know first is which way of ending forgot.
 */
class WizardRegionReleaseTest {

    private static final WizardKey<String> ID = WizardKey.text("id");
    private static final WizardKey<net.exylia.lib.region.SelectionResult> AREA =
            WizardKey.region("area");

    private WizardHarness harness;

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Events", "DiGround");
        // Symmetrical with the teardown below. A previous test that leaked a
        // selector would otherwise make the very first beginSelection here
        // throw, and this class would fail for somebody else's bug.
        Regions.releaseAll();
        harness.wizards().using(new WizardSettings(30, 3, false, "%title%"));
    }

    @AfterEach
    void tearDown() {
        harness.stop();
        // Not only for tidiness: while release() is broken the selector really
        // is still held after the run ends, and without this the leak would
        // travel into every wizard test that runs after this class.
        Regions.releaseAll();
    }

    /** A flow that stops on a region step, with the selection it claimed. */
    private SelectionSession startAndReachTheRegionStep() {
        Wizard event = harness.wizards().define("event")
                .ask(ID, step -> step.id("Event id"))
                .region(AREA, "Select the arena bounds")
                .summary()
                .build();

        harness.wizards().start(harness.player(), event);
        harness.settle();
        harness.answer("blitz");

        SelectionSession claimed = Regions.of(harness.plugin())
                .selection(harness.player())
                .orElse(null);
        assertTrue(claimed != null,
                "the flow never reached the region step, so this test proves nothing");
        assertEquals(SelectionState.ACTIVE, claimed.state());
        return claimed;
    }

    /**
     * Asserts the player got their selector back, the way another plugin finds
     * out that they did not.
     *
     * @param claimed the session the ended run had taken
     * @param ending  how the run stopped, for the failure message
     */
    private void assertSelectorReleased(SelectionSession claimed, String ending) {
        // Asserted first because it is the contract, not the echo: some other
        // plugin asks for this player's selector afterwards and must get it.
        PluginRegions somebodyElse = Regions.of(FakeServer.newPlugin("Practice", null));
        SelectionSession retaken = assertDoesNotThrow(
                () -> somebodyElse.beginSelection(harness.player()),
                "a flow that was " + ending + " left " + harness.player().getName()
                        + " unable to select a block for any plugin until they reconnect");
        retaken.cancel();

        assertEquals(SelectionState.CANCELLED, claimed.state(),
                "a flow that was " + ending + " left the selection alive");
        assertTrue(Regions.of(harness.plugin()).selection(harness.player()).isEmpty(),
                "a flow that was " + ending + " still owns the player's selection");
    }

    @Test
    @DisplayName("cancelling on a region step gives the player their selector back")
    void cancelReleasesTheSelector() {
        SelectionSession claimed = startAndReachTheRegionStep();

        Wizards.running(harness.player()).ifPresent(WizardRun::cancel);
        harness.settle();

        assertSelectorReleased(claimed, "cancelled");
    }

    @Test
    @DisplayName("running past the limit on a region step gives the player their selector back")
    void timeoutReleasesTheSelector() {
        // The likeliest way this ends in production: selecting two corners means
        // walking across an arena, which is exactly the step a player wanders
        // off in the middle of.
        SelectionSession claimed = startAndReachTheRegionStep();

        harness.settle(30 * 20 + 5);

        assertSelectorReleased(claimed, "timed out");
    }

    @Test
    @DisplayName("leaving on a region step gives the player their selector back")
    void disconnectReleasesTheSelector() {
        // And the worst one to get wrong: the selector is keyed by UUID, so it
        // is still held for the same player when they come back.
        SelectionSession claimed = startAndReachTheRegionStep();

        harness.disconnect();

        assertSelectorReleased(claimed, "left by a player who quit");
    }

    @Test
    @DisplayName("disabling the owning plugin gives the player their selector back")
    void pluginReleaseReleasesTheSelector() {
        // Wizards.release ends the runs; it does not touch the region module.
        // Nothing else is going to clean this up, which is the point.
        SelectionSession claimed = startAndReachTheRegionStep();

        Wizards.release(harness.plugin().getName());
        harness.settle();

        assertSelectorReleased(claimed, "shut down");
    }

    @Test
    @DisplayName("a second flow displacing one on a region step gives the selector back")
    void replacementReleasesTheSelector() {
        // The displaced run must let go before the flow that won asks for
        // anything, or the winner's own region step would be refused by the
        // corpse of the one it replaced.
        SelectionSession claimed = startAndReachTheRegionStep();

        harness.wizards().start(harness.player(), harness.wizards().define("other")
                .ask(ID, step -> step.id("Event id"))
                .build());
        harness.settle();

        assertSelectorReleased(claimed, "replaced");
    }
}
