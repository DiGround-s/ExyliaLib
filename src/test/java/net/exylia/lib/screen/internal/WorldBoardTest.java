package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.Screen;
import net.exylia.lib.spatial.SpatialClick;
import net.exylia.lib.stage.StagePlane;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBoardTest {

    private static final double UNIT = 0.01; // a 400 px screen 4 blocks wide

    private static StagePlane plane(float facing) {
        return new StagePlane(null, 0, 64, 0, facing + 180, 1, 1, 1, 1);
    }

    @Test
    void aHitLandsOnTheLayoutPixelUnderTheCrosshair() {
        // Facing south, seen from the south looking north: half a block right and up of the middle.
        double[] at = WorldBoard.pixel(plane(0).hit(0.5, 64.5, 5, 0, 0, -1), UNIT, 400, 225);
        assertArrayEquals(new double[]{250, 62.5}, at, 1e-9);
        // Facing west, seen from the west looking east: the viewer's right is south.
        at = WorldBoard.pixel(plane(90).hit(-5, 63.5, 0.5, 1, 0, 0), UNIT, 400, 225);
        assertArrayEquals(new double[]{250, 162.5}, at, 1e-9);
        // From behind, and off the panel's edge, nothing.
        assertNull(WorldBoard.pixel(plane(0).hit(0, 64, -5, 0, 0, -1), UNIT, 400, 225));
        assertNull(WorldBoard.pixel(plane(0).hit(3, 64, 5, 0, 0, -1), UNIT, 400, 225));
    }

    @Test
    void onlyAButtonIsPickedNeverTheEmptyPanel() {
        Node root = Node.column(Node.text("TOP").id("title"),
                Node.row(Node.box().size(20, 20).id("prev").onClick(e -> { }),
                        Node.box().grow(1),
                        Node.box().size(20, 20).id("next").onClick(e -> { })));
        List<Layout.Placed> placed = Layout.lay(root, 200, 100);
        Layout.Placed prev = placed.stream().filter(p -> "prev".equals(p.node().id())).findFirst().orElseThrow();
        Layout.Placed next = placed.stream().filter(p -> "next".equals(p.node().id())).findFirst().orElseThrow();
        assertEquals(prev, placed.get(WorldBoard.button(placed, prev.x() + 5, prev.y() + 5)));
        assertEquals(next, placed.get(WorldBoard.button(placed, next.x() + 5, next.y() + 5)));
        assertEquals(-1, WorldBoard.button(placed, 100, prev.y() + 5));
        assertEquals(-1, WorldBoard.button(placed, 2, 2));
    }

    @Test
    void eachViewerTurnsTheirOwnPageAndAGoneRowIsDestroyed() {
        withMappings(this::pages);
    }

    @Test
    void theHoveredButtonIsLitForItsViewerOnly() {
        withMappings(this::hover);
    }

    private static void withMappings(Runnable test) {
        var previous = com.github.retrooper.packetevents.PacketEvents.getAPI();
        if (previous == null) com.github.retrooper.packetevents.PacketEvents.setAPI(new ScreenWireTest.MappingsOnly());
        try {
            test.run();
        } finally {
            com.github.retrooper.packetevents.PacketEvents.setAPI(previous);
        }
    }

    private void pages() {
        FakeServer.install();
        FakeServer.reset();
        // Page 1 has three rows, page 2 one: turning the page must destroy the two rows it no longer has.
        Screen screen = Screen.of(session -> {
            int page = session.state("page", 1);
            List<Node> rows = new ArrayList<>();
            rows.add(Node.text("page " + page).id("title"));
            for (int i = 0; i < (page == 1 ? 3 : 1); i++) rows.add(Node.text("row " + i).background("#201830"));
            rows.add(Node.box().size(20, 20).background("#8a51c4").id("next")
                    .onClick(e -> e.session().set("page", page + 1)));
            return Node.column(rows.toArray(Node[]::new)).background("#0b0912");
        });
        WorldBoard board = new WorldBoard(FakeServer.newPlugin("WorldBoard"), new Location(null, 0, 64, 0), screen, 4);
        AtomicInteger ids = new AtomicInteger(1000);
        List<PacketWrapper<?>> toA = new ArrayList<>(), toB = new ArrayList<>();
        WorldBoard.View a = board.new View(new FakePlayer("A").player(),
                new ScreenPackets.Wire(toA::addAll), ids::incrementAndGet);
        WorldBoard.View b = board.new View(new FakePlayer("B").player(),
                new ScreenPackets.Wire(toB::addAll), ids::incrementAndGet);
        a.render();
        b.render();
        int spawned = count(toA, WrapperPlayServerSpawnEntity.class);
        assertTrue(spawned >= 8, "panel, 3 rows with text, title, button: " + spawned);

        int button = indexOf(a, "next");
        toA.clear();
        a.press(new SpatialClick(SpatialClick.Kind.LEFT, false, button));
        assertEquals(2, a.state("page", 0));
        assertEquals(0, b.state("page", 0), "B never turned a page");
        // Two rows went, each a fill and a text; nothing new was spawned.
        WrapperPlayServerDestroyEntities destroy = toA.stream().filter(WrapperPlayServerDestroyEntities.class::isInstance)
                .map(WrapperPlayServerDestroyEntities.class::cast).findFirst().orElseThrow();
        assertEquals(4, destroy.getEntityIds().length);
        assertEquals(0, count(toA, WrapperPlayServerSpawnEntity.class));
        // Only what changed was sent: the title's text, the moved button, never the panel's untouched fill.
        assertTrue(count(toA, WrapperPlayServerEntityMetadata.class) < spawned);

        toB.clear();
        b.render();
        assertEquals(0, toB.size(), "B's board did not change");
    }

    private void hover() {
        FakeServer.install();
        FakeServer.reset();
        Screen screen = Screen.of(session -> Node.column(Node.box().size(40, 20).background("#201830")
                .hover("#8a51c4").id("go").onClick(e -> { })).background("#0b0912"));
        WorldBoard board = new WorldBoard(FakeServer.newPlugin("WorldBoardHover"), new Location(null, 0, 64, 0), screen, 4);
        AtomicInteger ids = new AtomicInteger(1000);
        List<PacketWrapper<?>> toA = new ArrayList<>();
        WorldBoard.View a = board.new View(new FakePlayer("A").player(),
                new ScreenPackets.Wire(toA::addAll), ids::incrementAndGet);
        a.render();
        toA.clear();
        a.hover(indexOf(a, "go"));
        assertTrue(count(toA, WrapperPlayServerEntityMetadata.class) > 0);
        toA.clear();
        a.hover(-1);
        a.hover(-1);
        assertTrue(count(toA, WrapperPlayServerEntityMetadata.class) > 0, "unlit once");
    }

    private static int indexOf(WorldBoard.View view, String id) {
        List<Layout.Placed> placed = view.placed();
        for (int i = 0; i < placed.size(); i++) if (id.equals(placed.get(i).node().id())) return i;
        throw new AssertionError(id);
    }

    private static int count(List<PacketWrapper<?>> packets, Class<?> type) {
        return (int) packets.stream().filter(type::isInstance).count();
    }
}
