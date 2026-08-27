package net.exylia.lib.region.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.PlayerRegionChangeEvent;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.PolicySet;
import net.exylia.lib.region.RegionChangeCause;
import net.exylia.lib.region.Regions;
import net.exylia.lib.region.WorldIdentity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which platform events reach the membership tracker.
 *
 * <p>{@link PlayerTeleportEvent} extends {@link PlayerMoveEvent} but declares
 * its own handler list, so a listener registered for moves alone never sees a
 * teleport. That is not visible by reading either class, and the symptom is a
 * player who arrives somewhere and is treated as still being where they left.
 */
class RegionListenerTest {

    private World world;
    private World otherWorld;
    private PluginRegions regions;
    private RegionListener listener;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();

        world = FakeServer.newWorld("arena");
        otherWorld = FakeServer.newWorld("survival");
        Plugin plugin = FakeServer.newPlugin("Survival", null);
        RegionRuntime.init(plugin);
        regions = Regions.of(plugin);
        regions.register(regions.region("spawn", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty()));
        listener = new RegionListener();
    }

    private FakePlayer playerAt(World home, double x, double y, double z) {
        FakePlayer player = new FakePlayer("Tester");
        player.at(new Location(home, x, y, z));
        FakeServer.online(player.player());
        RegionRuntime.initialize(player.player());
        return player;
    }

    private static PlayerTeleportEvent teleport(FakePlayer player, Location to) {
        return new PlayerTeleportEvent(player.player(), player.player().getLocation(), to);
    }

    @Test
    @DisplayName("teleporting into a region enters it without a step")
    void teleportEnters() {
        FakePlayer player = playerAt(world, 100, 5, 100);

        listener.onTeleport(teleport(player, new Location(world, 5, 5, 5)));

        List<PlayerRegionChangeEvent> fired = FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, fired.size(), "the teleport is a region change");
        assertEquals(regions.id("spawn"), fired.getFirst().entered().getFirst().id());
        assertEquals(RegionChangeCause.TELEPORT, fired.getFirst().cause());
    }

    @Test
    @DisplayName("teleporting out of a region exits it without a step")
    void teleportExits() {
        FakePlayer player = playerAt(world, 5, 5, 5);

        listener.onTeleport(teleport(player, new Location(world, 100, 5, 100)));

        List<PlayerRegionChangeEvent> fired = FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, fired.size(), "the teleport is a region change");
        assertEquals(regions.id("spawn"), fired.getFirst().exited().getFirst().id());
        assertTrue(fired.getFirst().entered().isEmpty());
    }

    @Test
    @DisplayName("a teleport to another world leaves the region behind")
    void teleportAcrossWorldsExits() {
        FakePlayer player = playerAt(world, 5, 5, 5);

        listener.onTeleport(teleport(player, new Location(otherWorld, 5, 5, 5)));

        List<PlayerRegionChangeEvent> fired = FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, fired.size());
        assertEquals(regions.id("spawn"), fired.getFirst().exited().getFirst().id());
    }

    @Test
    @DisplayName("respawning somewhere else leaves the region the player died in")
    void respawnExits() {
        FakePlayer player = playerAt(world, 5, 5, 5);

        listener.onRespawn(new PlayerRespawnEvent(player.player(),
                new Location(world, 100, 5, 100), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH,
                com.google.common.collect.ImmutableSet.builder()));

        List<PlayerRegionChangeEvent> fired = FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, fired.size());
        assertEquals(regions.id("spawn"), fired.getFirst().exited().getFirst().id());
    }

    @Test
    @DisplayName("a move nothing announced is caught by the poll")
    void pollCatchesASilentMove() {
        // Folia does not put every teleport through PlayerTeleportEvent, and a
        // player moved by packet produces none at all. Without the poll they
        // stand in a region the server says they left.
        FakePlayer player = playerAt(world, 100, 5, 100);

        player.at(new Location(world, 5, 5, 5));
        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty(), "nothing was fired");

        FakeServer.tick(6);

        List<PlayerRegionChangeEvent> fired = FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, fired.size(), "the poll found them inside");
        assertEquals(regions.id("spawn"), fired.getFirst().entered().getFirst().id());
        assertEquals(RegionChangeCause.SYNC, fired.getFirst().cause());
    }

    @Test
    @DisplayName("the poll says nothing while the player stands still")
    void pollIsSilentWhenNothingMoved() {
        playerAt(world, 5, 5, 5);

        FakeServer.tick(40);

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty());
    }

    @Test
    @DisplayName("quitting stops the poll")
    void quitStopsThePoll() {
        FakePlayer player = playerAt(world, 5, 5, 5);

        listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player.player(),
                (net.kyori.adventure.text.Component) null,
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        player.at(new Location(world, 100, 5, 100));
        FakeServer.tick(20);

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty(),
                "a player who left the server is not walked out of anything");
    }

    @Test
    @DisplayName("a teleport that does not leave the block says nothing")
    void teleportInsideTheBlockIsSilent() {
        FakePlayer player = playerAt(world, 5.1, 5, 5.1);

        listener.onTeleport(teleport(player, new Location(world, 5.9, 5, 5.9)));

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty());
    }
}
