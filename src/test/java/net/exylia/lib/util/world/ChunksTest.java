package net.exylia.lib.util.world;

import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunksTest {

    private static final ChunkSnapshot SNAPSHOT = proxy(ChunkSnapshot.class, (method, args) -> null);

    /** A world whose chunk is loaded or not, and which throws on any synchronous load. */
    private static final class FakeWorld {
        boolean loaded;
        boolean stayUnloaded;
        Throwable loadFailure;
        final AtomicInteger asyncLoads = new AtomicInteger();

        World world() {
            Chunk chunk = proxy(Chunk.class, (method, args) ->
                    method.getName().equals("getChunkSnapshot") ? SNAPSHOT : null);
            return proxy(World.class, (method, args) -> switch (method.getName()) {
                case "isChunkLoaded" -> loaded;
                case "getName" -> "test";
                case "getChunkAt" -> {
                    if (!loaded) throw new AssertionError("synchronous chunk load");
                    yield chunk;
                }
                case "getChunkAtAsync" -> {
                    asyncLoads.incrementAndGet();
                    if (loadFailure != null) yield CompletableFuture.failedFuture(loadFailure);
                    loaded = !stayUnloaded;
                    yield CompletableFuture.completedFuture(chunk);
                }
                default -> null;
            });
        }
    }

    private static final TaskScheduler INLINE = proxy(TaskScheduler.class, (method, args) -> {
        if (method.getName().equals("runAtLocation")) ((Runnable) args[1]).run();
        return method.getReturnType() == boolean.class ? false : null;
    });

    @Test
    void readsALoadedChunkWithoutLoadingIt() throws Exception {
        FakeWorld fake = new FakeWorld();
        fake.loaded = true;
        assertSame(SNAPSHOT, Chunks.snapshot(INLINE, fake.world(), 3, -2).get());
        assertEquals(0, fake.asyncLoads.get());
    }

    @Test
    void loadsAnUnloadedChunkAsynchronously() throws Exception {
        FakeWorld fake = new FakeWorld();
        assertSame(SNAPSHOT, Chunks.snapshot(INLINE, fake.world(), 0, 0).get());
        assertEquals(1, fake.asyncLoads.get());
    }

    @Test
    void givesUpOnAChunkThatKeepsUnloading() {
        FakeWorld fake = new FakeWorld();
        fake.stayUnloaded = true;
        assertThrows(ExecutionException.class, () -> Chunks.snapshot(INLINE, fake.world(), 0, 0).get());
        assertEquals(3, fake.asyncLoads.get());
    }

    @Test
    void reportsAFailedLoad() {
        FakeWorld fake = new FakeWorld();
        fake.loadFailure = new IllegalStateException("io");
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> Chunks.snapshot(INLINE, fake.world(), 0, 0).get());
        assertSame(fake.loadFailure, failed.getCause());
    }

    private interface Handler {
        Object handle(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> handler.handle(method, args));
    }
}
