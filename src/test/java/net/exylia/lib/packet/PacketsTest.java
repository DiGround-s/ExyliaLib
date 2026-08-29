package net.exylia.lib.packet;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.packet.internal.SectionGroups;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the module promises on a server without PacketEvents, and the pure parts. */
class PacketsTest {

    private World world;
    private Plugin plugin;
    private FakePlayer alice;
    private FakePlayer bob;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Packets.releaseAll();
        world = FakeServer.newWorld("world");
        plugin = FakeServer.newPlugin("Staff");
        alice = new FakePlayer("Alice").at(new Location(world, 0, 64, 0));
        bob = new FakePlayer("Bob").at(new Location(world, 5, 64, 5));
        FakeServer.online(alice.player(), bob.player());
    }

    @Test
    @DisplayName("without PacketEvents nothing is available and nothing throws")
    void noPacketEvents() {
        assertFalse(Packets.isAvailable());
        PluginPackets packets = Packets.of(plugin);
        assertSame(packets, Packets.of(plugin));

        packets.visibility().rule((viewer, target) -> false);
        packets.visibility().refresh(alice.player());
        assertTrue(packets.visibility().canSee(bob.player(), alice.player()));

        packets.fakeBlocks().show(alice.player(), Map.of());
        packets.fakeBlocks().clear(alice.player());
        packets.fakeBlocks().clear(alice.player(), List.of(new Location(world, 1, 2, 3)));

        packets.fakeGameMode().spectator(alice.player(), true);
        assertFalse(packets.fakeGameMode().isSpectator(alice.player()));

        Packets.release("Staff");
        Packets.releaseAll();
    }

    @Test
    @DisplayName("freezing is bookkeeping and survives without PacketEvents")
    void freezeIsTracked() {
        Movement movement = Packets.of(plugin).movement();
        movement.freeze(alice.player());
        assertTrue(movement.isFrozen(alice.player()));
        assertFalse(movement.isFrozen(bob.player()));
        // Another plugin does not own the freeze and cannot lift it.
        Packets.of(FakeServer.newPlugin("Other")).movement().unfreeze(alice.player());
        assertTrue(movement.isFrozen(alice.player()));
        movement.unfreeze(alice.player());
        assertFalse(movement.isFrozen(alice.player()));
    }

    @Test
    @DisplayName("release lifts what the plugin froze")
    void releaseUnfreezes() {
        Movement movement = Packets.of(plugin).movement();
        movement.freeze(alice.player());
        Packets.release("Staff");
        assertFalse(Packets.of(plugin).movement().isFrozen(alice.player()));
    }

    @Test
    @DisplayName("positions group by 16x16x16 section, negatives floored")
    void sectionGrouping() {
        Map<SectionGroups.Section, List<Location>> groups = SectionGroups.group(List.of(
                new Location(world, 0, 64, 0),
                new Location(world, 15, 79, 15),
                new Location(world, 16, 64, 0),
                new Location(world, -1, 64, -1),
                new Location(world, -16, -1, -16)));
        assertEquals(4, groups.size());
        assertEquals(2, groups.get(new SectionGroups.Section(0, 4, 0)).size());
        assertEquals(1, groups.get(new SectionGroups.Section(1, 4, 0)).size());
        assertEquals(1, groups.get(new SectionGroups.Section(-1, 4, -1)).size());
        assertEquals(1, groups.get(new SectionGroups.Section(-1, -1, -1)).size());
        assertEquals(new SectionGroups.Section(-1, -1, -1), SectionGroups.Section.of(-1, -1, -1));
    }
}
