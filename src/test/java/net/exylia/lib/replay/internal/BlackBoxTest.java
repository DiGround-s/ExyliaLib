package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.BlackBoxSettings;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the black box does not read what it is going to throw away.
 */
class BlackBoxTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** An entity that counts how often anything but its id is asked of it. */
    private static Entity counting(UUID id, AtomicInteger reads) {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return id;
                    reads.incrementAndGet();
                    // Not valid: sampleNear stops at its first read either way.
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    @Test
    @DisplayName("an entity another timer already wrote this tick is not read again")
    void skipsWhatWasSeenThisTick() {
        BlackBox box = new BlackBox(null, BlackBoxSettings.defaults());
        UUID id = UUID.randomUUID();
        Tape tape = new Tape(id, new ReplayActor(id, "Arrow", null, null, null), false, false);
        tape.seen = 100;
        box.tapes.put(id, tape);
        AtomicInteger reads = new AtomicInteger();
        Entity arrow = counting(id, reads);

        box.sampleNear(arrow, new Location(null, 0, 64, 0), 1600, 100);
        assertEquals(0, reads.get(), "seen this tick: nothing is read");

        box.sampleNear(arrow, new Location(null, 0, 64, 0), 1600, 101);
        assertEquals(1, reads.get(), "a new tick: it is read");
    }

    @Test
    @DisplayName("without terrain only the chunks a block changed in are snapshotted")
    void changedChunksOnly() {
        BlockData was = null;
        List<BlackBox.Change> log = List.of(
                new BlackBox.Change(50, WORLD, 5, 64, 5, was),      // chunk 0,0: kept
                new BlackBox.Change(50, WORLD, 40, 64, -20, was),   // chunk 2,-2: kept
                new BlackBox.Change(10, WORLD, 100, 64, 100, was),  // before the scene
                new BlackBox.Change(50, OTHER, 200, 64, 200, was),  // another world
                new BlackBox.Change(50, WORLD, 300, 300, 300, was)  // above the sections
        );
        Set<Long> chunks = BlackBox.changedChunks(log, WORLD, 20, 0, 8);
        assertEquals(Set.of(BlackBox.key(0, 0), BlackBox.key(2, -2)), chunks);
    }

    @Test
    @DisplayName("reads whose batch the scheduler refuses complete instead of hanging the capture")
    void refusedBatchesComplete() {
        TaskScheduler refusing = (TaskScheduler) Proxy.newProxyInstance(TaskScheduler.class.getClassLoader(),
                new Class<?>[]{TaskScheduler.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("plugin disabled");
                });
        int count = BlackBox.SNAPSHOTS_PER_TICK * 3;
        List<Runnable> reads = new ArrayList<>();
        List<CompletableFuture<Void>> loading = new ArrayList<>();
        AtomicInteger ran = new AtomicInteger();
        for (int index = 0; index < count; index++) {
            CompletableFuture<Void> read = new CompletableFuture<>();
            loading.add(read);
            reads.add(() -> {
                ran.incrementAndGet();
                read.complete(null);
            });
        }
        BlackBox.inBatches(reads, loading, refusing);
        assertEquals(BlackBox.SNAPSHOTS_PER_TICK, ran.get(), "only the first batch runs now");
        assertTrue(loading.stream().allMatch(CompletableFuture::isDone), "every read is complete");
    }
}
