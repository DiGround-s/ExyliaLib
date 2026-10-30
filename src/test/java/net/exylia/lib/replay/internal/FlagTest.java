package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.BlackBoxSettings;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayFlag;
import net.exylia.lib.replay.ReplayHit;
import net.exylia.lib.replay.ReplayMark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Anticheat flags: folded in the black box, carried through the codec, and the
 * hits a playback listener asks about.
 */
class FlagTest {

    private static final UUID RED = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BLUE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final int STANDING = MotionTrack.flagsOf(0, false, true, false, false, false, false, false, true);

    @Test
    @DisplayName("flags of one player and check within a second are one entry with a count")
    void folds() throws Exception {
        ReplayClock.useWall(true);
        BlackBox box = new BlackBox(null, BlackBoxSettings.defaults());
        Field running = BlackBox.class.getDeclaredField("running");
        running.setAccessible(true);
        running.setBoolean(box, true);

        for (int i = 0; i < 5; i++) box.flag(RED, "Reach", "3.4 blocks");
        box.flag(RED, "Timer", null);
        box.flag(BLUE, "Reach", null);

        Collection<?> happenings = (Collection<?>) field(box, "happenings");
        assertEquals(3, happenings.size(), "one entry per player and check");
        List<String[]> written = new ArrayList<>();
        for (Object happening : happenings) {
            java.lang.reflect.Method read = happening.getClass().getDeclaredMethod("flag");
            read.setAccessible(true);
            BlackBox.Flag flag = (BlackBox.Flag) read.invoke(happening);
            written.add(MarkData.flag(flag.write()));
        }
        assertEquals("Reach", written.get(0)[0]);
        assertEquals("3.4 blocks", written.get(0)[1], "the first detail is kept");
        assertEquals("5", written.get(0)[2]);
        assertEquals("1", written.get(1)[2]);
        assertNull(written.get(1)[1]);
    }

    @Test
    @DisplayName("a stopped box takes no flags")
    void stopped() throws Exception {
        BlackBox box = new BlackBox(null, BlackBoxSettings.defaults());
        box.flag(RED, "Reach", null);
        assertEquals(0, ((Collection<?>) field(box, "happenings")).size());
    }

    @Test
    @DisplayName("flag marks come back through the codec, read as flags")
    void roundTrip() {
        Replay read = Replay.from(replay().toBytes());
        List<ReplayFlag> flags = read.flags();
        assertEquals(List.of(new ReplayFlag(RED, 4, "Killaura", "type B", 7),
                new ReplayFlag(BLUE, 9, "Fly", null, 1)), flags);
        assertNull(ReplayFlag.of(read.marks().getFirst()), "not a flag");
    }

    @Test
    @DisplayName("each attack mark finds its own hit and tally, the uncounted ones adding nothing")
    void tallies() {
        Replay replay = Replay.from(replay().toBytes());
        List<ReplayMark> attacks = replay.marks().stream()
                .filter(mark -> mark.kind().equals(ReplayMark.ATTACK)).toList();
        assertEquals(4, attacks.size());

        ReplayHit first = replay.hit(attacks.get(0));
        assertEquals(2, first.tick());
        assertEquals(new ReplayHit.Tally(1, 3.0), replay.tally(first));

        ReplayHit missed = replay.hit(attacks.get(1));
        assertFalse(missed.confirmed());
        assertEquals(new ReplayHit.Tally(1, 3.0), replay.tally(missed), "told the tally before it");

        ReplayHit second = replay.hit(attacks.get(2));
        assertSame(replay.hits().get(2), second, "two on one tick stay apart");
        assertEquals(new ReplayHit.Tally(2, 7.0), replay.tally(second));

        ReplayHit back = replay.hit(attacks.get(3));
        assertEquals(BLUE, back.attacker());
        assertEquals(new ReplayHit.Tally(1, 2.0), replay.tally(back), "the other way round is its own count");
        assertNull(replay.hit(replay.marks().getFirst()));
    }

    private static Replay replay() {
        MotionTrack.Builder red = new MotionTrack.Builder();
        MotionTrack.Builder blue = new MotionTrack.Builder();
        for (int tick = 0; tick < 20; tick++) {
            red.put(tick, 0, 0, 0, 0f, 0f, 0f, 20, STANDING);
            blue.put(tick, 0, 0, 2, 180f, 0f, 180f, 20, STANDING);
        }
        List<ReplayMark> marks = List.of(
                ReplayMark.of(0, ReplayMark.CHAT, RED, "gl"),
                new ReplayMark(2, ReplayMark.ATTACK, RED, MarkData.attack(BLUE, MarkData.STRONG, 3f)),
                new ReplayMark(4, ReplayMark.FLAG, RED, MarkData.flag("Killaura", "type B", 7)),
                new ReplayMark(6, ReplayMark.ATTACK, RED, MarkData.attack(BLUE, MarkData.UNCONFIRMED, Float.NaN)),
                new ReplayMark(6, ReplayMark.ATTACK, RED, MarkData.attack(BLUE, MarkData.STRONG, 4f)),
                new ReplayMark(8, ReplayMark.ATTACK, BLUE, MarkData.attack(RED, 0, 2f)),
                new ReplayMark(9, ReplayMark.FLAG, BLUE, MarkData.flag("Fly", null, 1)));
        return new Replay(UUID.randomUUID(), 1L, 20,
                List.of(new ReplayActor(RED, "Red", null, null, null), new ReplayActor(BLUE, "Blue", null, null, null)),
                List.of(red.build(), blue.build()), marks, List.of(), null);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
