package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.npc.NpcPose;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an NPC always goes away, and only once.
 *
 * <p>This is the module's whole risk. An NPC is a packet, so the server has no
 * record of it and nothing else will clean it up: one whose removal is skipped
 * stands there wearing somebody's name until that player relogs. Every way out
 * of the queue is asserted here — its life ending, its plugin being disabled,
 * the server stopping, and somebody taking it away by hand.
 */
class NpcLifetimeTest {

    /** One viewer, which is never dereferenced: the sink records rather than sends. */
    private static final List<Player> SOMEBODY = Arrays.asList((Player) null);

    private final List<String> sent = new ArrayList<>();
    private long now;

    private final NpcSink sink = new NpcSink() {
        @Override
        public void announce(List<Player> viewers, NpcModel model) {
            sent.add("announce");
        }

        @Override
        public void spawn(List<Player> viewers, int entityId, NpcModel model, Location at) {
            sent.add("spawn");
        }

        @Override
        public void look(List<Player> viewers, int entityId, float yaw, float pitch) {
            sent.add("look");
        }

        @Override
        public void move(List<Player> viewers, int entityId,
                         double dx, double dy, double dz, float yaw, float pitch) {
            sent.add("move");
        }

        @Override
        public void hurt(List<Player> viewers, int entityId) {
            sent.add("hurt");
        }

        @Override
        public void swing(List<Player> viewers, int entityId) {
            sent.add("swing");
        }

        @Override
        public void pose(List<Player> viewers, int entityId, NpcModel model, NpcPose pose) {
            sent.add("pose");
        }

        @Override
        public void destroy(List<Player> viewers, int entityId, UUID profile) {
            sent.add("destroy");
        }
    };

    @BeforeEach
    void setUp() {
        sent.clear();
        now = 0L;
        NpcRuntime.testHooks(() -> now, () -> 1, sink);
        NpcRuntime.releaseAll();
        sent.clear();
    }

    @AfterEach
    void tearDown() {
        NpcRuntime.releaseAll();
        NpcRuntime.testHooks(null, null, null);
    }

    private NpcHandle show(String owner, long life) {
        return show(owner, life, NpcMotion.still());
    }

    private NpcHandle show(String owner, long life, NpcMotion motion) {
        return NpcRuntime.show(owner, NpcModel.of("Body", "texture", null), motion,
                new Location(null, 0, 0, 0), life, SOMEBODY);
    }

    @Test
    @DisplayName("it is drawn, and eventually taken away")
    void spawnsAndExpires() {
        NpcHandle npc = show("Test", 1000);

        assertNotNull(npc);
        assertEquals(List.of("announce"), sent,
                "the body was drawn in the same burst as the identity it hangs on");
        assertTrue(npc.isShowing());

        sent.clear();
        now = 50L;
        NpcRuntime.tick();
        assertEquals(List.of("spawn"), sent, "the body was never drawn");

        sent.clear();
        now = 999L;
        NpcRuntime.tick();
        assertTrue(sent.isEmpty(), "it went early");
        assertEquals(1, NpcRuntime.active());

        now = 1000L;
        NpcRuntime.tick();
        assertEquals(List.of("destroy"), sent);
        assertFalse(npc.isShowing());
        assertEquals(0, NpcRuntime.active(), "a body that was destroyed is still in the queue");
    }

    @Test
    @DisplayName("taking it away by hand happens once, and the driver does not repeat it")
    void removedByHandOnce() {
        NpcHandle npc = show("Test", 10_000);
        sent.clear();

        npc.remove();
        assertEquals(List.of("destroy"), sent);

        sent.clear();
        npc.remove();
        now = 20_000L;
        NpcRuntime.tick();
        assertTrue(sent.isEmpty(), "a body must not be destroyed twice");
        assertEquals(0, NpcRuntime.active());
    }

    @Test
    @DisplayName("disabling a plugin takes away its own and leaves everyone else's")
    void releaseIsPerPlugin() {
        show("Mine", 60_000);
        show("Yours", 60_000);
        sent.clear();

        NpcRuntime.release("Mine");

        assertEquals(List.of("destroy"), sent);
        assertEquals(1, NpcRuntime.active());

        NpcRuntime.releaseAll();
        assertEquals(0, NpcRuntime.active());
    }

    @Test
    @DisplayName("a life outside anything a file could mean is brought back inside it")
    void lifeIsClamped() {
        show("Test", 5_000_000);
        sent.clear();

        now = 130_000L;
        NpcRuntime.tick();

        assertEquals(List.of("destroy"), sent, "a mistyped life left a crowd standing about");
    }

    @Test
    @DisplayName("nobody watching is nothing sent")
    void noViewersNoNpc() {
        assertNull(NpcRuntime.show("Test", NpcModel.of("Body", "texture", null),
                NpcMotion.still(), new Location(null, 0, 0, 0), 1000, List.of()));

        assertTrue(sent.isEmpty());
        assertEquals(0, NpcRuntime.active());
    }

    @Test
    @DisplayName("a still body is never written to after it appears")
    void stillBodiesCostNothing() {
        show("Test", 5000);
        now = 50L;
        NpcRuntime.tick();
        sent.clear();

        for (now = 100; now < 4000; now += 50) {
            NpcRuntime.tick();
        }

        assertTrue(sent.isEmpty(), "a body that does nothing was sent " + sent.size() + " packets");
    }

    @Test
    @DisplayName("a body that was thrown is moved, and stops being moved once it lands")
    void thrownBodiesAreDriven() {
        show("Test", 4000, NpcMotion.builder()
                .over(500).to(0, 0, -3).gravity(8).hurt(true).build());

        assertEquals(List.of("announce"), sent);

        sent.clear();
        now = 50L;
        NpcRuntime.tick();
        assertEquals(List.of("spawn", "hurt", "move"), sent,
                "it was not drawn, did not flinch, and did not start moving together");

        sent.clear();
        for (now = 100; now <= 500; now += 50) {
            NpcRuntime.tick();
        }
        assertEquals(9, sent.size(), "the throw was not driven every tick");
        assertTrue(sent.stream().allMatch("move"::equals));

        sent.clear();
        for (now = 550; now < 3000; now += 50) {
            NpcRuntime.tick();
        }
        assertTrue(sent.isEmpty(), "it was still being moved after it landed");
    }

    @Test
    @DisplayName("a body that slumps changes pose once, when it was told to")
    void slumpingChangesPoseOnce() {
        show("Test", 4000, NpcMotion.builder()
                .pose(NpcPose.STANDING)
                .over(0)
                .collapsing(NpcPose.LYING, 600)
                .build());
        now = 50L;
        NpcRuntime.tick();
        sent.clear();

        for (now = 100; now < 600; now += 50) {
            NpcRuntime.tick();
        }
        assertTrue(sent.isEmpty(), "it went down before it was told to");

        now = 600L;
        NpcRuntime.tick();
        assertEquals(List.of("pose"), sent);

        sent.clear();
        for (now = 650; now < 3000; now += 50) {
            NpcRuntime.tick();
        }
        assertTrue(sent.isEmpty(), "it kept being re-posed");
    }

    @Test
    @DisplayName("a body that is thrown later stands still until it is")
    void movementCanWait() {
        show("Test", 4000, NpcMotion.builder()
                .startAfter(600).over(400).to(0, 0, -2).hurt(true).build());
        now = 50L;
        NpcRuntime.tick();
        assertEquals(List.of("announce", "spawn"), sent,
                "it flinched or moved before anything had happened to it");

        sent.clear();
        for (now = 100; now < 600; now += 50) {
            NpcRuntime.tick();
        }
        assertTrue(sent.isEmpty(), "it moved before it was thrown");

        now = 600L;
        NpcRuntime.tick();
        assertEquals(List.of("hurt"), sent, "it did not flinch when it was thrown");

        sent.clear();
        now = 650L;
        NpcRuntime.tick();
        assertEquals(List.of("move"), sent, "it flinched but never went anywhere");
    }

    @Test
    @DisplayName("a pose or a look on a body that has gone sends nothing")
    void goneBodiesAreSilent() {
        NpcHandle npc = show("Test", 1000);
        npc.remove();
        sent.clear();

        npc.look(90f, 0f);
        npc.pose(NpcPose.STANDING);

        assertTrue(sent.isEmpty(), "a body that has gone was still being written to");
    }
}
