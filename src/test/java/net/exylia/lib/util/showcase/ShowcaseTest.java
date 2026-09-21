package net.exylia.lib.util.showcase;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.util.showcase.internal.LiveStage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a showcase promises: nothing plays to nobody, a turn waits for the last
 * one and its rest, the same cosmetic never plays twice in a row, and whatever a
 * turn put on screen goes away when the next arrives or the showcase stops.
 */
class ShowcaseTest {

    private World world;
    private Location spot;
    private FakePlayer near;
    private FakePlayer far;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("lobby");
        FakeServer.worlds(world);
        spot = new Location(world, 0, 64, 0);
        near = new FakePlayer("Near").at(new Location(world, 3, 64, 0));
        far = new FakePlayer("Far").at(new Location(world, 100, 64, 0));
    }

    private LiveStage stage(ShowcaseSettings settings, ShowcaseAct act) {
        return new LiveStage(spot, () -> settings, () -> player -> true, act);
    }

    @Test
    @DisplayName("Nobody within the radius: no turn starts")
    void nobodyWatching() {
        FakeServer.online(far.player());
        AtomicInteger turns = new AtomicInteger();
        LiveStage stage = stage(new ShowcaseSettings(), s -> {
            turns.incrementAndGet();
            return ShowcaseTurn.of(1000);
        });

        assertFalse(stage.tick(0));
        assertEquals(0, turns.get());
    }

    @Test
    @DisplayName("Only the players within the radius watch")
    void onlyNearbyWatch() {
        FakeServer.online(near.player(), far.player());
        AtomicReference<List<Player>> cast = new AtomicReference<>();
        stage(new ShowcaseSettings(), s -> {
            cast.set(s.watching());
            return ShowcaseTurn.of(1000);
        }).tick(0);

        assertEquals(List.of(near.player()), cast.get());
    }

    @Test
    @DisplayName("A player who may not see showcases is neither cast nor counted")
    void hiddenPlayersDoNotWatch() {
        FakeServer.online(near.player());
        LiveStage stage = new LiveStage(spot, ShowcaseSettings::new, () -> player -> false,
                s -> ShowcaseTurn.of(1000));

        assertFalse(stage.tick(0));
    }

    @Test
    @DisplayName("The next turn waits for this one and its rest, then takes this one away")
    void turnsWaitAndReplace() {
        FakeServer.online(near.player());
        List<AtomicInteger> cancels = new ArrayList<>();
        LiveStage stage = stage(new ShowcaseSettings(List.of(), 2, 24, List.of()), s -> {
            AtomicInteger cancelled = new AtomicInteger();
            cancels.add(cancelled);
            return ShowcaseTurn.of(1000, cancelled::incrementAndGet);
        });

        assertTrue(stage.tick(0));
        assertFalse(stage.tick(2999), "still inside its turn and rest");
        assertEquals(0, cancels.get(0).get(), "left standing through the rest");
        assertTrue(stage.tick(3000));
        assertEquals(1, cancels.get(0).get(), "taken away when the next arrives");
        assertEquals(0, cancels.get(1).get());
    }

    @Test
    @DisplayName("An act with nothing to show is asked again, and keeps what was on screen")
    void nothingToShow() {
        FakeServer.online(near.player());
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        LiveStage stage = stage(new ShowcaseSettings(List.of(), 0, 24, List.of()), s ->
                calls.incrementAndGet() == 1 ? ShowcaseTurn.of(0, cancelled::incrementAndGet) : null);

        assertTrue(stage.tick(0));
        assertFalse(stage.tick(1));
        assertFalse(stage.tick(2));
        assertEquals(3, calls.get());
        assertEquals(0, cancelled.get());
    }

    @Test
    @DisplayName("Stopping takes the current turn away, once, and nothing starts after")
    void stopCancels() {
        FakeServer.online(near.player());
        AtomicInteger cancelled = new AtomicInteger();
        LiveStage stage = stage(new ShowcaseSettings(), s -> ShowcaseTurn.of(0, cancelled::incrementAndGet));

        stage.tick(0);
        stage.stop();
        stage.stop();

        assertEquals(1, cancelled.get());
        assertFalse(stage.tick(10_000));
    }

    @Test
    @DisplayName("Never the same cosmetic twice in a row, while there is another")
    void neverTwiceInARow() {
        FakeServer.online(near.player());
        List<String> played = new ArrayList<>();
        LiveStage stage = stage(new ShowcaseSettings(List.of(), 0, 24, List.of()), s -> {
            played.add(s.pick(List.of("a", "b"), id -> id));
            return ShowcaseTurn.of(0);
        });

        for (int turn = 0; turn < 40; turn++) {
            stage.tick(turn * 10L);
        }
        for (int i = 1; i < played.size(); i++) {
            assertNotEquals(played.get(i - 1), played.get(i));
        }
    }

    @Test
    @DisplayName("Picks only what the settings name, ignoring case, and nothing from an empty pool")
    void picksOnly() {
        FakeServer.online(near.player());
        AtomicReference<String> picked = new AtomicReference<>("unset");
        stage(new ShowcaseSettings(List.of(), 0, 24, List.of("Shogun")), s -> {
            picked.set(s.pick(List.of("shogun", "sentry", "wild"), id -> id));
            return ShowcaseTurn.of(0);
        }).tick(0);
        assertEquals("shogun", picked.get());

        stage(new ShowcaseSettings(List.of(), 0, 24, List.of("missing")), s -> {
            picked.set(s.pick(List.of("shogun"), id -> id));
            return null;
        }).tick(0);
        assertNull(picked.get());
    }

    @Test
    @DisplayName("A second body is somebody else when two are watching, the same one alone")
    void castsAPair() {
        FakePlayer other = new FakePlayer("Other").at(new Location(world, -2, 64, 0));
        FakeServer.online(near.player(), other.player());
        AtomicReference<Player> first = new AtomicReference<>();
        AtomicReference<Player> second = new AtomicReference<>();
        stage(new ShowcaseSettings(), s -> {
            first.set(s.someone());
            second.set(s.someoneBut(first.get()));
            return ShowcaseTurn.of(0);
        }).tick(0);
        assertNotEquals(first.get(), second.get());

        FakeServer.online(near.player());
        stage(new ShowcaseSettings(), s -> {
            first.set(s.someone());
            second.set(s.someoneBut(first.get()));
            return ShowcaseTurn.of(0);
        }).tick(0);
        assertEquals(first.get(), second.get());
    }

    @Test
    @DisplayName("Adding, removing and clearing write the list and restart the loops")
    void placesAreSaved() {
        Plugin plugin = FakeServer.newPlugin("Lobby");
        AtomicReference<ShowcaseSettings> settings = new AtomicReference<>(new ShowcaseSettings());
        PluginShowcases showcases = Showcases.of(plugin).start(settings::get,
                placed -> settings.set(settings.get().withLocations(placed)),
                s -> ShowcaseTurn.of(0));

        showcases.add(spot);
        showcases.add(new Location(world, 50, 64, 50));
        assertEquals(2, settings.get().locations().size());
        assertEquals(2, showcases.active());

        assertNull(showcases.removeNear(new Location(world, 20, 64, 20)), "nothing within reach");
        Location removed = showcases.removeNear(new Location(world, 1, 64, 1));
        assertNotNull(removed);
        assertEquals(0, removed.getBlockX());
        assertEquals(1, showcases.active());

        assertEquals(1, showcases.clear());
        assertEquals(0, showcases.active());
        assertTrue(settings.get().locations().isEmpty());

        showcases.add(spot);
        Showcases.release("Lobby");
        assertEquals(0, showcases.active());
    }

    @Test
    @DisplayName("Settings clamp what cannot be meant and skip entries that are not places")
    void settingsClamp() {
        ShowcaseSettings settings = new ShowcaseSettings(
                List.of("lobby,0,64,0,0,0", "not a place"), -5, 0, null);
        assertEquals(0, settings.pauseSeconds());
        assertEquals(1, settings.radius());
        assertTrue(settings.only().isEmpty());
        assertEquals(2, settings.locations().size());
    }
}
