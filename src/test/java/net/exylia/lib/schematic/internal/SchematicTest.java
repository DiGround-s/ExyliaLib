package net.exylia.lib.schematic.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.debug.DebugCapture;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.schematic.PluginSchematics;
import net.exylia.lib.schematic.RegenerateOptions;
import net.exylia.lib.schematic.SchematicOutcome;
import net.exylia.lib.schematic.SchematicResult;
import net.exylia.lib.schematic.Schematics;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Behaviour of the schematic module, with no FastAsyncWorldEdit and no server.
 *
 * <p>Everything the module decides is above {@link SchematicEngine}, so a fake
 * installed through {@link Engines#install} exercises all of it: the name
 * checking, which folder a name resolves to, the order of the three stages, and
 * what a stage that fails does to the caller's future.
 *
 * <p>Every wait here has a deadline. A chain that hangs is the headline
 * ExyliaCommons bug this module fixes, so a test of it must <em>fail</em>
 * rather than block the build forever.
 */
class SchematicTest {

    /** Long enough for a real thread to get going, short enough to notice. */
    private static final long DEADLINE_MILLIS = 5_000L;

    /** Names are unique per test so a leftover owner can never be reused. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @TempDir
    Path dataFolder;

    /** The three stages append to this, so a test can assert their order. */
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    private RecordingEngine engine;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        FakeServer.runAsyncForReal();
        DebugCapture.start();
        SchematicRuntime.forgetOwnersForTests();
        engine = new RecordingEngine(log);
        Engines.install(engine);
        plugin = FakeServer.newPlugin("Schem" + COUNTER.incrementAndGet(),
                dataFolder.toFile());
        // A world by the name the identity below carries: a save resolves the
        // world before it copies anything, and a server that has not loaded it
        // is a FAILED result rather than a copy of nothing.
        FakeServer.worlds(FakeServer.newWorld("arena"));
    }

    @AfterEach
    void tearDown() {
        SchematicRuntime.forgetOwnersForTests();
        Engines.install(null);
        DebugCapture.stop();
        FakeServer.reset();
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a name that would escape the folder is refused, not concatenated")
    void refusesTraversal() {
        assertNotNull(SchematicNames.reasonToRefuse("../../plugins/Other/config"));
        assertNotNull(SchematicNames.reasonToRefuse("..\\other"));
        assertNotNull(SchematicNames.reasonToRefuse("arena/1"));
        assertNotNull(SchematicNames.reasonToRefuse("arena\u0000"));
    }

    @Test
    @DisplayName("an empty name is refused, so no plugin shares a file called .schem")
    void refusesEmpty() {
        assertNotNull(SchematicNames.reasonToRefuse(""));
        assertNotNull(SchematicNames.reasonToRefuse(null));
    }

    @Test
    @DisplayName("a leading dot is refused, which is also what stops \"..\"")
    void refusesLeadingDot() {
        assertNotNull(SchematicNames.reasonToRefuse(".hidden"));
        assertNotNull(SchematicNames.reasonToRefuse(".."));
        // But a dot inside is fine: arena.v2 is a name somebody will write.
        assertNull(SchematicNames.reasonToRefuse("arena.v2"));
        assertNull(SchematicNames.reasonToRefuse("Arena_1-final"));
    }

    @Test
    @DisplayName("a name longer than 128 characters is refused")
    void refusesTooLong() {
        assertNull(SchematicNames.reasonToRefuse("a".repeat(128)));
        assertNotNull(SchematicNames.reasonToRefuse("a".repeat(129)));
    }

    @Test
    @DisplayName("a refused name is a FAILED result, never a thrown exception")
    void refusedNameFailsTheFuture() throws Exception {
        SchematicResult result = await(schematics().save("../escape", box(), world()));
        assertEquals(SchematicOutcome.FAILED, result.outcome());
        assertNotNull(result.reason());
        // And nothing was attempted.
        assertTrue(engine.calls().isEmpty());
    }

    // ------------------------------------------------------------------
    // The index
    // ------------------------------------------------------------------

    @Test
    @DisplayName("exists() answers from memory: the file can be gone and it still says yes")
    void existsNeverTouchesTheDisk() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        assertTrue(schematics.exists("arena_1"));

        // Deleted behind the module's back, exactly as a server owner would.
        assertTrue(new File(dataFolder.toFile(), "schematics/arena_1.schem").delete());
        assertTrue(schematics.exists("arena_1"),
                "exists() must answer from the index, not from a stat syscall");
    }

    @Test
    @DisplayName("exists() still answers when the whole folder is gone")
    void existsSurvivesTheFolderGoing() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        deleteRecursively(dataFolder.toFile());
        assertFalse(dataFolder.toFile().exists());

        assertTrue(schematics.exists("arena_1"));
        assertEquals(1, schematics.names().size());
    }

    @Test
    @DisplayName("the index is seeded from both folders, the ExyliaCommons one included")
    void indexReadsBothFolders() throws Exception {
        writeSchematic("schematics", "new_arena");
        writeSchematic("schematics/regions", "commons_arena");
        // Something that is not a schematic, and a name no operation would take.
        Files.writeString(dataFolder.resolve("schematics").resolve("notes.txt"), "x");
        Files.createDirectories(dataFolder.resolve("schematics"));
        Files.writeString(dataFolder.resolve("schematics").resolve("bad name.schem"), "x");

        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        assertTrue(schematics.exists("new_arena"));
        assertTrue(schematics.exists("commons_arena"),
                "arenas written by ExyliaCommons are still on production and must be listed");
        assertFalse(schematics.exists("notes"));
        assertFalse(schematics.exists("bad name"));
        assertEquals(2, schematics.names().size());
    }

    @Test
    @DisplayName("a name in both folders resolves to the new one, so a re-save is an upgrade")
    void newFolderWins() throws Exception {
        writeSchematic("schematics/regions", "arena_1");
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        await(schematics.paste("arena_1", somewhere()));

        String pasted = engine.calls().stream()
                .filter(call -> call.startsWith("paste:"))
                .findFirst()
                .orElseThrow();
        assertTrue(pasted.contains("arena_1.schem"));
        // Same file name in both folders, so the proof is the resolution itself
        // having preferred the current one; the store is asked directly.
        assertEquals(new File(dataFolder.toFile(), "schematics/arena_1.schem")
                        .getAbsolutePath(),
                new SchematicStore(dataFolder.toFile()).find("arena_1").getAbsolutePath());
    }

    @Test
    @DisplayName("delete removes both copies, so a deleted arena does not come back")
    void deleteRemovesBothFolders() throws Exception {
        writeSchematic("schematics", "arena_1");
        writeSchematic("schematics/regions", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        assertTrue(await(schematics.delete("arena_1")));

        assertFalse(new File(dataFolder.toFile(), "schematics/arena_1.schem").exists());
        assertFalse(new File(dataFolder.toFile(), "schematics/regions/arena_1.schem").exists(),
                "leaving the ExyliaCommons copy makes the arena come back next restart");
        assertFalse(schematics.exists("arena_1"));
    }

    @Test
    @DisplayName("a save adds to the index, so the next menu render sees it")
    void saveKeepsTheIndexInStep() throws Exception {
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);
        assertFalse(schematics.exists("arena_1"));

        assertTrue(await(schematics.save("arena_1", box(), world())).isSuccess());

        assertTrue(schematics.exists("arena_1"));
    }

    // ------------------------------------------------------------------
    // Every future completes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stage that throws completes the future instead of hanging")
    void failingStageCompletes() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);
        engine.failsWith(new IllegalStateException("chunk would not load"));

        SchematicResult result = await(schematics.paste("arena_1", somewhere()));

        assertEquals(SchematicOutcome.FAILED, result.outcome());
        assertTrue(String.valueOf(result.reason()).contains("chunk would not load"));
    }

    @Test
    @DisplayName("an Error, not only an Exception, still completes the future")
    void failingWithAnErrorCompletes() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);
        // What a FAWE version whose API moved actually throws. A
        // catch (Exception) does not see this, and an unseen failure is a
        // future nobody completes.
        engine.failsWith(new NoClassDefFoundError("com/sk89q/worldedit/EditSession"));

        SchematicResult result = await(schematics.paste("arena_1", somewhere()));

        assertEquals(SchematicOutcome.FAILED, result.outcome());
        assertTrue(String.valueOf(result.reason()).contains("NoClassDefFoundError"));
    }

    @Test
    @DisplayName("NOT_FOUND is not FAILED: nothing went wrong, there is just no file")
    void notFoundIsItsOwnAnswer() throws Exception {
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        SchematicResult missing = await(schematics.paste("never_saved", somewhere()));
        assertEquals(SchematicOutcome.NOT_FOUND, missing.outcome());
        assertNull(missing.reason());
        assertTrue(engine.calls().isEmpty(), "nothing should have been attempted");

        writeSchematic("schematics", "arena_1");
        engine.failsWith(new IllegalStateException("disk on fire"));
        SchematicResult broken = await(schematics.paste("arena_1", somewhere()));
        assertEquals(SchematicOutcome.FAILED, broken.outcome());
    }

    @Test
    @DisplayName("without an engine nothing throws: every operation answers UNSUPPORTED")
    void unsupportedRatherThanThrowing() throws Exception {
        Engines.install(null);
        PluginSchematics schematics = schematics();

        assertFalse(Schematics.isSupported());
        assertEquals(Engines.NO_FAWE, Schematics.unsupportedReason());

        assertEquals(SchematicOutcome.UNSUPPORTED,
                await(schematics.save("arena_1", box(), world())).outcome());
        assertEquals(SchematicOutcome.UNSUPPORTED,
                await(schematics.paste("arena_1", somewhere())).outcome());
        assertEquals(SchematicOutcome.UNSUPPORTED,
                await(schematics.regenerate("arena_1", box(), world())).outcome());
    }

    @Test
    @DisplayName("a disabled plugin completes what it still has, naming the schematic")
    void releaseCompletesWhatIsInFlight() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        Engines.install(new HoldingEngine(entered, held));

        CompletableFuture<SchematicResult> future = schematics.paste("arena_1", somewhere());
        assertTrue(entered.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS),
                "the paste never started");

        SchematicRuntime.release(plugin.getName());

        SchematicResult result = await(future);
        assertEquals(SchematicOutcome.FAILED, result.outcome());
        assertEquals("arena_1", result.name());
        held.countDown();
    }

    @Test
    @DisplayName("release drops the plugin's index, so nothing survives its classloader")
    void releaseDropsTheIndex() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        // Removed behind the module's back, so the only thing still claiming
        // this arena exists is the in-memory index.
        assertTrue(new File(dataFolder.toFile(), "schematics/arena_1.schem").delete());
        assertTrue(schematics.exists("arena_1"));

        SchematicRuntime.release(plugin.getName());

        // A fresh owner re-reads the disk, which no longer has it. Phrased this
        // way rather than by catching the new owner before its listing
        // finishes: that listing is asynchronous, so racing it would make the
        // test a coin flip rather than a proof.
        PluginSchematics reopened = schematics();
        awaitIndexed(reopened);
        assertFalse(reopened.exists("arena_1"),
                "the released index was kept, so a dropped plugin's state outlived it");
    }

    // ------------------------------------------------------------------
    // Entities are opt-in
    // ------------------------------------------------------------------

    @Test
    @DisplayName("loose entities are off by default on save and on paste")
    void entitiesAreOptIn() throws Exception {
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        await(schematics.save("plain", box(), world()));
        assertTrue(engine.calls().contains("save:plain.schem:false"),
                "copying entities would restore the dropped swords of the last match");

        await(schematics.save("built", box(), world(), true));
        assertTrue(engine.calls().contains("save:built.schem:true"));

        await(schematics.paste("plain", somewhere()));
        assertTrue(engine.calls().stream().anyMatch(call ->
                call.startsWith("paste:plain.schem") && call.endsWith(":false")));

        await(schematics.paste("built", somewhere(), true));
        assertTrue(engine.calls().stream().anyMatch(call ->
                call.startsWith("paste:built.schem") && call.endsWith(":true")));
    }

    // ------------------------------------------------------------------
    // Regenerating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the three stages run in order: clear, then paste, then rescue")
    void regenerateOrdersItsStages() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        FakeEntity dropped = new FakeEntity();
        FakePlayer buried = new FakePlayer("Buried");
        FakeWorld world = new FakeWorld("arena", log)
                .with(dropped.entity())
                .with(buried.player())
                .fill(5, 60, 70, 5);
        buried.at(new Location(world.world(), 5, 62, 5));
        FakeServer.worlds(world.world());

        SchematicResult result = awaitTicking(
                schematics.regenerate("arena_1", box(), identity(world),
                        RegenerateOptions.defaults()));
        assertTrue(result.isSuccess());
        FakeServer.tick(3);

        List<String> stages = List.copyOf(log);
        int clear = stages.indexOf("clear");
        int paste = stages.indexOf("paste");
        int rescue = stages.indexOf("rescue");
        assertTrue(clear >= 0 && paste >= 0 && rescue >= 0,
                "all three stages must have run, saw " + stages);
        assertTrue(clear < paste,
                "entities must go while the old blocks are still there, saw " + stages);
        assertTrue(paste < rescue,
                "rescuing first would put a player inside a wall that is not there yet, "
                        + "saw " + stages);
        assertTrue(dropped.isRemoved());
    }

    @Test
    @DisplayName("the rescue is scheduled onto the world thread, never done inline")
    void rescueHopsOntoTheWorldThread() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        FakePlayer buried = new FakePlayer("Buried");
        FakeWorld world = new FakeWorld("arena", log)
                .with(buried.player())
                .fill(5, 60, 70, 5);
        buried.at(new Location(world.world(), 5, 62, 5));
        FakeServer.worlds(world.world());

        // Clearing is off, so nothing before the paste reads the world and the
        // whole chain up to it runs asynchronously: the future is answered
        // without the server ticking at all.
        SchematicResult result = await(schematics.regenerate("arena_1", box(),
                identity(world), RegenerateOptions.defaults().clearEntities(false)));
        assertTrue(result.isSuccess());

        // The assertion that catches the bug. A rescue called inline from the
        // asynchronous paste stage would already have read the world here.
        // Asserting on the thread's *name* would not: FakeServer runs an async
        // task on a real thread and also leaves it queued, so a later tick
        // re-runs it on the test thread and both stages report the same name.
        assertTrue(world.readerThreads().isEmpty(),
                "the world was read from the asynchronous paste stage; "
                        + "the rescue must be scheduled with runAtLocation");

        FakeServer.tick(2);

        assertFalse(world.readerThreads().isEmpty(),
                "the rescue never ran");
        assertEquals(1, buried.teleports().size(), "the buried player was not moved");
        assertEquals(71, buried.teleports().get(0).getBlockY(),
                "the player should end up on the first air above the arena");
    }

    @Test
    @DisplayName("both regenerate switches can be turned off")
    void regenerateSwitchesAreHonoured() throws Exception {
        writeSchematic("schematics", "arena_1");
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        FakeEntity dropped = new FakeEntity();
        FakePlayer standing = new FakePlayer("Standing");
        FakeWorld world = new FakeWorld("arena", log)
                .with(dropped.entity())
                .with(standing.player())
                .fill(5, 60, 70, 5);
        standing.at(new Location(world.world(), 5, 62, 5));
        FakeServer.worlds(world.world());

        SchematicResult result = await(schematics.regenerate("arena_1", box(),
                identity(world),
                RegenerateOptions.defaults().clearEntities(false).moveTrappedPlayers(false)));
        assertTrue(result.isSuccess());
        FakeServer.tick(3);

        assertFalse(dropped.isRemoved(), "the armour stands were the build");
        assertTrue(standing.teleports().isEmpty(), "nobody asked for a rescue");
        assertTrue(world.readerThreads().isEmpty(), "neither stage should have read the world");
    }

    @Test
    @DisplayName("a regenerate of a schematic that is not there kills nothing first")
    void regenerateResolvesBeforeItDestroys() throws Exception {
        PluginSchematics schematics = schematics();
        awaitIndexed(schematics);

        FakeEntity dropped = new FakeEntity();
        FakeWorld world = new FakeWorld("arena", log).with(dropped.entity());
        FakeServer.worlds(world.world());

        SchematicResult result = awaitTicking(
                schematics.regenerate("never_saved", box(), identity(world),
                        RegenerateOptions.defaults()));

        assertEquals(SchematicOutcome.NOT_FOUND, result.outcome());
        assertFalse(dropped.isRemoved(),
                "an arena whose schematic is missing must not lose its entities first");
    }

    // ------------------------------------------------------------------
    // Bounds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cuboid rounds to the blocks it encloses, maximum included")
    void boundsAreInclusiveBlocks() {
        Bounds bounds = Bounds.of(Cuboid.blocks(0, 60, 0, 10, 70, 10));
        assertEquals(0, bounds.minX());
        assertEquals(10, bounds.maxX());
        assertEquals(70, bounds.maxY());
        assertTrue(bounds.contains(10, 70, 10));
        assertFalse(bounds.contains(11, 70, 10));
        assertFalse(bounds.contains(-1, 60, 0));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PluginSchematics schematics() {
        return Schematics.of(plugin);
    }

    private static Cuboid box() {
        return Cuboid.blocks(0, 60, 0, 10, 70, 10);
    }

    private static WorldIdentity world() {
        return new WorldIdentity(java.util.UUID.nameUUIDFromBytes("arena".getBytes()), "arena");
    }

    private static WorldIdentity identity(FakeWorld world) {
        return WorldIdentity.from(world.world());
    }

    private Location somewhere() {
        org.bukkit.World target = FakeServer.newWorld("paste-target");
        return new Location(target, 0, 60, 0);
    }

    private void writeSchematic(String folder, String name) throws Exception {
        Path directory = dataFolder.resolve(folder);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name + ".schem"), "schematic");
    }

    /**
     * Waits on a future with a deadline.
     *
     * <p>A deadline rather than a plain {@code get()}: a chain that hangs is
     * the bug this module exists to fix, so a test of it has to fail rather
     * than block the build.
     */
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            return fail("the future never completed — a stage swallowed a failure");
        } catch (ExecutionException failed) {
            return fail("nothing here should complete exceptionally", failed.getCause());
        }
    }

    /**
     * Waits on a future while ticking the server.
     *
     * <p>A regeneration crosses threads on purpose — its clear and rescue
     * stages are scheduled onto the thread that owns the box — so waiting on
     * the future alone would deadlock the harness rather than the module.
     */
    private static SchematicResult awaitTicking(CompletableFuture<SchematicResult> future)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
        while (!future.isDone()) {
            if (System.nanoTime() > deadline) {
                return fail("the regeneration never completed");
            }
            FakeServer.tick(1);
            Thread.sleep(5L);
        }
        return future.get();
    }

    /** Waits for the first folder listing to finish, with a deadline. */
    private static void awaitIndexed(PluginSchematics schematics) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
        while (!schematics.isIndexed()) {
            if (System.nanoTime() > deadline) {
                fail("the schematics folder was never listed");
            }
            Thread.sleep(5L);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** An engine whose paste can be held open, so a release can catch it. */
    private static final class HoldingEngine implements SchematicEngine {

        private final CountDownLatch entered;
        private final CountDownLatch held;

        private HoldingEngine(CountDownLatch entered, CountDownLatch held) {
            this.entered = entered;
            this.held = held;
        }

        @Override
        public void save(org.bukkit.World world, Bounds bounds, File destination,
                         boolean copyEntities) {
        }

        @Override
        public void paste(org.bukkit.World world, int x, int y, int z, File source,
                          boolean copyEntities) throws Exception {
            entered.countDown();
            if (!held.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the test never let go");
            }
        }

        @Override
        public void releaseAll() {
        }
    }
}
