package net.exylia.lib.packet.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.packet.Packets;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How an outline of many blocks reaches the client. */
class BlockOutlinesTest {

    /** Counts what was drawn, and in which tick. */
    private static final class CountingSink implements PacketSink {

        private int ids;
        int drawn;
        final Map<Integer, Integer> perTick = new LinkedHashMap<>();

        @Override
        public void despawn(Player viewer, int entityId, UUID profile) {
        }

        @Override
        public void blocks(Player viewer, SectionGroups.Section section, List<Location> positions,
                           Map<Location, BlockData> data) {
        }

        @Override
        public int newEntityId() {
            return ++ids;
        }

        @Override
        public void glowingBlock(Player viewer, int entityId, Location at, BlockData data, int argb) {
            drawn++;
            perTick.merge(FakeServer.currentTick(), 1, Integer::sum);
        }

        @Override
        public void destroyEntities(Player viewer, int[] entityIds) {
        }

        @Override
        public void gameMode(Player viewer, int mode) {
        }

        @Override
        public void sidebarSlot(Player viewer, String objectiveName) {
        }

        @Override
        public void abilities(Player viewer, boolean invulnerable, boolean flying,
                              boolean allowFlight, float flySpeed) {
        }

        @Override
        public void close() {
        }
    }

    private World world;
    private Plugin plugin;
    private FakePlayer alice;
    private CountingSink sink;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.releaseAll();
        world = FakeServer.newWorld("world");
        plugin = FakeServer.newPlugin("Staff");
        alice = new FakePlayer("Alice").at(new Location(world, 0, 64, 0));
        FakeServer.online(alice.player());
        sink = new CountingSink();
        PacketRuntime.install(sink);
        PacketRuntime.init(plugin);
    }

    @AfterEach
    void tearDown() {
        Packets.releaseAll();
        PacketRuntime.shutdown();
    }

    private Map<Location, BlockData> ores(int count) {
        BlockData stone = Material.STONE.createBlockData();
        Map<Location, BlockData> blocks = new HashMap<>();
        for (int index = 0; index < count; index++) {
            blocks.put(new Location(world, index, 64, 0), stone);
        }
        return blocks;
    }

    @Test
    @DisplayName("a small outline goes out in one tick")
    void smallOutlineIsOneTick() {
        Packets.of(plugin).glowingBlocks().show(alice.player(), ores(40), NamedTextColor.AQUA);
        FakeServer.tick(1);

        assertEquals(40, sink.drawn);
        assertEquals(1, sink.perTick.size(), "a small outline must not be spread over ticks");
    }

    @Test
    @DisplayName("many outlines at once share one budget, not one each")
    void manyCallsShareTheBudget() {
        // What an x-ray sweep does: one call per chunk that came into range,
        // all in the same tick. Each used to get a whole tick's budget.
        for (int chunk = 0; chunk < 10; chunk++) {
            Map<Location, BlockData> blocks = new HashMap<>();
            BlockData stone = Material.STONE.createBlockData();
            for (int index = 0; index < 100; index++) {
                blocks.put(new Location(world, chunk * 100 + index, 70, 0), stone);
            }
            Packets.of(plugin).glowingBlocks().show(alice.player(), blocks, NamedTextColor.AQUA);
        }
        FakeServer.tick(40);

        assertEquals(1000, sink.drawn, "every block must still be drawn");
        int worst = sink.perTick.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(worst <= 128, "ten calls at once must share one budget, worst tick was " + worst);
    }

    @Test
    @DisplayName("a big outline is spread rather than sent in one tick")
    void bigOutlineIsSpread() {
        // An x-ray sweep of a couple of thousand ores: sent whole it is four
        // thousand packets inside one tick, which is the spike this guards.
        Packets.of(plugin).glowingBlocks().show(alice.player(), ores(1000), NamedTextColor.AQUA);
        FakeServer.tick(40);

        assertEquals(1000, sink.drawn, "every block must still be drawn");
        assertTrue(sink.perTick.size() >= 8,
                "the sweep must be spread, was in " + sink.perTick.size() + " tick(s)");
        int worst = sink.perTick.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(worst <= 128, "no tick may carry more than a slice, worst was " + worst);
    }
}
