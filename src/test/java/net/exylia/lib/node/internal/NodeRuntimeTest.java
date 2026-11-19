package net.exylia.lib.node.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.node.ClientNode;
import net.exylia.lib.node.ClientNodes;
import net.exylia.lib.task.Tasks;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRuntimeTest {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<String> clicks = new CopyOnWriteArrayList<>();
    private Plugin plugin;
    private World world;
    private FakePlayer farmer;
    private ClientNodes nodes;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("NodeTest", null);
        AtomicInteger ids = new AtomicInteger(5000);
        NodeRuntime.testHooks(Tasks.of(plugin), ids::getAndIncrement, new NodeSink() {
            @Override public void spawn(NodeImpl node) { sent.add("spawn " + node.displayId); }
            @Override public void despawn(NodeImpl node) { sent.add("despawn " + node.displayId); }
            @Override public void look(NodeImpl node) { sent.add("look " + node.displayId); }
            @Override public void glow(NodeImpl node) { sent.add("glow " + node.glowColor()); }
            @Override public void scale(NodeImpl node, float size, int ticks) { sent.add("scale " + size); }
        });
        world = FakeServer.newWorld("world");
        farmer = new FakePlayer("Farmer").at(new Location(world, 0, 64, 0));
        FakeServer.online(farmer.player());
        nodes = ClientNodes.of(plugin).range(32)
                .onClick((viewer, node, click) -> clicks.add(viewer.getName() + " " + click));
    }

    @AfterEach
    void tearDown() {
        NodeRuntime.removeEverything();
        FakeServer.reset();
    }

    private static BlockData wheat() {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "wheat";
                });
    }

    private static void pass() {
        FakeServer.tick((int) NodeRuntime.PERIOD_TICKS + 1);
    }

    @Test
    void sentOnlyWhileTheViewerIsNear() {
        NodeImpl node = (NodeImpl) nodes.block(farmer.player(), new Location(world, 10.7, 64, 0), wheat());
        assertEquals(10, node.location().getBlockX());
        pass();
        assertEquals(List.of("spawn " + node.displayId), sent);

        farmer.at(new Location(world, 40, 64, 0)); // 29.5 from its centre: still sent
        pass();
        assertEquals(1, sent.size());

        farmer.at(new Location(world, 50, 64, 0)); // past the range and the margin
        pass();
        assertEquals("despawn " + node.displayId, sent.get(1));

        farmer.at(new Location(world, 44, 64, 0)); // inside the margin, outside the range: stays off
        pass();
        assertEquals(2, sent.size());

        farmer.at(new Location(world, 41, 64, 0));
        pass();
        assertEquals("spawn " + node.displayId, sent.get(2));
    }

    @Test
    void changesReachTheClientOnlyWhileSent() {
        ClientNode node = nodes.block(farmer.player(), new Location(world, 100, 64, 0), wheat());
        node.glow(Color.YELLOW);
        node.look(wheat());
        assertTrue(sent.isEmpty());

        farmer.at(new Location(world, 98, 64, 0));
        pass();
        node.glow(Color.YELLOW); // unchanged: no packet
        node.glow(null);
        assertEquals(List.of("spawn " + ((NodeImpl) node).displayId, "glow null"), sent);
    }

    @Test
    void clicksCountForTheViewerWithinReach() {
        NodeImpl node = (NodeImpl) nodes.block(farmer.player(), new Location(world, 3, 64, 0), wheat());
        FakePlayer stranger = new FakePlayer("Stranger").at(new Location(world, 3, 64, 0));
        FakeServer.online(stranger.player());

        NodeRuntime.clicked(farmer.player(), node.hitboxId, Click.LEFT);
        NodeRuntime.clicked(stranger.player(), node.hitboxId, Click.LEFT);
        farmer.at(new Location(world, 20, 64, 0));
        NodeRuntime.clicked(farmer.player(), node.hitboxId, Click.RIGHT);
        FakeServer.tick(2);

        assertEquals(List.of("Farmer LEFT"), clicks);
    }

    @Test
    void aRespawnSendsEveryNodeAgain() {
        NodeImpl node = (NodeImpl) nodes.block(farmer.player(), new Location(world, 2, 64, 0), wheat());
        pass();
        NodeRuntime.respawned(farmer.player());
        pass();
        assertEquals(List.of("spawn " + node.displayId, "spawn " + node.displayId), sent);
    }

    @Test
    void leavingAndDisablingForgetEveryNode() {
        NodeImpl node = (NodeImpl) nodes.block(farmer.player(), new Location(world, 2, 64, 0), wheat());
        NodeRuntime.forget(farmer.player());
        assertTrue(node.isRemoved());
        assertFalse(NodeRuntime.owns(node.hitboxId));

        NodeImpl other = (NodeImpl) nodes.block(farmer.player(), new Location(world, 2, 65, 0), wheat());
        pass();
        ClientNodes.release(plugin);
        assertTrue(other.isRemoved());
        assertEquals("despawn " + other.displayId, sent.get(sent.size() - 1));
    }
}
