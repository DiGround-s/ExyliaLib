package net.exylia.lib.packet.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.packet.BlockMasks;
import net.exylia.lib.packet.Packets;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who is sent a masked block as it is, and when the mask comes back. */
class MasksTest {

    /** Records the masks sent, by viewer name. */
    private static final class RecordingSink implements PacketSink {

        final List<String> sent = new ArrayList<>();

        @Override public void despawn(Player viewer, int entityId, UUID profile) { }
        @Override public int newEntityId() { return 0; }
        @Override public void blocks(Player viewer, SectionGroups.Section section, List<Location> positions,
                                     Map<Location, BlockData> data) {
            for (Location at : positions) sent.add(viewer.getName() + "@" + at.getBlockX());
        }
        @Override public void glowingBlock(Player viewer, int entityId, Location at, BlockData data, int argb) { }
        @Override public void destroyEntities(Player viewer, int[] entityIds) { }
        @Override public void gameMode(Player viewer, int mode) { }
        @Override public void sidebarSlot(Player viewer, String objectiveName) { }
        @Override public void abilities(Player viewer, boolean invulnerable, boolean flying, boolean allowFlight,
                                        float flySpeed) { }
        @Override public void border(Player viewer, double x, double z, double from, double to, long millis,
                                     int warningBlocks, int warningSeconds) { }
        @Override public void close() { }
    }

    /** The fake server makes no block data: a bare one stands in. */
    private static final BlockData STONE = (BlockData) java.lang.reflect.Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> 1;
                default -> null;
            });

    private World world;
    private Plugin plugin;
    private FakePlayer alice;
    private FakePlayer bob;
    private RecordingSink sink;
    private BlockMasks masks;
    private Location spawner;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.releaseAll();
        world = FakeServer.newWorld("world");
        plugin = FakeServer.newPlugin("Spawners");
        alice = new FakePlayer("Alice").at(new Location(world, 0, 64, 0));
        bob = new FakePlayer("Bob").at(new Location(world, 14, 64, 0));
        FakeServer.online(alice.player(), bob.player());
        sink = new RecordingSink();
        PacketRuntime.install(sink);
        PacketRuntime.init(plugin);
        masks = Packets.of(plugin).blockMasks();
        spawner = new Location(world, 2, 64, 0);
    }

    @AfterEach
    void tearDown() {
        Packets.releaseAll();
        PacketRuntime.shutdown();
    }

    private Masks.Mask hiddenFor(FakePlayer player) {
        return Masks.hiddenAt(player.player().getUniqueId(), world.getUID(), 2, 64, 0);
    }

    @Test
    @DisplayName("a near player keeps the truth, a far one is sent the mask at once")
    void maskSplitsNearAndFar() {
        masks.mask(spawner, STONE, 8);

        assertTrue(masks.isMasked(spawner));
        assertNull(hiddenFor(alice));
        assertNotNull(hiddenFor(bob));
        assertEquals(List.of("Bob@2"), sink.sent);
        assertTrue(masks.isRevealed(alice.player(), spawner));
    }

    @Test
    @DisplayName("walking away brings the mask back past the margin, walking up takes it off")
    void passFollowsPlayers() {
        masks.mask(spawner, STONE, 8);
        sink.sent.clear();

        alice.at(new Location(world, 11, 64, 0)); // 9 away: inside the 2-block margin
        Masks.pass();
        assertNull(hiddenFor(alice));

        alice.at(new Location(world, 30, 64, 0));
        bob.at(new Location(world, 5, 64, 0));
        Masks.pass();
        assertNotNull(hiddenFor(alice));
        assertNull(hiddenFor(bob));
        assertEquals(List.of("Alice@2"), sink.sent);
    }

    @Test
    @DisplayName("unmasking forgets the block; another plugin cannot unmask it")
    void unmaskIsPerPlugin() {
        masks.mask(spawner, STONE, 8);
        Packets.of(FakeServer.newPlugin("Other")).blockMasks().unmaskAll();
        assertTrue(masks.isMasked(spawner));

        masks.unmask(spawner);
        assertFalse(masks.isMasked(spawner));
        assertNull(hiddenFor(bob));
        assertFalse(Masks.any());
    }
}
