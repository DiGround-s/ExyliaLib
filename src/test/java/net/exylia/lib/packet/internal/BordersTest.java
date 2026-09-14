package net.exylia.lib.packet.internal;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.packet.VirtualBorder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a virtual border sends, and the arithmetic the server hurts by. */
class BordersTest {

    /** One border packet as it would have gone out. */
    private record Sent(double x, double z, double from, double to, long millis) {
    }

    private static final class RecordingSink implements PacketSink {

        final List<Sent> borders = new ArrayList<>();

        @Override
        public void border(Player viewer, double x, double z, double from, double to, long millis,
                           int warningBlocks, int warningSeconds) {
            borders.add(new Sent(x, z, from, to, millis));
        }

        @Override public void despawn(Player viewer, int entityId, UUID profile) { }
        @Override public void blocks(Player viewer, SectionGroups.Section section, List<Location> positions,
                                     Map<Location, BlockData> data) { }
        @Override public int newEntityId() { return 0; }
        @Override public void glowingBlock(Player viewer, int entityId, Location at, BlockData data, int argb) { }
        @Override public void destroyEntities(Player viewer, int[] entityIds) { }
        @Override public void gameMode(Player viewer, int mode) { }
        @Override public void sidebarSlot(Player viewer, String objectiveName) { }
        @Override public void abilities(Player viewer, boolean invulnerable, boolean flying,
                                        boolean allowFlight, float flySpeed) { }
        @Override public void close() { }
    }

    private World world;
    private Plugin plugin;
    private FakePlayer alice;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.releaseAll();
        world = FakeServer.newWorld("world");
        plugin = FakeServer.newPlugin("Zones");
        alice = new FakePlayer("Alice").at(new Location(world, 0, 64, 0));
        FakeServer.online(alice.player());
        sink = new RecordingSink();
        PacketRuntime.install(sink);
        PacketRuntime.init(plugin);
    }

    @AfterEach
    void tearDown() {
        Borders.SEEN.clear();
        PacketRuntime.shutdown();
    }

    @Test
    @DisplayName("showing a border sends it once, standing still")
    void showSendsTheBorder() {
        VirtualBorder zone = Packets.of(plugin).worldBorders().create(new Location(world, 100, 64, -50), 200);
        zone.show(alice.player());
        FakeServer.tick(1);

        assertEquals(List.of(new Sent(100, -50, 200, 200, 0)), sink.borders);
        assertSame(zone, Packets.of(plugin).worldBorders().seenBy(alice.player()));
    }

    @Test
    @DisplayName("a resize is one packet, from the width it has now")
    void resizeIsOnePacket() {
        VirtualBorder zone = Packets.of(plugin).worldBorders().create(new Location(world, 0, 64, 0), 200);
        zone.show(alice.player());
        FakeServer.tick(1);
        zone.size(20, Duration.ofMinutes(2));

        Sent resize = sink.borders.get(sink.borders.size() - 1);
        assertEquals(200, resize.from(), 0.5);
        assertEquals(20, resize.to());
        assertTrue(resize.millis() > 119_000 && resize.millis() <= 120_000, "was " + resize.millis());
    }

    @Test
    @DisplayName("a player sees one border: a second replaces the first")
    void secondBorderReplaces() {
        var borders = Packets.of(plugin).worldBorders();
        VirtualBorder first = borders.create(new Location(world, 0, 64, 0), 100);
        VirtualBorder second = borders.create(new Location(world, 500, 64, 0), 50);
        first.show(alice.player());
        second.show(alice.player());

        assertSame(second, borders.seenBy(alice.player()));
        assertTrue(first.viewers().isEmpty());
        assertEquals(List.of(alice.player()), List.copyOf(second.viewers()));
    }

    @Test
    @DisplayName("sizes out of range and borders without a world are refused")
    void refusesNonsense() {
        var borders = Packets.of(plugin).worldBorders();
        assertThrows(IllegalArgumentException.class, () -> borders.create(new Location(null, 0, 0, 0), 10));
        assertThrows(IllegalArgumentException.class, () -> borders.create(new Location(world, 0, 0, 0), 0));
        VirtualBorder zone = borders.create(new Location(world, 0, 0, 0), 10);
        assertThrows(IllegalArgumentException.class, () -> zone.size(10, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> zone.damage(-1, 0));
    }

    @Test
    @DisplayName("a resize is worked out from its clock")
    void sizeFollowsTheClock() {
        Borders.Shape shape = new Borders.Shape(0, 0, 200, 100, 1_000, 10_000, 5, 15, 0.2, 5);
        assertEquals(200, shape.sizeAt(0));
        assertEquals(150, shape.sizeAt(6_000));
        assertEquals(100, shape.sizeAt(11_000));
        assertEquals(100, shape.sizeAt(50_000));
        assertEquals(5_000, shape.remaining(6_000));
        assertEquals(0, shape.remaining(50_000));
    }

    @Test
    @DisplayName("damage follows vanilla: nothing inside or in the buffer, at least one past it")
    void damageLikeVanilla() {
        Borders.Shape shape = new Borders.Shape(0, 0, 100, 100, 0, 0, 5, 15, 0.2, 5);
        assertTrue(shape.beyond(10, -20, 0) < 0, "inside");
        assertEquals(7, shape.beyond(57, 0, 0));
        assertEquals(0, shape.damage(shape.beyond(10, 0, 0)));
        assertEquals(0, shape.damage(4));
        assertEquals(1, shape.damage(7));
        assertEquals(5, shape.damage(30));
        assertEquals(0, shape.damage(0.0, 5).damage(30));
    }

    @Test
    @DisplayName("without PacketEvents a border is shaped but shown to nobody")
    void noPacketEvents() {
        PacketRuntime.install(null);
        VirtualBorder zone = Packets.of(plugin).worldBorders().create(new Location(world, 0, 64, 0), 100);
        zone.show(alice.player());
        assertNull(Packets.of(plugin).worldBorders().seenBy(alice.player()));
        assertTrue(zone.contains(new Location(world, 49, 64, 0)));
        assertFalse(zone.contains(new Location(world, 51, 64, 0)));
    }
}
