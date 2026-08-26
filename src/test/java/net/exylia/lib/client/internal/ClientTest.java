package net.exylia.lib.client.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.Clients;
import net.exylia.lib.client.Cooldown;
import net.exylia.lib.client.Waypoint;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the module asks a modified client to do.
 *
 * <p>The promises worth testing are the ones a plugin relies on without
 * thinking: a vanilla player is never a special case, a feature the client
 * lacks is silently skipped, what was sent is remembered so it can be put
 * back, and a broken integration cannot take the caller down with it.
 */
class ClientTest {

    private World world;
    private FakePlayer lunar;
    private FakePlayer feather;
    private FakePlayer vanilla;

    private FakeLink lunarLink;
    private FakeLink featherLink;

    private Plugin lobby;
    private Plugin game;
    private Plugin library;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();

        world = FakeServer.newWorld("world");
        lunar = new FakePlayer("Lunar").at(new Location(world, 0, 64, 0));
        feather = new FakePlayer("Feather").at(new Location(world, 0, 64, 0));
        vanilla = new FakePlayer("Vanilla").at(new Location(world, 0, 64, 0));
        FakeServer.online(lunar.player(), feather.player(), vanilla.player());

        lobby = FakeServer.newPlugin("ExyliaLobby");
        game = FakeServer.newPlugin("ExyliaFFA");
        library = FakeServer.newPlugin("ExyliaLib");

        lunarLink = FakeLink.full(ClientBrand.LUNAR).owning(lunar.player());
        featherLink = FakeLink.waypointsOnly(ClientBrand.FEATHER).owning(feather.player());
        ClientRegistry.install(List.of(lunarLink, featherLink));
    }

    @AfterEach
    void tearDown() {
        ClientRuntime.shutdown();
        FakeServer.reset();
    }

    private Waypoint waypoint() {
        return Waypoint.at("Koth", new Location(world, 100, 70, 200));
    }

    /** The name two plugins both have every right to. */
    private Waypoint spawn() {
        return Waypoint.at("spawn", new Location(world, 0, 64, 0));
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("each player is matched to the client they run")
    void detectsClients() {
        assertEquals(ClientBrand.LUNAR, Clients.brandOf(lunar.player()));
        assertEquals(ClientBrand.FEATHER, Clients.brandOf(feather.player()));
        assertEquals(ClientBrand.VANILLA, Clients.brandOf(vanilla.player()));
    }

    @Test
    @DisplayName("a vanilla player is not a special case, just a player who sees nothing")
    void vanillaPlayersAreSilentlySkipped() {
        assertFalse(Clients.waypoints().show(vanilla.player(), waypoint()));
        assertFalse(Clients.cooldowns().show(vanilla.player(), Cooldown.seconds("pearl", 16)));
        Clients.markers().update(vanilla.player(), List.of(lunar.player()));

        assertEquals(List.of(), lunarLink.calls());
        assertEquals(List.of(), featherLink.calls());
    }

    @Test
    @DisplayName("detection happens once and is remembered")
    void detectionIsCached() {
        Clients.brandOf(lunar.player());
        Clients.brandOf(lunar.player());
        Clients.brandOf(lunar.player());

        // recognises() is only consulted while the answer is unknown.
        lunarLink.broken = true;
        assertEquals(ClientBrand.LUNAR, Clients.brandOf(lunar.player()));
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a waypoint reaches the client that can draw it")
    void waypointIsSent() {
        assertTrue(Clients.waypoints().show(lunar.player(), waypoint()));

        assertEquals(List.of("waypoint:Lunar:Koth:world"), lunarLink.calls("waypoint"));
    }

    @Test
    @DisplayName("showing the same name twice moves it rather than duplicating it")
    void showingTwiceReplaces() {
        Clients.waypoints().show(feather.player(), waypoint());
        featherLink.clear();

        Clients.waypoints().show(feather.player(), waypoint());

        assertEquals(List.of("unwaypoint:Feather:Koth:handle-1"), featherLink.calls("unwaypoint"));
        assertEquals(List.of("waypoint:Feather:Koth:world"), featherLink.calls("waypoint"));
    }

    @Test
    @DisplayName("a client with one slot per name is not told to remove what it is about to replace")
    void showingTwiceOnANameKeyedClientOnlySends() {
        Clients.waypoints().show(lunar.player(), waypoint());
        lunarLink.clear();

        Clients.waypoints().show(lunar.player(), waypoint());

        // Sending the removal first would empty the one slot that name has,
        // which on a shared name is somebody else's marker.
        assertEquals(List.of(), lunarLink.calls("unwaypoint"));
        assertEquals(List.of("waypoint:Lunar:Koth:world"), lunarLink.calls("waypoint"));
    }

    @Test
    @DisplayName("removing a waypoint uses the handle the client gave back")
    void removeUsesTheClientHandle() {
        Clients.waypoints().show(feather.player(), waypoint());
        featherLink.clear();

        Clients.waypoints().remove(feather.player(), "Koth");

        assertEquals(List.of("unwaypoint:Feather:Koth:handle-1"), featherLink.calls("unwaypoint"));
    }

    // ------------------------------------------------------------------
    // Two plugins, one name, one slot
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a plugin that is not the one on screen removes nothing from it")
    void theSlotBelongsToWhoeverShowedItLast() {
        Clients.of(lobby).waypoints().show(lunar.player(), spawn());
        Clients.of(game).waypoints().show(lunar.player(), spawn());
        lunarLink.clear();

        // The lobby's marker was replaced by the game's the moment the game
        // showed it. Taking the lobby's down must not empty that slot.
        Clients.of(lobby).waypoints().remove(lunar.player(), "spawn");

        assertEquals(List.of(), lunarLink.calls());
    }

    @Test
    @DisplayName("giving up the slot hands it back to the plugin that still wants the name")
    void theSlotGoesBackToTheOtherPlugin() {
        Clients.of(lobby).waypoints().show(lunar.player(), spawn());
        Clients.of(game).waypoints().show(lunar.player(), spawn());
        lunarLink.clear();

        Clients.of(game).waypoints().clear(lunar.player());

        // Shown, not removed and then shown: one packet, and no tick where the
        // player is looking at an empty minimap.
        assertEquals(List.of("waypoint:Lunar:spawn:world"), lunarLink.calls("waypoint"));
        assertEquals(List.of(), lunarLink.calls("unwaypoint"));
        // And the lobby can still take its own down afterwards.
        lunarLink.clear();
        Clients.of(lobby).waypoints().remove(lunar.player(), "spawn");
        assertEquals(List.of("unwaypoint:Lunar:spawn:spawn"), lunarLink.calls("unwaypoint"));
    }

    @Test
    @DisplayName("the last plugin holding a name does remove it")
    void theLastOneOutTurnsItOff() {
        Clients.of(game).waypoints().show(lunar.player(), spawn());
        lunarLink.clear();

        Clients.of(game).waypoints().remove(lunar.player(), "spawn");

        assertEquals(List.of("unwaypoint:Lunar:spawn:spawn"), lunarLink.calls("unwaypoint"));
    }

    @Test
    @DisplayName("a client that keeps a handle per waypoint keeps both plugins' markers")
    void aHandleKeyedClientHasNoSlotToFightOver() {
        Clients.of(lobby).waypoints().show(feather.player(), spawn());
        Clients.of(game).waypoints().show(feather.player(), spawn());
        featherLink.clear();

        Clients.of(game).waypoints().remove(feather.player(), "spawn");

        // Nothing to hand back: the lobby's was never off the screen.
        assertEquals(List.of("unwaypoint:Feather:spawn:handle-2"), featherLink.calls("unwaypoint"));
        assertEquals(List.of(), featherLink.calls("waypoint"));
    }

    // ------------------------------------------------------------------
    // Durations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a waypoint with a duration comes down when the time is up")
    void durationIsEnforcedForAClientThatWillNot() {
        ClientRuntime.library(library);
        Clients.of(game).waypoints().show(lunar.player(),
                spawn().lasting(Duration.ofSeconds(2)));
        lunarLink.clear();

        // Two seconds is forty ticks, and the fake scheduler counts the delay
        // down before it runs the body, exactly as the real one does.
        FakeServer.tick(40);
        assertEquals(List.of(), lunarLink.calls("unwaypoint"));

        FakeServer.tick(1);
        assertEquals(List.of("unwaypoint:Lunar:spawn:spawn"), lunarLink.calls("unwaypoint"));
    }

    @Test
    @DisplayName("re-showing before the time is up cancels the old expiry")
    void anExpiryOnlyTakesDownItsOwnRegistration() {
        ClientRuntime.library(library);
        Clients.of(game).waypoints().show(lunar.player(),
                spawn().lasting(Duration.ofSeconds(2)));
        FakeServer.tick(20);
        Clients.of(game).waypoints().show(lunar.player(), spawn());
        lunarLink.clear();

        FakeServer.tick(40);

        assertEquals(List.of(), lunarLink.calls("unwaypoint"));
    }

    @Test
    @DisplayName("a waypoint handed back its slot still expires on time")
    void theHeirKeepsItsDuration() {
        ClientRuntime.library(library);
        Clients.of(lobby).waypoints().show(lunar.player(),
                spawn().lasting(Duration.ofSeconds(2)));
        Clients.of(game).waypoints().show(lunar.player(), spawn());
        Clients.of(game).waypoints().remove(lunar.player(), "spawn");
        lunarLink.clear();

        FakeServer.tick(41);

        assertEquals(List.of("unwaypoint:Lunar:spawn:spawn"), lunarLink.calls("unwaypoint"));
    }

    @Test
    @DisplayName("a client that counts the duration down itself is left to do it")
    void noTimerForAClientThatExpiresItsOwn() {
        ClientRuntime.library(library);
        Clients.of(game).waypoints().show(feather.player(),
                spawn().lasting(Duration.ofSeconds(2)));
        featherLink.clear();

        FakeServer.tick(60);

        assertEquals(List.of(), featherLink.calls("unwaypoint"));
    }

    @Test
    @DisplayName("removing a waypoint that was never shown sends nothing")
    void removingUnknownSendsNothing() {
        Clients.waypoints().remove(lunar.player(), "never-shown");

        assertEquals(List.of(), lunarLink.calls());
    }

    @Test
    @DisplayName("a waypoint the client refused is not remembered")
    void refusedWaypointsAreNotRemembered() {
        lunarLink.refuseWaypoints = true;

        assertFalse(Clients.waypoints().show(lunar.player(), waypoint()));
        lunarLink.clear();

        // Nothing to put back, because nothing was ever on screen.
        ClientRuntime.resend(lunar.player(), false);
        assertEquals(List.of(), lunarLink.calls());
    }

    // ------------------------------------------------------------------
    // Re-sending
    // ------------------------------------------------------------------

    @Test
    @DisplayName("what a player had is put back when their client forgets it")
    void waypointsAreResentOnJoin() {
        Clients.waypoints().show(lunar.player(), waypoint());
        lunarLink.clear();

        ClientRuntime.resend(lunar.player(), false);

        assertEquals(List.of("waypoint:Lunar:Koth:world"), lunarLink.calls("waypoint"));
    }

    @Test
    @DisplayName("only clients that forget on world change are sent them again")
    void worldChangeOnlyResendsForClientsThatNeedIt() {
        Clients.waypoints().show(lunar.player(), waypoint());
        Clients.waypoints().show(feather.player(), waypoint());
        lunarLink.clear();
        featherLink.clear();

        ClientRuntime.resend(lunar.player(), true);
        ClientRuntime.resend(feather.player(), true);

        assertEquals(List.of(), lunarLink.calls("waypoint"), "Lunar keeps its waypoints");
        assertEquals(List.of("waypoint:Feather:Koth:world"), featherLink.calls("waypoint"));
    }

    @Test
    @DisplayName("after a world change only waypoints for the new world come back")
    void worldChangeSkipsOtherWorlds() {
        Clients.waypoints().show(feather.player(),
                Waypoint.at("Nether base", 10, 60, 10, "nether"));
        Clients.waypoints().show(feather.player(), waypoint());
        featherLink.clear();

        ClientRuntime.resend(feather.player(), true);

        assertEquals(List.of("waypoint:Feather:Koth:world"), featherLink.calls("waypoint"));
    }

    @Test
    @DisplayName("a player who left is forgotten without being sent anything")
    void leavingForgetsWithoutSending() {
        Clients.waypoints().show(lunar.player(), waypoint());
        lunarLink.clear();

        lunar.disconnect();
        ClientRuntime.forget(lunar.player());
        ClientRuntime.resend(lunar.player(), false);

        assertEquals(List.of(), lunarLink.calls("waypoint"));
    }

    // ------------------------------------------------------------------
    // Restoring after a rejoin
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a plugin is asked what a player should see once their client is ready")
    void restorerIsAskedOnJoin() {
        Plugin homes = FakeServer.newPlugin("Homes", null);
        Clients.of(homes).waypoints().restoreWith(player -> List.of(waypoint()));

        // A rejoin: nothing is remembered, so the plugin's own answer is the
        // only source there is.
        ClientRuntime.forget(lunar.player());
        ClientRuntime.resend(lunar.player(), false);

        assertEquals(List.of("waypoint:Lunar:Koth:world"), lunarLink.calls("waypoint"));
    }

    @Test
    @DisplayName("a world change does not ask again")
    void restorerIsNotAskedOnWorldChange() {
        // Feather, because it is the client that drops waypoints with the world
        // and therefore the one a world change re-sends to at all.
        Plugin homes = FakeServer.newPlugin("Homes", null);
        Clients.of(homes).waypoints().restoreWith(player -> List.of(waypoint()));
        Clients.of(homes).waypoints().show(feather.player(), waypoint());
        featherLink.clear();

        // What was sent is still remembered, so asking the owner too would send
        // the same waypoint a second time.
        ClientRuntime.resend(feather.player(), true);

        assertEquals(List.of("waypoint:Feather:Koth:world"), featherLink.calls("waypoint"));
    }

    @Test
    @DisplayName("one plugin answering badly does not cost another its markers")
    void abrokenRestorerIsIsolated() {
        Plugin broken = FakeServer.newPlugin("Broken", null);
        Plugin homes = FakeServer.newPlugin("Homes", null);
        Clients.of(broken).waypoints().restoreWith(player -> {
            throw new IllegalStateException("no");
        });
        Clients.of(homes).waypoints().restoreWith(player -> List.of(waypoint()));

        ClientRuntime.forget(lunar.player());
        ClientRuntime.resend(lunar.player(), false);

        assertEquals(List.of("waypoint:Lunar:Koth:world"), lunarLink.calls("waypoint"));
    }

    @Test
    @DisplayName("the unowned API can take down what its own restorer put back")
    void aRestoredWaypointKeepsTheOwnerItWasShownUnder() {
        Clients.waypoints().restoreWith(player -> List.of(waypoint()));

        ClientRuntime.forget(lunar.player());
        ClientRuntime.resend(lunar.player(), false);
        lunarLink.clear();

        // The restorer map cannot hold a null key, so the unowned API is filed
        // under "" there. Restoring under that name rather than under nobody
        // left a marker the static remove() could not find.
        Clients.waypoints().remove(lunar.player(), "Koth");

        assertEquals(List.of("unwaypoint:Lunar:Koth:Koth"), lunarLink.calls("unwaypoint"));
    }

    @Test
    @DisplayName("a plugin that goes away stops being asked")
    void releasingForgetsTheRestorer() {
        Plugin homes = FakeServer.newPlugin("Homes", null);
        Clients.of(homes).waypoints().restoreWith(player -> List.of(waypoint()));

        ClientRuntime.release("Homes");
        ClientRuntime.forget(lunar.player());
        ClientRuntime.resend(lunar.player(), false);

        assertEquals(List.of(), lunarLink.calls("waypoint"));
    }

    @Test
    @DisplayName("showing waypoints from many threads at once loses none of them")
    void waypointsSurviveConcurrentShows() throws Exception {
        int threads = 8;
        int each = 50;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> running = new java.util.ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            int mine = thread;
            running.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    Clients.of(game).waypoints().show(lunar.player(),
                            Waypoint.at("point-" + mine + "-" + i, new Location(world, i, 64, 0)));
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> task : running) {
            task.get();
        }
        pool.shutdownNow();

        // A plain HashMap under the player would drop entries here, or spin.
        assertEquals(threads * each, lunarLink.calls("waypoint").size());
        assertEquals(threads * each, ClientState.waypointsOf(lunar.player().getUniqueId()).size());
    }

    // ------------------------------------------------------------------
    // Cooldowns and markers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cooldown reaches a client that draws them")
    void cooldownIsSent() {
        assertTrue(Clients.cooldowns().show(lunar.player(),
                Cooldown.of("pearl", Duration.ofSeconds(16))));

        assertEquals(List.of("cooldown:Lunar:pearl:16000"), lunarLink.calls("cooldown"));
    }

    @Test
    @DisplayName("a client that cannot draw cooldowns is skipped, waypoints still work")
    void featureSupportIsPerClient() {
        assertFalse(Clients.cooldowns().show(feather.player(), Cooldown.seconds("pearl", 16)));
        assertTrue(Clients.waypoints().show(feather.player(), waypoint()));

        assertEquals(List.of(), featherLink.calls("cooldown"));
        assertEquals(1, featherLink.calls("waypoint").size());
    }

    @Test
    @DisplayName("markers are sent as a whole team, and never include the viewer")
    void markersExcludeTheViewer() {
        Clients.markers().update(lunar.player(), List.of(lunar.player(), vanilla.player()));

        assertEquals(List.of("markers:Lunar:Vanilla"), lunarLink.calls("markers"));
    }

    @Test
    @DisplayName("a whole team sees each other")
    void updateTeamSendsToEveryMember() {
        FakePlayer second = new FakePlayer("Lunar2").at(new Location(world, 0, 64, 0));
        lunarLink.owning(second.player());
        FakeServer.online(lunar.player(), second.player());

        Clients.markers().updateTeam(List.of(lunar.player(), second.player()));

        assertEquals(List.of("markers:Lunar:Lunar2", "markers:Lunar2:Lunar"),
                lunarLink.calls("markers"));
    }

    @Test
    @DisplayName("clearing takes down everything the library sent")
    void clearRemovesEverything() {
        Clients.waypoints().show(lunar.player(), waypoint());
        Clients.cooldowns().show(lunar.player(), Cooldown.seconds("pearl", 16));
        Clients.markers().update(lunar.player(), List.of(vanilla.player()));
        lunarLink.clear();

        Clients.clear(lunar.player());

        assertEquals(1, lunarLink.calls("clearwaypoints").size());
        assertEquals(1, lunarLink.calls("clearcooldowns").size());
        assertEquals(1, lunarLink.calls("clearmarkers").size());
    }

    // ------------------------------------------------------------------
    // Failure is contained
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an integration that throws does not reach the caller")
    void brokenIntegrationsAreContained() {
        Clients.waypoints().show(lunar.player(), waypoint());
        lunarLink.broken = true;

        // None of these may throw: the plugin asking for a waypoint did nothing
        // wrong, and a broken client integration is somebody else's bug.
        Clients.waypoints().remove(lunar.player(), "Koth");
        Clients.cooldowns().remove(lunar.player(), "pearl");
        Clients.markers().clear(lunar.player());
        Clients.clear(lunar.player());
    }

    @Test
    @DisplayName("with no integrations installed everything still works and sends nothing")
    void noIntegrationsIsNotAnError() {
        ClientRegistry.clear();

        assertFalse(Clients.isSupported());
        assertEquals(ClientBrand.VANILLA, Clients.brandOf(lunar.player()));
        assertFalse(Clients.waypoints().show(lunar.player(), waypoint()));
        assertFalse(Clients.cooldowns().show(lunar.player(), Cooldown.seconds("x", 1)));
        Clients.markers().update(lunar.player(), List.of(vanilla.player()));
        Clients.clear(lunar.player());
    }
}
