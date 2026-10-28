package net.exylia.lib.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Picking an instance per mode, cap and age, and the heartbeat's wire form. */
class InstancesTest {

    private static final long NOW = 100_000;
    private static final long MAX_AGE = 6_000;

    private static Instance instance(String server, int players, int capacity, long seenAt) {
        return new Instance(server, true, 20, new Instance.Load(players, capacity),
                Map.of("castle", new Instance.Load(players, 0)), seenAt);
    }

    private static String pick(List<Instance> instances, Instances.Mode mode, String part, String preferred) {
        return Instances.pick(instances, mode, part, preferred, NOW, MAX_AGE).map(Instance::server).orElse(null);
    }

    @Test
    @DisplayName("Fill first takes the fullest instance below its cap; least players the emptiest")
    void modes() {
        List<Instance> pool = List.of(instance("a", 3, 10, NOW), instance("b", 9, 10, NOW), instance("c", 10, 10, NOW));
        assertEquals("b", pick(pool, Instances.Mode.FILL_FIRST, null, null));
        assertEquals("a", pick(pool, Instances.Mode.LEAST_PLAYERS, null, null));
    }

    @Test
    @DisplayName("A full, closed or stale instance takes nobody")
    void filters() {
        Instance closed = new Instance("closed", false, 20, Instance.Load.EMPTY, Map.of(), NOW);
        List<Instance> pool = List.of(instance("full", 10, 10, NOW), instance("stale", 1, 10, NOW - MAX_AGE - 1), closed);
        assertNull(pick(pool, Instances.Mode.FILL_FIRST, null, null));
        assertNull(pick(pool, Instances.Mode.LEAST_PLAYERS, null, null));
        assertTrue(Instances.isFresh(instance("edge", 0, 0, NOW - MAX_AGE), NOW, MAX_AGE));
    }

    @Test
    @DisplayName("A part narrows the pool to instances that have it with room, and counts its players")
    void parts() {
        Instance a = new Instance("a", true, 20, new Instance.Load(20, 0),
                Map.of("castle", new Instance.Load(2, 0), "desert", new Instance.Load(18, 0)), NOW);
        Instance b = new Instance("b", true, 20, new Instance.Load(5, 0),
                Map.of("castle", new Instance.Load(5, 5)), NOW);
        Instance c = new Instance("c", true, 20, new Instance.Load(1, 0),
                Map.of("castle", new Instance.Load(1, 0)), NOW);
        List<Instance> pool = List.of(a, b, c);
        // b's castle is at its cap: of a and c, a's castle has more players.
        assertEquals("a", pick(pool, Instances.Mode.FILL_FIRST, "castle", null));
        assertEquals("c", pick(pool, Instances.Mode.LEAST_PLAYERS, "castle", null));
        assertEquals("a", pick(pool, Instances.Mode.FILL_FIRST, "desert", null));
        assertNull(pick(pool, Instances.Mode.FILL_FIRST, "jungle", null));
    }

    @Test
    @DisplayName("A tie goes to the preferred instance, then the higher TPS, then the name")
    void ties() {
        Instance slow = new Instance("a", true, 15, new Instance.Load(4, 0), Map.of(), NOW);
        Instance fast = new Instance("b", true, 20, new Instance.Load(4, 0), Map.of(), NOW);
        Instance other = new Instance("c", true, 20, new Instance.Load(4, 0), Map.of(), NOW);
        List<Instance> pool = List.of(slow, fast, other);
        assertEquals("a", pick(pool, Instances.Mode.FILL_FIRST, null, "A"));
        assertEquals("b", pick(pool, Instances.Mode.FILL_FIRST, null, null));
        assertEquals("b", pick(pool, Instances.Mode.LEAST_PLAYERS, null, "nobody"));
    }

    @Test
    @DisplayName("A heartbeat reads back as it was written, stamped with the receiver's clock")
    void wire() {
        Instances.Status status = Instances.status(true, new Instance.Load(7, 40),
                Map.of("castle", new Instance.Load(4, 20), "bad\tid", new Instance.Load(1, 1)));
        Instance read = Instances.decode(Instances.encode("rk-2", status, 19.456), 1234);
        assertNotNull(read);
        assertEquals("rk-2", read.server());
        assertTrue(read.accepting());
        assertEquals(19.46, read.tps(), 1e-9);
        assertEquals(new Instance.Load(7, 40), read.total());
        assertEquals(Map.of("castle", new Instance.Load(4, 20)), read.parts());
        assertEquals(1234, read.seenAt());
        assertNull(Instances.decode("garbage", 0));
        assertNull(Instances.decode("x\t1\tnot-a-number\t1\t1", 0));
    }
}
