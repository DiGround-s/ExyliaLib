package net.exylia.lib.display.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A display that changes after it was spawned: where it is mid-flight, a new
 * motion from there, a new look, and viewers who come and go.
 */
class RetargetAndTrackTest {

    private final List<String> sent = new ArrayList<>();
    private final List<DisplayKeyframe> posed = new ArrayList<>();

    private final DisplaySink sink = new DisplaySink() {
        @Override
        public void spawn(List<Player> viewers, int entityId, DisplayModel model,
                          Location at, DisplayKeyframe pose) {
            sent.add("spawn x" + viewers.size() + " at " + pose.x());
        }

        @Override
        public void pose(List<Player> viewers, int entityId, DisplayModel model,
                         DisplayKeyframe pose, int overTicks) {
            posed.add(pose);
            sent.add("pose x" + viewers.size() + " to " + pose.x() + " over " + overTicks);
        }

        @Override
        public void mount(List<Player> viewers, int vehicleId, int[] passengers) {
        }

        @Override
        public void restyle(List<Player> viewers, int entityId, DisplayModel model) {
            sent.add("restyle glow " + model.glowArgb());
        }

        @Override
        public void destroy(List<Player> viewers, int entityId) {
            sent.add("destroy x" + viewers.size());
        }
    };

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    /** From x=0 to x=10 in a straight line over a second. */
    private LiveDisplay sliding(List<Player> viewers) {
        DisplayMotion motion = DisplayMotion.of(List.of(
                new DisplayKeyframe(0, 0, 0, 0, Rotation.NONE, 1, 1, 1),
                new DisplayKeyframe(1000, 10, 0, 0, Rotation.NONE, 1, 1, 1)), 5000);
        LiveDisplay display = new LiveDisplay("Test", 7, DisplayModel.text(Component.empty()),
                motion, viewers, 0L, 0);
        display.spawn(sink, new Location(null, 0, 0, 0));
        display.advance(sink, 0L);
        sent.clear();
        posed.clear();
        return display;
    }

    @Test
    @DisplayName("mid-flight it is where the client is drawing it")
    void poseMidFlight() {
        LiveDisplay display = sliding(List.of());

        assertEquals(0f, display.poseAt(0L).x(), 1e-6);
        assertEquals(2.5f, display.poseAt(250L).x(), 1e-6);
        assertEquals(10f, display.poseAt(4000L).x(), 1e-6, "arrived and holding");
    }

    @Test
    @DisplayName("a new motion starts from the pose on screen, not from its own first pose")
    void retargetStartsWhereItIs() {
        LiveDisplay display = sliding(List.of());
        DisplayMotion stop = DisplayMotion.of(List.of(
                new DisplayKeyframe(0, 99, 0, 0, Rotation.NONE, 1, 1, 1),
                new DisplayKeyframe(500, 3, 0, 0, Rotation.NONE, 1, 1, 1)), 800);

        display.retarget(sink, 500L, stop, false);

        assertEquals(List.of("pose x0 to 3.0 over 10"), sent);
        assertEquals(5f, display.poseAt(500L).x(), 1e-6, "the seam is where it was, not x=99");
        assertEquals(4f, display.poseAt(750L).x(), 1e-6);

        // The life is the new motion's from now on: 500 + 800 + one tick.
        assertTrue(!display.advance(sink, 1300L));
        assertTrue(display.advance(sink, 1350L));
    }

    @Test
    @DisplayName("moving to a pose keeps the life that was left when it outlasts the move")
    void retargetCanKeepLife() {
        LiveDisplay display = sliding(List.of());
        display.retarget(sink, 200L, DisplayMotion.between(display.poseAt(200L),
                new DisplayKeyframe(0, 1, 0, 0, Rotation.NONE, 1, 1, 1), 300,
                DisplayMotion.Easing.OUT), true);

        assertTrue(!display.advance(sink, 3000L), "still within the 5 s it was given");
    }

    @Test
    @DisplayName("a new look is sent as metadata, and a model of another kind is refused")
    void restyle() {
        LiveDisplay display = sliding(List.of());

        display.restyle(sink, display.model().glow(0xFF00AA));
        display.restyle(sink, DisplayModel.block((org.bukkit.block.data.BlockData) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.bukkit.block.data.BlockData.class},
                (proxy, method, args) -> null)));

        assertEquals(List.of("restyle glow " + 0xFF00AA), sent);
        assertEquals(0xFF00AA, display.model().glowArgb());
    }

    @Test
    @DisplayName("someone who walks up is shown the frame everyone sees; someone who leaves loses it")
    void tracking() {
        Player first = new FakePlayer("First").player();
        Player second = new FakePlayer("Second").player();
        LiveDisplay display = sliding(List.of(first));

        int gained = display.track(sink, 500L, List.of(first, second), Integer.MAX_VALUE);

        assertEquals(1, gained);
        assertEquals(List.of("spawn x1 at 5.0", "pose x1 to 10.0 over 10"), sent);
        assertEquals(2, display.viewers().size());

        sent.clear();
        int lost = display.track(sink, 600L, List.of(second), Integer.MAX_VALUE);

        assertEquals(-1, lost);
        assertEquals(List.of("destroy x1"), sent);
        assertEquals(List.of(second), display.viewers());
    }

    @Test
    @DisplayName("nobody joins past the budget")
    void trackingRespectsBudget() {
        Player first = new FakePlayer("First").player();
        Player second = new FakePlayer("Second").player();
        LiveDisplay display = sliding(List.of());

        assertEquals(1, display.track(sink, 100L, List.of(first, second), 1));
        assertEquals(1, display.viewers().size());
    }

    @Test
    @DisplayName("the audience is whoever is in range, in that world, and passes the test")
    void audienceReadsTheWorld() {
        FakeServer.install();
        World world = FakeServer.newWorld("arena");
        FakePlayer near = new FakePlayer("Near").at(new Location(world, 3, 64, 0));
        FakePlayer far = new FakePlayer("Far").at(new Location(world, 40, 64, 0));
        FakePlayer muted = new FakePlayer("Muted").at(new Location(world, 1, 64, 0));
        FakeServer.online(near.player(), far.player(), muted.player());

        List<Player> present = new DisplayRuntime.Audience(10, viewer -> viewer != muted.player())
                .at(new Location(world, 0, 64, 0));

        assertEquals(List.of(near.player()), present);
    }
}
