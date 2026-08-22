package net.exylia.lib.region;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.region.internal.RegionRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player's membership does as they move and as regions change under
 * them.
 *
 * <p>This is the part that fires events, and the failure modes are the ones
 * that made the old system unreliable: an enter that never arrives, an exit
 * fired twice, membership that goes stale while somebody stands still, and one
 * plugin's reload wiping another plugin's regions.
 */
class RegionRuntimeTest {

    private World world;
    private World otherWorld;
    private Plugin practice;
    private Plugin survival;
    private PluginRegions regions;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Regions.releaseAll();

        world = FakeServer.newWorld("arena");
        otherWorld = FakeServer.newWorld("survival");
        practice = FakeServer.newPlugin("Practice", null);
        survival = FakeServer.newPlugin("Survival", null);
        RegionRuntime.init(practice);
        regions = Regions.of(practice);
    }

    /** A ten-block cube at the origin. */
    private RegionSnapshot cube(String key, int priority) {
        return regions.region(key, WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), priority, PolicySet.empty());
    }

    private FakePlayer playerAt(double x, double y, double z) {
        FakePlayer player = new FakePlayer("Tester");
        player.at(new Location(world, x, y, z));
        FakeServer.online(player.player());
        return player;
    }

    private void moveTo(FakePlayer player, World destination, double x, double y, double z) {
        player.at(new Location(destination, x, y, z));
        RegionRuntime.move(player.player(), destination.getUID(), destination.getName(),
                x, y, z, RegionChangeCause.MOVE);
    }

    // ------------------------------------------------------------ membership

    @Test
    @DisplayName("walking in and out fires one enter and then one exit")
    void enterAndExit() {
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(100, 5, 100);
        RegionRuntime.initialize(player.player());

        moveTo(player, world, 5, 5, 5);
        List<PlayerRegionChangeEvent> afterEntering =
                FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, afterEntering.size(), "one event for entering");
        assertEquals(1, afterEntering.getFirst().entered().size());
        assertTrue(afterEntering.getFirst().exited().isEmpty());

        moveTo(player, world, 100, 5, 100);
        List<PlayerRegionChangeEvent> afterLeaving =
                FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(2, afterLeaving.size(), "one more for leaving");
        assertEquals(1, afterLeaving.get(1).exited().size());
        assertTrue(afterLeaving.get(1).entered().isEmpty());
    }

    @Test
    @DisplayName("moving inside the same region says nothing")
    void movingInsideIsSilent() {
        // Otherwise every step inside a spawn region would be an event, and
        // listeners would be doing work on every move packet.
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(1, 1, 1);
        RegionRuntime.initialize(player.player());

        moveTo(player, world, 2, 2, 2);
        moveTo(player, world, 3, 3, 3);

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty());
    }

    @Test
    @DisplayName("joining inside a region does not manufacture an enter")
    void joiningIsNotEntering() {
        // They did not walk in; they were already there. A synthetic enter on
        // every join would double every reward that listens for one.
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(5, 5, 5);

        RegionRuntime.initialize(player.player());

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty());
    }

    @Test
    @DisplayName("one step can leave one region and enter another")
    void severalRegionsChangeAtOnce() {
        regions.register(cube("first", 0));
        regions.register(regions.region("second", WorldIdentity.from(world),
                Cuboid.blocks(20, 0, 20, 29, 9, 29), 0, PolicySet.empty()));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        moveTo(player, world, 25, 5, 25);

        PlayerRegionChangeEvent event =
                FakeServer.events(PlayerRegionChangeEvent.class).getFirst();
        assertEquals(1, event.exited().size(), "left the first");
        assertEquals(1, event.entered().size(), "entered the second");
        assertEquals(regions.id("first"), event.exited().getFirst().id());
        assertEquals(regions.id("second"), event.entered().getFirst().id());
    }

    @Test
    @DisplayName("overlapping regions are reported highest priority first")
    void overlapOrder() {
        regions.register(cube("low", 0));
        regions.register(regions.region("high", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 10, PolicySet.empty()));
        FakePlayer player = playerAt(100, 5, 100);
        RegionRuntime.initialize(player.player());

        moveTo(player, world, 5, 5, 5);

        List<RegionSnapshot> entered =
                FakeServer.events(PlayerRegionChangeEvent.class).getFirst().entered();
        assertEquals(2, entered.size());
        assertEquals(regions.id("high"), entered.getFirst().id(), "priority decides");
    }

    @Test
    @DisplayName("the same coordinates in another world are not the same place")
    void worldsAreNotInterchangeable() {
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        moveTo(player, otherWorld, 5, 5, 5);

        PlayerRegionChangeEvent event =
                FakeServer.events(PlayerRegionChangeEvent.class).getFirst();
        assertEquals(1, event.exited().size(), "same numbers, different world");
        assertTrue(event.entered().isEmpty());
    }

    // ------------------------------------------------- changing under a player

    @Test
    @DisplayName("walking before any region exists still reports where you came from")
    void movementBeforeAnyRegionExistsStillTracksPosition() {
        // A server whose plugins have not registered anything yet can skip the
        // region lookup entirely, but not the position: the first event after a
        // region appears carries previous(), and reporting the spot the player
        // stood on at join rather than the one they walked from is a silent lie
        // about where they came from.
        FakePlayer player = playerAt(100, 5, 100);
        RegionRuntime.initialize(player.player());

        moveTo(player, world, 60, 5, 60);
        moveTo(player, world, 11, 5, 11);
        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty(),
                "nothing is registered, so nothing can be entered");

        regions.register(cube("spawn", 0));
        moveTo(player, world, 5, 5, 5);

        List<PlayerRegionChangeEvent> events = FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, events.size(), "entering the new region");
        BlockPosition previous = events.getFirst().previous().orElseThrow();
        assertEquals(11, previous.x(), "previous() is the last block walked, not the join spot");
        assertEquals(11, previous.z(), "previous() is the last block walked, not the join spot");
    }

    @Test
    @DisplayName("a region registered around a standing player enters them")
    void registeringUnderneathAPlayer() {
        // The old system only looked when somebody moved, so a region created
        // around a stationary player did nothing until they walked. That is
        // exactly the case an admin tests first.
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        regions.register(cube("spawn", 0));
        FakeServer.tick(1);

        List<PlayerRegionChangeEvent> events =
                FakeServer.events(PlayerRegionChangeEvent.class);
        assertEquals(1, events.size(), "the player is now inside something");
        assertEquals(RegionChangeCause.REGISTER, events.getFirst().cause());
        assertEquals(1, events.getFirst().entered().size());
    }

    @Test
    @DisplayName("deleting a region a player is standing in exits them")
    void unregisteringUnderneathAPlayer() {
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        regions.unregister("spawn");
        FakeServer.tick(1);

        PlayerRegionChangeEvent event =
                FakeServer.events(PlayerRegionChangeEvent.class).getFirst();
        assertEquals(RegionChangeCause.UNREGISTER, event.cause());
        assertEquals(1, event.exited().size(), "the region they were in is gone");
        assertEquals(regions.id("spawn"), event.exited().getFirst().id(),
                "the exited snapshot must be the one they were actually in");
    }

    @Test
    @DisplayName("a reload that keeps a player inside says nothing")
    void reloadWithoutMembershipChangeIsSilent() {
        // A database reload replaces every region. If that fired an exit and an
        // enter for everybody, every reload would re-trigger every join reward
        // on the server.
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        regions.replaceAll(List.of(cube("spawn", 0)));
        FakeServer.tick(1);

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty(),
                "they never left the region");
    }

    @Test
    @DisplayName("a reload that moves a region under a player exits them")
    void reloadThatChangesMembership() {
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        regions.replaceAll(List.of(regions.region("spawn", WorldIdentity.from(world),
                Cuboid.blocks(500, 0, 500, 509, 9, 509), 0, PolicySet.empty())));
        FakeServer.tick(1);

        PlayerRegionChangeEvent event =
                FakeServer.events(PlayerRegionChangeEvent.class).getFirst();
        assertEquals(1, event.exited().size(), "the region moved away from them");
    }

    // --------------------------------------------------------------- ownership

    @Test
    @DisplayName("a listener sees only its own plugin's regions in the event")
    void eventIsFilteredByOwner() {
        // The lists carry the whole server's movement. A game that reads them
        // unfiltered eliminates a player for walking out of somebody else's
        // claim, which is the failure this filter exists to stop.
        PluginRegions survivalRegions = Regions.of(survival);
        survivalRegions.register(survivalRegions.region("claim", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 0, PolicySet.empty()));
        FakePlayer player = playerAt(100, 5, 100);
        RegionRuntime.initialize(player.player());

        moveTo(player, world, 5, 5, 5);

        PlayerRegionChangeEvent event =
                FakeServer.events(PlayerRegionChangeEvent.class).getFirst();
        assertEquals(1, event.entered().size(), "the raw list has the other plugin's region");
        assertTrue(event.entered(regions).isEmpty(), "but this plugin entered none of its own");
        assertFalse(event.involves(regions), "so it has nothing to do");
        assertEquals(1, event.entered(survivalRegions).size(), "the owner sees its own");
        assertTrue(event.involves(survivalRegions));
    }

    @Test
    @DisplayName("one step across two plugins' borders splits by owner")
    void eventSplitsBetweenOwners() {
        PluginRegions survivalRegions = Regions.of(survival);
        regions.register(cube("arena", 0));
        survivalRegions.register(survivalRegions.region("claim", WorldIdentity.from(world),
                Cuboid.blocks(20, 0, 20, 29, 9, 29), 0, PolicySet.empty()));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        moveTo(player, world, 25, 5, 25);

        PlayerRegionChangeEvent event =
                FakeServer.events(PlayerRegionChangeEvent.class).getFirst();
        assertEquals(1, event.exited(regions).size(), "it left its own arena");
        assertTrue(event.entered(regions).isEmpty(), "and entered none of its own");
        assertEquals(1, event.entered(survivalRegions).size(), "the other plugin was entered");
        assertTrue(event.exited(survivalRegions).isEmpty());
    }

    @Test
    @DisplayName("one plugin's reload leaves another plugin's regions alone")
    void replaceAllIsOwnerScoped() {
        // Commons had one global registry and a reload emptied it, so reloading
        // one plugin turned off protection everywhere.
        PluginRegions survivalRegions = Regions.of(survival);
        regions.register(cube("arena", 0));
        survivalRegions.register(survivalRegions.region("claim", WorldIdentity.from(world),
                Cuboid.blocks(50, 0, 50, 59, 9, 59), 0, PolicySet.empty()));

        regions.replaceAll(List.of());

        assertTrue(regions.all().isEmpty(), "its own regions are gone");
        assertEquals(1, survivalRegions.all().size(), "the other plugin's are not");
    }

    @Test
    @DisplayName("disabling a plugin releases exactly its own regions")
    void releaseIsExact() {
        PluginRegions survivalRegions = Regions.of(survival);
        regions.register(cube("arena", 0));
        survivalRegions.register(survivalRegions.region("claim", WorldIdentity.from(world),
                Cuboid.blocks(50, 0, 50, 59, 9, 59), 0, PolicySet.empty()));

        assertEquals(1, Regions.release("Practice"));

        assertTrue(regions.all().isEmpty());
        assertEquals(1, survivalRegions.all().size());
    }

    @Test
    @DisplayName("a plugin cannot touch another plugin's region")
    void ownershipIsEnforced() {
        PluginRegions survivalRegions = Regions.of(survival);
        regions.register(cube("arena", 0));

        // Same id, different owner: the facade must not let it through.
        assertThrows(IllegalArgumentException.class,
                () -> survivalRegions.unregister(regions.id("arena")));
    }

    @Test
    @DisplayName("registering the same id twice is refused")
    void duplicateIdsAreRefused() {
        regions.register(cube("spawn", 0));

        assertThrows(IllegalStateException.class, () -> regions.register(cube("spawn", 0)));
    }

    @Test
    @DisplayName("a failed change leaves the previous regions untouched")
    void failureDoesNotPublish() {
        // The index is built off to the side and swapped in once. A rejected
        // build must not leave the server with half a registry.
        regions.register(cube("spawn", 0));

        assertThrows(RuntimeException.class, () -> regions.replaceAll(List.of(
                cube("a", 0), cube("a", 0))));

        assertEquals(1, regions.all().size(), "the old registry survived");
        assertEquals(regions.id("spawn"), regions.all().getFirst().id());
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("a player who leaves is forgotten, without a parting exit")
    void quitForgets() {
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        RegionRuntime.forget(player.player().getUniqueId());
        player.disconnect();
        FakeServer.online();

        regions.unregister("spawn");
        FakeServer.tick(1);

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty(),
                "nothing should be tracked for somebody who left");
    }

    @Test
    @DisplayName("membership survives standing still, however long")
    void membershipDoesNotExpire() {
        // Commons evicted a tracker after ten minutes of not moving, so an
        // away player was silently re-entered and every enter effect fired
        // again the moment they twitched.
        regions.register(cube("spawn", 0));
        FakePlayer player = playerAt(5, 5, 5);
        RegionRuntime.initialize(player.player());

        FakeServer.tick(20 * 60 * 30);
        moveTo(player, world, 6, 5, 6);

        assertTrue(FakeServer.events(PlayerRegionChangeEvent.class).isEmpty(),
                "they never left, so nothing happened");
    }

    @Test
    @DisplayName("releasing everything empties the registry")
    void releaseAllEmpties() {
        regions.register(cube("spawn", 0));
        Regions.of(survival).register(Regions.of(survival)
                .region("claim", WorldIdentity.from(world),
                        Cuboid.blocks(50, 0, 50, 59, 9, 59), 0, PolicySet.empty()));

        Regions.releaseAll();

        assertEquals(0, Regions.registered());
    }

    // --------------------------------------------------------------- queries

    @Test
    @DisplayName("a policy is answered by the highest region that states it")
    void policyResolution() {
        PolicyKey<Boolean> pvp = CommonRegionPolicies.PVP;
        regions.register(regions.region("outer", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 99, 99, 99), 0, PolicySet.of(pvp, false)));
        // Higher priority, and silent about pvp: it must not mask the one below.
        regions.register(regions.region("inner", WorldIdentity.from(world),
                Cuboid.blocks(0, 0, 0, 9, 9, 9), 10, PolicySet.empty()));

        PolicyResolution<Boolean> resolved =
                Regions.resolve(world.getUID(), 5, 5, 5, pvp);

        assertFalse(resolved.value(), "the outer region's answer stands");
    }

    @Test
    @DisplayName("nowhere in particular gets the policy's own default")
    void policyDefault() {
        PolicyResolution<Boolean> resolved =
                Regions.resolve(UUID.randomUUID(), 0, 0, 0, CommonRegionPolicies.PVP);

        assertTrue(resolved.value(), "pvp is allowed unless a region says otherwise");
    }
}
