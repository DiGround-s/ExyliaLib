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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things a {@link Setup} makes impossible to get wrong.
 *
 * <p>Each of these is a divergence that actually shipped: a spawn set by
 * clicking a block and so facing nowhere, four plugins each inventing a title
 * dialect, and a cancel that reopened nothing because the way back was passed
 * as {@code null}.
 */
class SetupTest {

    private WizardHarness harness;
    private World world;

    @BeforeEach
    void setUp() {
        harness = WizardHarness.start("Practice", "DiGround");
        world = FakeServer.newWorld("lobby");
        FakeServer.worlds(world);
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    @DisplayName("a spawn answers with a facing, which is why it is not a block pick")
    void spawnKeepsFacing() {
        harness.player().teleport(new Location(world, 8.5, 65, 12.5, 90f, 0f));

        AtomicReference<Location> answered = new AtomicReference<>();
        harness.wizards().setup(harness.player(), () -> { })
                .spawn("LOBBY SPAWN", answered::set);
        harness.settle();

        // Sneak and click: the standing gesture. A plain click must not answer
        // it, which is the whole difference between spawn() and block().
        assertTrue(WizardPeek.interact(harness.player().getUniqueId(), null, true),
                "sneak and click must answer a spawn");
        harness.settle();

        assertEquals(90f, answered.get().getYaw(),
                "a spawn is somewhere a player is put, so it must carry a facing");
    }

    @Test
    @DisplayName("the title is built from a plain name, with the context after it")
    void titleIsBuilt() {
        assertEquals("{primary}&lLOBBY SPAWN", Setup.title("Lobby Spawn", null));
        assertEquals("{primary}&lPORTAL RETURN {muted}nether_1",
                Setup.title("portal return", "nether_1"));
        assertEquals("{primary}&lARENA BOUNDS", Setup.title("ARENA BOUNDS", "  "));
    }

    @Test
    @DisplayName("a name carrying its own colours is refused, not rendered")
    void formattingIsRefused() {
        assertThrows(WizardException.class, () -> Setup.title("{warning}⚡ Lobby Spawn", null));
        assertThrows(WizardException.class, () -> Setup.title("&cLOBBY SPAWN", null));
        assertThrows(WizardException.class, () -> Setup.title("LOBBY SPAWN", "{muted}arena_1"));
        assertThrows(WizardException.class, () -> Setup.title("  ", null));
    }

    @Test
    @DisplayName("backing out reopens the menu the setup was obtained with")
    void cancelGoesBack() {
        AtomicBoolean reopened = new AtomicBoolean();
        WizardRun run = harness.wizards().setup(harness.player(), () -> reopened.set(true))
                .spawn("LOBBY SPAWN", "arena_1", where -> { });
        harness.settle();

        run.cancel();
        harness.settle(3);

        assertTrue(reopened.get(), "a cancelled setup must land back in the menu it came from");
    }
}
