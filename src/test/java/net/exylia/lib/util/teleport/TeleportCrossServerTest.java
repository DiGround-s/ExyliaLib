package net.exylia.lib.util.teleport;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.redis.internal.MemoryClient;
import net.exylia.lib.redis.internal.RedisClient;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handing a player to another server, and picking one up from one.
 *
 * <p>The Redis here is a real map holding the real key under the real prefix,
 * so what these assert is the format and the <em>ordering</em> rather than that
 * a mock was called. The ordering is the whole contract: a {@code Connect} sent
 * before the destination is stored is a player standing on the other server
 * while the only thing that knew where to put them has not been written yet.
 *
 * <p>The one thing simulated is the wire, which is the part {@code JedisClient}
 * confines to itself.
 */
class TeleportCrossServerTest {

    private static final AtomicInteger DATABASE = new AtomicInteger();

    private Plugin plugin;
    private World world;
    private FakePlayer player;
    private PluginTeleports teleports;
    private MemoryClient redis;

    /** Everything the module did to Redis, in the order it did it. */
    private final List<String> journal = new ArrayList<>();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Practice");
        world = FakeServer.newWorld("lobby");
        FakeServer.worlds(world);

        player = new FakePlayer("DiGround");
        player.at(new Location(world, 10, 64, 10));
        FakeServer.online(player.player());

        DatabaseRuntime.installForTests(plugin,
                SqlSettings.memory("h2", "tp" + DATABASE.incrementAndGet()));

        TeleportRuntime.resetForTests();
        TeleportRuntime.init(plugin);
        teleports = Teleports.of(plugin);
    }

    @AfterEach
    void tearDown() {
        RedisRuntime.installForTests(null);
        DatabaseRuntime.installRedisForTests(null);
        Teleports.releaseAll();
        TeleportRuntime.resetForTests();
        Cooldowns.clearEverything();
        Tasks.releaseAll();
        FakeServer.reset();
    }

    /**
     * Points the module at a Redis whose every operation is recorded in order.
     *
     * <p>Recording rather than asserting per call: what makes a handover
     * correct is the sequence, and a sequence can only be judged once it is
     * whole.
     */
    private void withRedis(String serverId) {
        redis = new MemoryClient();
        RedisRuntime.installForTests((settings, name) -> new Journalling(redis, journal));
        DatabaseRuntime.installRedisForTests(new RedisSettings(true, "localhost", 6379, "",
                0, 8, 1800, 300, 10_000, "exylia", serverId));
    }

    /** The key one player's queued destination lives under. */
    private String keyFor(FakePlayer who) {
        return "exylia:teleport:pending:" + who.player().getUniqueId();
    }

    // -------------------------------------------------------------- no Redis

    @Test
    @DisplayName("with no Redis a cross-server teleport says so, and throws nothing")
    void withoutRedisItIsUnavailable() {
        // No withRedis: the default block is disabled, which is what every
        // single-server installation looks like.
        assertFalse(teleports.isCrossServerAvailable());

        TeleportHandle handle = teleports.to(player.player(),
                ExyliaLocation.fromString("arena-3,lobby,1,2,3,0,0")).start();
        settle();

        assertEquals(TeleportResult.CROSS_SERVER_UNAVAILABLE, resultOf(handle));
        assertTrue(player.pluginMessages().isEmpty(),
                "a server with no Redis told a proxy to move somebody anyway");
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("with no Redis a local teleport is completely unaffected")
    void withoutRedisLocalTeleportsWork() {
        TeleportHandle handle = teleports.to(player.player(),
                new Location(world, 0, 70, 0)).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(1, player.teleports().size());
    }

    // ------------------------------------------------------------ the ordering

    @Test
    @DisplayName("the destination is stored before the proxy is told to move them")
    void theKeyIsWrittenBeforeTheConnect() {
        withRedis("lobby-1");
        assertTrue(teleports.isCrossServerAvailable());

        TeleportHandle handle = teleports.to(player.player(),
                ExyliaLocation.fromString("arena-3,lobby,1,2,3,0,0")).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));

        // The central contract of the module. A Connect sent first is a player
        // who arrives somewhere that does not know where to put them, and the
        // server they left has already forgotten them.
        assertEquals(1, journal.size(), "the destination was never stored");
        assertEquals("set " + keyFor(player), journal.get(0));
        assertEquals(List.of("BungeeCord"), player.pluginMessages(),
                "the proxy was never told to move them");
        assertEquals("arena-3,lobby,1.0,2.0,3.0,0.0,0.0", redis.get(keyFor(player)),
                "the stored destination is not the one that was asked for");
    }

    @Test
    @DisplayName("a write that fails sends no Connect at all")
    void aFailedWriteSendsNothing() {
        withRedis("lobby-1");
        redis.failingWrites(true);

        TeleportHandle handle = teleports.to(player.player(),
                ExyliaLocation.fromString("arena-3,lobby,1,2,3,0,0")).start();
        settle();

        assertEquals(TeleportResult.CROSS_SERVER_UNAVAILABLE, resultOf(handle));
        // Staying put with a message is the better half of a bad situation:
        // the alternative is being dumped in another lobby by a server that
        // just failed to record where they were going.
        assertTrue(player.pluginMessages().isEmpty(),
                "a failed write still told the proxy to move them");
        assertNull(redis.get(keyFor(player)));
    }

    @Test
    @DisplayName("a destination naming this server is a local teleport, not a handover")
    void thisServerIsLocal() {
        withRedis("lobby-1");

        TeleportHandle handle = teleports.to(player.player(),
                ExyliaLocation.fromString("lobby-1,lobby,7,70,8,0,0")).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(1, player.teleports().size(), "the player was handed to themselves");
        assertEquals(7.0, player.teleports().get(0).getX(), 0.001);
        assertTrue(player.pluginMessages().isEmpty());
        assertTrue(journal.isEmpty(), "a local teleport wrote a handover key");
    }

    @Test
    @DisplayName("a countdown runs before anything is written or sent")
    void theCountdownRunsFirst() {
        withRedis("lobby-1");

        TeleportHandle handle = teleports.to(player.player(),
                        ExyliaLocation.fromString("arena-3,lobby,1,2,3,0,0"))
                .warmup(1.0)
                .start();

        FakeServer.tick(15);
        assertTrue(journal.isEmpty(), "the destination was queued before the countdown ended");
        assertTrue(player.pluginMessages().isEmpty());

        FakeServer.tick(10);
        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(1, journal.size());
    }

    // ------------------------------------------------------------ the arrival

    @Test
    @DisplayName("joining with a queued destination moves them once the client has settled")
    void joiningClaimsTheDestination() {
        withRedis("arena-3");
        redis.set(keyFor(player), "arena-3,lobby,42,70,43,0,0", 300);
        journal.clear();

        join(player);
        // One tick is the asynchronous read, and it is the only thing that has
        // happened: the key is claimed straight away and only the move waits.
        FakeServer.tick(1);
        assertEquals(List.of("set " + presenceFor(player), "get " + keyFor(player), "delete"),
                journal, "the destination was not claimed on join");
        assertTrue(player.teleports().isEmpty(),
                "the player was moved before the client had a chance to load");

        // Half a second by default, and the wait is for the client rather than
        // a race we depend on: the key is already ours by now.
        FakeServer.tick(15);

        assertEquals(1, player.teleports().size(), "the player never arrived");
        assertEquals(42.0, player.teleports().get(0).getX(), 0.001);
        assertNull(redis.get(keyFor(player)),
                "a claimed destination must not move them again on the next login");
    }

    @Test
    @DisplayName("a configured settle wait is the one an arrival actually uses")
    void theConfiguredSettleWaitIsHonoured() {
        withRedis("arena-3");
        // Four seconds rather than the default half a second. An owner whose
        // clients are slow to load a world raises this, and a value that is
        // read but never applied is worse than no setting at all: it looks
        // like the fix was tried and did not help.
        teleports.using(new TeleportSettings(
                0.0, true, true, 5, 32, 300, 4.0, 3, 30, 60, 8, 16));
        redis.set(keyFor(player), "arena-3,lobby,42,70,43,0,0", 300);

        join(player);
        // Past the default wait, and well short of the configured one.
        FakeServer.tick(30);
        assertTrue(player.teleports().isEmpty(),
                "the arrival used the library default instead of the configured wait");

        FakeServer.tick(60);
        assertEquals(1, player.teleports().size(), "the player never arrived");
    }

    @Test
    @DisplayName("an ordinary join with nothing queued does nothing at all")
    void anOrdinaryJoinDoesNothing() {
        withRedis("arena-3");

        join(player);
        FakeServer.tick(20);

        // The normal case for every player on the server, so it costs the
        // presence write and one absent read, nothing else: no move, no log.
        assertEquals(List.of("set " + presenceFor(player), "get " + keyFor(player)), journal);
        assertTrue(player.teleports().isEmpty(), "a plain join moved somebody");
    }

    @Test
    @DisplayName("a queued destination in a world this server does not have moves nobody")
    void anUnknownWorldOnArrivalMovesNobody() {
        withRedis("arena-3");
        redis.set(keyFor(player), "arena-3,nether,1,2,3,0,0", 300);

        join(player);
        FakeServer.tick(20);

        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("an unreadable queued destination moves nobody and throws nothing")
    void anUnreadableDestinationMovesNobody() {
        withRedis("arena-3");
        redis.set(keyFor(player), "not a location", 300);

        join(player);
        FakeServer.tick(20);

        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("a Redis that will not answer on join costs nothing but the handover")
    void anUnreachableRedisOnJoinIsHarmless() {
        withRedis("arena-3");
        redis.failing(true);

        join(player);
        FakeServer.tick(20);

        assertTrue(player.teleports().isEmpty());
    }

    // ------------------------------------------------------- reaching a player

    /** Where one player's current server is filed. */
    private static String presenceFor(FakePlayer who) {
        return "exylia:players:" + who.player().getUniqueId();
    }

    @Test
    @DisplayName("reaching a player on another server queues them by id, then tells the proxy")
    void followingQueuesThePlayerThenConnects() {
        withRedis("lobby-1");
        FakePlayer suspect = new FakePlayer("Suspect");
        redis.set(presenceFor(suspect), "arena-3", 90);
        journal.clear();

        TeleportHandle handle = teleports.toPlayer(player.player(), suspect.player().getUniqueId()).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(TeleportCause.CROSS_SERVER, handle.cause());
        assertEquals(List.of("get " + presenceFor(suspect), "set " + keyFor(player)), journal);
        assertEquals("player:" + suspect.player().getUniqueId(), redis.get(keyFor(player)),
                "the arriving server must be told who to look for, not a stale place");
        assertEquals(List.of("BungeeCord"), player.pluginMessages());
    }

    @Test
    @DisplayName("a player the network does not know is not found, and nothing is sent")
    void followingNobodyIsNotFound() {
        withRedis("lobby-1");
        FakePlayer gone = new FakePlayer("Gone");

        TeleportHandle handle = teleports.toPlayer(player.player(), gone.player().getUniqueId()).start();
        settle();

        assertEquals(TeleportResult.TARGET_NOT_FOUND, resultOf(handle));
        assertTrue(player.pluginMessages().isEmpty());
        assertNull(redis.get(keyFor(player)));
    }

    @Test
    @DisplayName("without Redis anybody not on this server is simply not found")
    void followingWithoutRedisIsNotFound() {
        FakePlayer gone = new FakePlayer("Gone");

        TeleportHandle handle = teleports.toPlayer(player.player(), gone.player().getUniqueId()).start();
        settle();

        assertEquals(TeleportResult.TARGET_NOT_FOUND, resultOf(handle));
        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("a player on this server is a plain local teleport, whatever Redis says")
    void followingSomebodyHereIsLocal() {
        withRedis("lobby-1");
        FakePlayer here = new FakePlayer("Here");
        here.at(new Location(world, 30, 64, 30));
        FakeServer.online(player.player(), here.player());

        TeleportHandle handle = teleports.toPlayer(player.player(), here.player().getUniqueId()).start();
        settle();

        assertEquals(TeleportResult.SUCCESS, resultOf(handle));
        assertEquals(1, player.teleports().size());
        assertEquals(30.0, player.teleports().get(0).getX(), 0.001);
        assertTrue(journal.isEmpty(), "a local teleport asked Redis where they were");
    }

    @Test
    @DisplayName("arriving for a player lands next to them")
    void arrivingForAPlayerLandsNextToThem() {
        withRedis("arena-3");
        FakePlayer suspect = new FakePlayer("Suspect");
        suspect.at(new Location(world, 42, 70, 43));
        FakeServer.online(player.player(), suspect.player());
        redis.set(keyFor(player), "player:" + suspect.player().getUniqueId(), 300);

        join(player);
        FakeServer.tick(16);

        assertEquals(1, player.teleports().size(), "the player never arrived");
        assertEquals(42.0, player.teleports().get(0).getX(), 0.001);
        assertNull(redis.get(keyFor(player)));
    }

    @Test
    @DisplayName("arriving for a player who already left moves nobody")
    void arrivingForNobodyMovesNobody() {
        withRedis("arena-3");
        redis.set(keyFor(player), "player:" + java.util.UUID.randomUUID(), 300);

        join(player);
        FakeServer.tick(20);

        assertTrue(player.teleports().isEmpty());
    }

    @Test
    @DisplayName("joining records this server as the player's, leaving withdraws it")
    void joiningAnnouncesAndLeavingWithdraws() {
        withRedis("lobby-1");

        join(player);
        FakeServer.tick(1);
        assertEquals("lobby-1", redis.get(presenceFor(player)));

        // Another server picked them up before this one heard they left: the
        // proxy connects first and disconnects after. Its entry must survive.
        redis.set(presenceFor(player), "arena-3", 90);
        quit(player);
        FakeServer.tick(1);
        assertEquals("arena-3", redis.get(presenceFor(player)),
                "leaving erased another server's claim on the player");

        redis.set(presenceFor(player), "lobby-1", 90);
        quit(player);
        FakeServer.tick(1);
        assertNull(redis.get(presenceFor(player)), "leaving left the entry behind");
    }

    @Test
    @DisplayName("asking where a player is answers from this server first, then the network")
    void serverOfAnswers() {
        withRedis("lobby-1");
        FakePlayer away = new FakePlayer("Away");
        redis.set(presenceFor(away), "arena-3", 90);

        assertEquals(java.util.Optional.of("lobby-1"),
                teleports.serverOf(player.player().getUniqueId()).join());
        java.util.concurrent.CompletableFuture<java.util.Optional<String>> asked =
                teleports.serverOf(away.player().getUniqueId());
        settle();
        assertEquals(java.util.Optional.of("arena-3"), asked.join());
        java.util.concurrent.CompletableFuture<java.util.Optional<String>> nobody =
                teleports.serverOf(java.util.UUID.randomUUID());
        settle();
        assertEquals(java.util.Optional.empty(), nobody.join());
    }

    @Test
    @DisplayName("pulling a player from another server queues where to put them, then tells the proxy")
    void bringingQueuesThenConnectsOther() {
        withRedis("lobby-1");
        FakePlayer suspect = new FakePlayer("Suspect");
        redis.set(presenceFor(suspect), "arena-3", 90);
        journal.clear();

        java.util.concurrent.CompletableFuture<TeleportResult> pulled =
                teleports.bring(player.player(), suspect.player().getUniqueId(), "Suspect");
        settle();

        assertEquals(TeleportResult.SUCCESS, pulled.join());
        assertEquals(List.of("get " + presenceFor(suspect), "set " + keyFor(suspect)), journal);
        assertEquals("lobby-1,lobby,10.0,64.0,10.0,0.0,0.0", redis.get(keyFor(suspect)),
                "the pulled player must arrive where the puller is standing");
        assertEquals(List.of("BungeeCord"), player.pluginMessages(),
                "the proxy is told through the puller");
    }

    @Test
    @DisplayName("pulling a player the network does not know writes nothing")
    void bringingNobodyIsNotFound() {
        withRedis("lobby-1");
        journal.clear();

        java.util.concurrent.CompletableFuture<TeleportResult> pulled =
                teleports.bring(player.player(), java.util.UUID.randomUUID(), "Gone");
        settle();

        assertEquals(TeleportResult.TARGET_NOT_FOUND, pulled.join());
        assertEquals(1, journal.size(), "a player nobody has was still queued somewhere");
        assertTrue(player.pluginMessages().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    /** What the server sends when a player joins. */
    private void join(FakePlayer who) {
        FakeServer.dispatch(new org.bukkit.event.player.PlayerJoinEvent(
                who.player(), net.kyori.adventure.text.Component.empty()));
    }

    /** What the server sends when a player leaves. */
    private void quit(FakePlayer who) {
        FakeServer.dispatch(new org.bukkit.event.player.PlayerQuitEvent(
                who.player(), net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    private static TeleportResult resultOf(TeleportHandle handle) {
        assertTrue(handle.isDone(), "the teleport never completed");
        return handle.future().join();
    }

    private static void settle() {
        FakeServer.tick(3);
    }

    /**
     * A client that writes down what it was asked to do, in order.
     *
     * <p>Wrapping rather than subclassing the memory client, so what is
     * recorded is exactly what the module asked for and the storage underneath
     * behaves as a real Redis would.
     */
    private record Journalling(MemoryClient real, List<String> journal) implements RedisClient {

        @Override
        public String get(String key) {
            journal.add("get " + key);
            return real.get(key);
        }

        @Override
        public void set(String key, String value, int ttlSeconds) {
            // Recorded after the call, so a write that throws is not written
            // down as one that happened.
            real.set(key, value, ttlSeconds);
            journal.add("set " + key);
        }

        @Override
        public void delete(java.util.Collection<String> keys) {
            real.delete(keys);
            journal.add("delete");
        }

        @Override
        public void publish(String channel, String message) {
            real.publish(channel, message);
            journal.add("publish " + channel);
        }

        @Override
        public Subscription subscribe(String channel, java.util.function.Consumer<String> handler) {
            return real.subscribe(channel, handler);
        }

        @Override
        public void close() {
            real.close();
        }
    }
}
