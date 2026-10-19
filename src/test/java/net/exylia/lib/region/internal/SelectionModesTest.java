package net.exylia.lib.region.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.Cylinder;
import net.exylia.lib.region.Polygon;
import net.exylia.lib.region.SelectionMode;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.SelectionResult;
import net.exylia.lib.region.SelectionSession;
import net.exylia.lib.region.SelectionState;
import net.exylia.lib.region.WorldIdentity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the clicks mean in every shape, and what the caller is answered with.
 */
class SelectionModesTest {

    private static final WorldIdentity WORLD = new WorldIdentity(UUID.randomUUID(), "world");

    @AfterEach
    void cleanRuntime() {
        SelectionRuntime.releaseAll();
    }

    @Test
    @DisplayName("a polygon waits from its third point, and a right click takes the last one back")
    void polygonPoints() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, options(SelectionMode.POLYGON));

        assertTrue(SelectionRuntime.primary(playerId, at(0, 64, 0)));
        assertTrue(SelectionRuntime.primary(playerId, at(10, 66, 0)));
        assertEquals(SelectionState.ACTIVE, session.state());
        assertTrue(SelectionRuntime.primary(playerId, at(5, 65, 8)));
        assertEquals(SelectionState.AWAITING_CONFIRMATION, session.state());
        assertEquals(3, session.points().size());

        assertTrue(SelectionRuntime.secondary(playerId, null), "undo works in the air");
        assertEquals(SelectionState.ACTIVE, session.state());
        assertFalse(SelectionRuntime.confirm(playerId));

        SelectionRuntime.primary(playerId, at(5, 65, 8));
        assertTrue(SelectionRuntime.confirm(playerId));

        SelectionResult result = session.result().toCompletableFuture().join();
        Polygon polygon = assertInstanceOf(Polygon.class, result.shape());
        assertEquals(3, polygon.vertices().size());
        // The corners are the box around it, so a caller reading cuboid() keeps every block.
        assertEquals(Cuboid.blocks(0, 64, 0, 10, 66, 8), result.cuboid());
    }

    @Test
    @DisplayName("an outline waits to be confirmed even when the options answer on the last click")
    void outlineAlwaysConfirms() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId,
                options(SelectionMode.POLYGON).toBuilder().requireConfirmation(false).build());

        SelectionRuntime.primary(playerId, at(0, 0, 0));
        SelectionRuntime.primary(playerId, at(4, 0, 0));
        SelectionRuntime.primary(playerId, at(4, 0, 4));

        assertEquals(SelectionState.AWAITING_CONFIRMATION, session.state());
    }

    @Test
    @DisplayName("a circle is a centre and an edge, and its result holds every block of it")
    void circle() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, options(SelectionMode.CIRCLE));

        SelectionRuntime.primary(playerId, at(0, 64, 0));
        SelectionRuntime.secondary(playerId, at(4, 66, 0));
        assertEquals(SelectionState.AWAITING_CONFIRMATION, session.state());
        assertTrue(SelectionRuntime.confirm(playerId));

        SelectionResult result = session.result().toCompletableFuture().join();
        Cylinder cylinder = assertInstanceOf(Cylinder.class, result.shape());
        assertEquals(4.5, cylinder.radius());
        assertEquals(Cuboid.blocks(-4, 64, -4, 4, 66, 4), result.cuboid());
    }

    @Test
    @DisplayName("a box keeps answering with the exact clicked corners")
    void boxIsUnchanged() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, options(SelectionMode.CUBOID));

        SelectionRuntime.select(playerId, true, at(9, 70, 1));
        SelectionRuntime.select(playerId, false, at(2, 60, 5));
        SelectionRuntime.confirm(playerId);

        assertEquals(new SelectionResult(WORLD, at(9, 70, 1), at(2, 60, 5)),
                session.result().toCompletableFuture().join());
    }

    @Test
    @DisplayName("shift + right-click goes to the next shape and starts over")
    void nextMode() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, SelectionOptions.builder()
                .modes(SelectionMode.CUBOID, SelectionMode.POLYGON)
                .giveSelector(false).feedback(false).previewParticle(null)
                .build());

        SelectionRuntime.primary(playerId, at(1, 1, 1));
        assertTrue(SelectionRuntime.nextMode(playerId));
        assertEquals(SelectionMode.POLYGON, session.mode());
        assertTrue(session.first().isEmpty(), "the corner was left behind");

        assertTrue(SelectionRuntime.nextMode(playerId));
        assertEquals(SelectionMode.CUBOID, session.mode(), "and round again");
    }

    @Test
    @DisplayName("one shape on offer cannot be changed")
    void singleMode() {
        UUID playerId = UUID.randomUUID();
        begin(playerId, options(SelectionMode.CUBOID));

        assertFalse(SelectionRuntime.nextMode(playerId));
    }

    @Test
    @DisplayName("a freehand pen puts a point down every couple of blocks and straight runs keep only their ends")
    void freehand() {
        UUID playerId = UUID.randomUUID();
        SelectionSession session = begin(playerId, options(SelectionMode.FREEHAND));
        SelectionRuntime.Session pen = SelectionRuntime.routed(playerId);

        assertTrue(SelectionRuntime.primary(playerId, at(0, 64, 0)), "pen down on a block");
        pen.look(at(1, 64, 0));
        assertEquals(1, session.points().size(), "one block is not far enough");
        pen.look(at(2, 64, 0));
        pen.look(at(4, 64, 0));
        pen.look(at(6, 64, 0));
        assertEquals(List.of(at(0, 64, 0), at(6, 64, 0)), session.points(), "a straight run");

        pen.look(at(6, 64, 6));
        pen.look(at(0, 64, 6));
        assertEquals(SelectionState.ACTIVE, session.state(), "still drawing");

        assertTrue(SelectionRuntime.primary(playerId, null), "pen up in the air");
        assertEquals(SelectionState.AWAITING_CONFIRMATION, session.state());
        pen.look(at(0, 64, 20));
        assertEquals(4, session.points().size(), "a lifted pen draws nothing");
    }

    private static SelectionOptions options(SelectionMode mode) {
        return SelectionOptions.builder()
                .modes(mode)
                .giveSelector(false)
                .feedback(false)
                .previewParticle(null)
                .build();
    }

    private static SelectionSession begin(UUID playerId, SelectionOptions options) {
        return SelectionRuntime.begin(plugin(), player(playerId), options);
    }

    private static BlockPosition at(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "ModesOwner";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }
}
