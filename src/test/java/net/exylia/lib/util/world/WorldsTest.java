package net.exylia.lib.util.world;

import net.exylia.lib.FakeServer;
import net.exylia.lib.util.world.internal.WorldsBackendDetector;
import net.kyori.adventure.key.Key;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a server without the Worlds plugin gets.
 *
 * <p>This is the path every server that has not installed Worlds takes, so the
 * promise being proved here is that asking costs nothing and answers plainly:
 * no exception reaches the caller, and a caller who checked first is told the
 * truth.
 */
class WorldsTest {

    private static final Key KEY = Key.key("exylialib", "test_world");

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        // The probe is remembered for the whole JVM, so a test that did not
        // clear it would be reading another test's answer.
        WorldsBackendDetector.reset();
    }

    @AfterEach
    void tearDown() {
        WorldsBackendDetector.reset();
        FakeServer.reset();
    }

    @Test
    @DisplayName("Without the Worlds plugin the module reports itself unavailable")
    void unavailableWithoutPlugin() {
        assertFalse(Worlds.isAvailable(),
                "no Worlds plugin is installed, so nothing can be bound");
    }

    @Test
    @DisplayName("The backend name is 'none' when nothing is bound, never null")
    void backendNameIsNone() {
        String name = Worlds.backendName();
        assertNotNull(name, "backendName is documented as never null");
        assertEquals("none", name);
    }

    @Test
    @DisplayName("Creating a world completes with null instead of throwing")
    void createCompletesWithNull() {
        CompletableFuture<World> future = Worlds.create(KEY, "arena_1");

        assertNotNull(future, "the future itself is never null");
        assertTrue(future.isDone(), "with no backend the answer is immediate");
        assertFalse(future.isCompletedExceptionally(),
                "degradation means a completed future, not a failed one");
        assertNull(future.join(), "no backend means no world");
    }

    @Test
    @DisplayName("Creating a non-void world degrades the same way")
    void createNonVoidCompletesWithNull() {
        CompletableFuture<World> future = Worlds.create(KEY, "arena_2", false);

        assertTrue(future.isDone());
        assertNull(future.join());
    }

    @Test
    @DisplayName("Deleting a world completes with false instead of throwing")
    void deleteCompletesWithFalse() {
        World world = FakeServer.newWorld("arena_1");

        CompletableFuture<Boolean> future = Worlds.delete(world);

        assertNotNull(future, "the future itself is never null");
        assertTrue(future.isDone(), "with no backend the answer is immediate");
        assertFalse(future.isCompletedExceptionally(),
                "degradation means a completed future, not a failed one");
        assertFalse(future.join(), "nothing was deleted, so the answer is false");
    }

    @Test
    @DisplayName("Detection runs once and is remembered, negative answers included")
    void detectionIsMemoized() {
        int before = WorldsBackendDetector.detections();

        Worlds.isAvailable();
        int afterFirst = WorldsBackendDetector.detections();

        Worlds.isAvailable();
        Worlds.backendName();
        Worlds.create(KEY, "arena_3");
        Worlds.delete(FakeServer.newWorld("arena_3"));
        int afterMany = WorldsBackendDetector.detections();

        assertEquals(before + 1, afterFirst, "the first call has to probe");
        assertEquals(afterFirst, afterMany,
                "'no backend' is remembered too, so nothing probes again");
    }

    @Test
    @DisplayName("Resetting the detection makes the next call probe again")
    void resetForcesReprobe() {
        Worlds.isAvailable();
        int afterFirst = WorldsBackendDetector.detections();

        WorldsBackendDetector.reset();
        Worlds.isAvailable();

        assertEquals(afterFirst + 1, WorldsBackendDetector.detections(),
                "reset is what a reload flow uses to pick up a newly enabled plugin");
    }

    @Test
    @DisplayName("No entry point throws when the Worlds plugin is absent")
    void nothingThrows() {
        World world = FakeServer.newWorld("arena_4");

        assertDoesNotThrow(() -> {
            Worlds.isAvailable();
            Worlds.backendName();
            Worlds.create(KEY, "arena_4").join();
            Worlds.create(KEY, "arena_4", false).join();
            Worlds.create(KEY, "arena_4", true).join();
            Worlds.delete(world).join();
        }, "a missing optional plugin must never reach the caller as a failure");
    }
}
