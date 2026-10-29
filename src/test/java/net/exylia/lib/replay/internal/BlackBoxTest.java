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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

        assertSame(tape, box.sampleNear(arrow, null, new Location(null, 0, 64, 0), 1600, 100, 1, new Location(null, 0, 0, 0)));
        assertEquals(0, reads.get(), "seen this tick: nothing is read");

        box.tapes.clear();
        assertSame(tape, box.sampleNear(arrow, tape, new Location(null, 0, 64, 0), 1600, 100, 1, new Location(null, 0, 0, 0)),
                "a remembered tape is used without looking it up");
        assertEquals(0, reads.get());

        box.sampleNear(arrow, tape, new Location(null, 0, 64, 0), 1600, 101, 1, new Location(null, 0, 0, 0));
        assertEquals(1, reads.get(), "a new tick: it is read");
    }

    @Test
    @DisplayName("something read every other tick is skipped in between, by every timer")
    void readEveryOtherTick() {
        BlackBox box = new BlackBox(null, BlackBoxSettings.defaults());
        UUID id = UUID.randomUUID();
        Tape tape = new Tape(id, new ReplayActor(id, "Zombie", null, null, null), false, true);
        tape.seen = 100;
        AtomicInteger reads = new AtomicInteger();
        Entity zombie = counting(id, reads);
        Location here = new Location(null, 0, 64, 0);

        box.sampleNear(zombie, tape, here, 1600, 101, 2, new Location(null, 0, 0, 0));
        assertEquals(0, reads.get(), "read a tick ago: not due yet");
        box.sampleNear(zombie, tape, here, 1600, 102, 2, new Location(null, 0, 0, 0));
        assertEquals(1, reads.get(), "two ticks on: due");
    }

    @Test
    @DisplayName("past the cap, something without a tape is not even looked up")
    void fullBoxReadsNothing() {
        BlackBox box = new BlackBox(null, BlackBoxSettings.defaults().radius(16)
                .entitySampleEvery(1).seconds(10));
        BlackBox empty = new BlackBox(null, new BlackBoxSettings(60, 32, 0, true, 6, 400, player -> false));
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        UUID id = UUID.randomUUID();
        Entity mob = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) {
                        ids.incrementAndGet();
                        return id;
                    }
                    reads.incrementAndGet();
                    return method.getReturnType() == boolean.class ? false : null;
                });
        Location here = new Location(null, 0, 64, 0);
        assertEquals(null, empty.sampleNear(mob, null, here, 1600, 5, 1, new Location(null, 0, 0, 0)));
        assertEquals(0, reads.get());
        assertEquals(0, ids.get(), "a box with room for nothing does not look for its tape");

        box.sampleNear(mob, null, here, 1600, 5, 1, new Location(null, 0, 0, 0));
        assertEquals(1, ids.get(), "with room, it is looked up");
    }

    @Test
    @DisplayName("ticks a mob was not read on move it in a straight line, and a teleport still jumps")
    void fillsTheTicksBetweenReads() {
        BlackBox.Draft scene = new BlackBox.Draft(0, WORLD, 0, 64, 0);
        scene.chunks.put(BlackBox.key(0, 0), new int[] {64, 64});
        UUID id = UUID.randomUUID();
        Tape tape = new Tape(id, new ReplayActor(id, "Zombie", null, null, null), false, true);
        tape.put(100, WORLD, 1, 64, 1, 0f, 0f, MotionTrack.PRESENT, 20f);
        tape.put(102, WORLD, 3, 64, 1, 90f, 0f, MotionTrack.PRESENT, 20f);
        // Stood still from 102 (a read on 104 found it there), then walked.
        tape.put(106, WORLD, 5, 64, 1, 90f, 0f, MotionTrack.PRESENT, 20f);
        // Teleported.
        tape.put(108, WORLD, 14, 64, 1, 90f, 0f, MotionTrack.PRESENT, 20f);

        MotionTrack track = BlackBox.trackOf(tape, List.of(scene), 100, 110, 2);
        assertEquals(2.0, track.x(1), 1e-9, "halfway between two reads");
        assertEquals(45f, track.yaw(1), 1e-3, "turning halfway too");
        assertEquals(3.0, track.x(3), 1e-9, "standing until the last read that found it still");
        assertEquals(4.0, track.x(5), 1e-9);
        assertEquals(5.0, track.x(7), 1e-9, "too far to have walked: held, then a jump");
        assertEquals(14.0, track.x(8), 1e-9);

        MotionTrack held = BlackBox.trackOf(tape, List.of(scene), 100, 110);
        assertEquals(1.0, held.x(1), 1e-9, "read every tick: nothing is made up");
    }

    @Test
    @DisplayName("a track holds every tick its frames cover, widens the ground once, and skips a tape long gone")
    void trackOfFollowsFrames() {
        BlackBox.Draft scene = new BlackBox.Draft(0, WORLD, 0, 64, 0);
        int[] band = {64, 64};
        scene.chunks.put(BlackBox.key(0, 0), band);
        UUID id = UUID.randomUUID();
        Tape tape = new Tape(id, new ReplayActor(id, "Zombie", null, null, null), false, true);
        tape.put(100, WORLD, 1, 70, 1, 0f, 0f, MotionTrack.PRESENT, 20f);
        tape.put(105, WORLD, 2, 72, 2, 0f, 0f, MotionTrack.PRESENT, 20f);

        MotionTrack track = BlackBox.trackOf(tape, List.of(scene), 100, 110);
        assertTrue(track != null && track.present(0) && track.present(10), "held between and after its frames");
        assertEquals(1.0, track.x(4), 1e-9);
        assertEquals(2.0, track.x(5), 1e-9);
        assertEquals(64, band[0]);
        assertEquals(72, band[1], "the ground under it reaches where it climbed");

        assertEquals(null, BlackBox.trackOf(tape, List.of(scene), 200, 210), "gone long before the window");
    }

    @Test
    @DisplayName("each chunk keeps the height people moved at above it, not the whole scene's")
    void heightIsPerChunk() {
        BlackBox.Draft draft = new BlackBox.Draft(0, WORLD, 0, 64, 0);
        draft.margin = 6;
        draft.bottom = -4;
        draft.top = 19;
        draft.minSection = 3;  // the focus stood at 64
        draft.maxSection = 4;
        draft.chunks.put(BlackBox.key(0, 0), new int[] {64, 64});
        draft.chunks.put(BlackBox.key(1, 0), new int[] {64, 120}); // somebody climbed there
        assertTrue(draft.keeps(BlackBox.key(0, 0), 4));
        assertFalse(draft.keeps(BlackBox.key(0, 0), 7), "nobody went that high here");
        assertTrue(draft.keeps(BlackBox.key(1, 0), 7), "the climber's chunk keeps their height");
        assertFalse(draft.keeps(BlackBox.key(1, 0), 1), "nor the caves under anybody");
        assertFalse(draft.keeps(BlackBox.key(9, 9), 7));
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
