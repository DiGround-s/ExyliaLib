package net.exylia.lib.panel;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionExecution;
import net.exylia.lib.action.ActionSequence;
import net.exylia.lib.action.ActionStep;
import net.exylia.lib.action.Actions;
import net.exylia.lib.panel.internal.PanelRuntime;
import net.exylia.lib.panel.internal.Session;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing survives its owner.
 *
 * <p>The three ways a panel ends — the player leaves, the plugin is disabled,
 * the window closes — and the one thing they must all do: give back everything
 * the panel took, including the delayed action steps a button started.
 *
 * <p>Sessions are created directly rather than through {@code open}, because
 * opening needs a real inventory and {@code Bukkit.createInventory} has no
 * server to answer it. What is under test here is release, not drawing.
 */
class PanelLifecycleTest {

    private Plugin a;
    private Plugin b;
    private FakePlayer viewer;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        a = FakeServer.newPlugin("A");
        b = FakeServer.newPlugin("B");
        viewer = new FakePlayer("Steve");
        FakeServer.online(viewer.player());
        FakeServer.plugins(a, b);
    }

    @AfterEach
    void tearDown() {
        PanelRuntime.releaseAll();
        Actions.releaseAll();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("quit releases that player's session and leaves the owner holding none")
    void quitReleasesTheSession() {
        Session session = Session.forTests(PanelRuntime.of(a), viewer.player(), "sound");
        assertEquals(1, PanelRuntime.of(a).open());

        PanelRuntime.forget(viewer.player().getUniqueId());

        assertEquals(0, PanelRuntime.of(a).open(),
                "a player who left must leave no session behind");
        assertFalse(session.isOpen(), "the released session must know it is over");
        assertTrue(Panels.session(viewer.player()).isEmpty(),
                "and nothing may answer for that player any more");
    }

    @Test
    @DisplayName("releasing one plugin closes its panels and leaves another's untouched")
    void releasingOnePluginLeavesTheOtherAlone() {
        FakePlayer other = new FakePlayer("Alex");
        Session ofA = Session.forTests(PanelRuntime.of(a), viewer.player(), "a");
        Session ofB = Session.forTests(PanelRuntime.of(b), other.player(), "b");

        Panels.release("A");

        assertFalse(ofA.isOpen(), "A's panel must be closed");
        assertTrue(ofB.isOpen(), "B's panel must be untouched by A's disable");
        assertEquals(1, PanelRuntime.of(b).open(), "B's registry must still hold its session");
    }

    @Test
    @DisplayName("a click in a plain chest resolves no panel session")
    void aForeignWindowIsNotOurs() {
        Session.forTests(PanelRuntime.of(a), viewer.player(), "sound");

        // The player has a panel, then opens something that is not one of ours.
        // FakePlayer reports no open inventory, which is exactly the shape of a
        // window whose holder is not a PanelHolder.
        assertTrue(Panels.session(viewer.player()).isEmpty(),
                "a window we did not open must not resolve to a panel session, "
                        + "which is what reading the holder buys over a map keyed by player");
    }

    @Test
    @DisplayName("plugin disable closes the window before its tasks are dropped, cancelling delayed steps")
    void disableClosesWindowsBeforeDroppingTasks() {
        Session session = Session.forTests(PanelRuntime.of(a), viewer.player(), "sound");
        AtomicBoolean ran = new AtomicBoolean();
        session.cancelOnClose(delayedStep(a, viewer.player(), ran));

        assertTrue(FakeServer.liveTasks() >= 1, "the delayed step must be scheduled to begin with");

        // The library's order: panels are released, and only then does the task
        // module drop what the plugin scheduled. Reversed, the cancel below
        // would have nothing left to cancel and the step would already be gone
        // without the session ever knowing.
        Panels.release("A");
        Tasks.release("A");

        assertFalse(session.isOpen());
        assertEquals(0, FakeServer.liveTasks(),
                "no task belonging to a disabled plugin's panel may remain live");

        FakeServer.tick(10);
        assertFalse(ran.get(), "a step cancelled with the panel must never run");
    }

    @Test
    @DisplayName("closing early cancels a delayed step so it never runs")
    void closeCancelsDelayedSteps() {
        Session session = Session.forTests(PanelRuntime.of(a), viewer.player(), "sound");
        AtomicBoolean ran = new AtomicBoolean();
        session.cancelOnClose(delayedStep(a, viewer.player(), ran));

        session.release();

        FakeServer.tick(10);
        assertFalse(ran.get(),
                "the player closed the panel before the delay elapsed, so the step is void");
        assertEquals(0, FakeServer.liveTasks());
    }

    @Test
    @DisplayName("an execution registered after release is cancelled instead of kept")
    void registeringAfterReleaseCancelsImmediately() {
        Session session = Session.forTests(PanelRuntime.of(a), viewer.player(), "sound");
        session.release();

        AtomicBoolean ran = new AtomicBoolean();
        ActionExecution late = delayedStep(a, viewer.player(), ran);
        session.cancelOnClose(late);

        assertTrue(late.isCancelled(),
                "nothing may be attached to a screen that is already gone");
        FakeServer.tick(10);
        assertFalse(ran.get());
    }

    @Test
    @DisplayName("releasing twice is harmless")
    void releaseIsIdempotent() {
        Session session = Session.forTests(PanelRuntime.of(a), viewer.player(), "sound");

        session.release();
        session.release();

        assertFalse(session.isOpen());
        assertEquals(0, PanelRuntime.of(a).open(),
                "a session must not be untracked twice or leave a phantom behind");
    }

    @Test
    @DisplayName("releaseAll empties every plugin's registry")
    void releaseAllEmptiesEverything() {
        Session.forTests(PanelRuntime.of(a), viewer.player(), "a");
        Session.forTests(PanelRuntime.of(b), new FakePlayer("Alex").player(), "b");
        assertTrue(PanelRuntime.registered() >= 2);

        Panels.releaseAll();

        assertEquals(0, PanelRuntime.registered(),
                "a stopping server must leave no panel runtime behind");
    }

    /** A sequence whose only step runs after a delay, so cancelling it is observable. */
    private static ActionExecution delayedStep(Plugin plugin, Player player, AtomicBoolean ran) {
        Actions.of(plugin).registerSync("mark", (context, arguments) -> {
            ran.set(true);
            return net.exylia.lib.action.ActionResult.success();
        });
        ActionStep step = new ActionStep(Actions.of(plugin).compile("mark"), 5);
        return ActionSequence.of(plugin, List.of(step))
                .execute(ActionContext.forPlayer(player).origin("panel").build());
    }
}
