package net.exylia.lib.block;

import net.exylia.lib.FakeServer;
import net.exylia.lib.block.internal.BlockRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the registry promises, and the ways it could quietly stop being true:
 * a location that resolves to a different key than the one it was stored
 * under, an unregister that takes down somebody else's block, a plugin that
 * leaves its handlers behind when it is disabled, and a held button that pays
 * for a crate twice.
 */
class BlockRegistryTest {

    private World world;
    private World otherWorld;
    private Plugin plugin;
    private PluginBlocks blocks;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Blocks.releaseAll();

        world = FakeServer.newWorld("survival");
        otherWorld = FakeServer.newWorld("resource");
        plugin = FakeServer.newPlugin("SurvivalCore", null);
        blocks = Blocks.of(plugin);
    }

    private Location at(World home, double x, double y, double z) {
        return new Location(home, x, y, z);
    }

    @Test
    @DisplayName("any point inside a block finds the registration")
    void anyPointInsideTheBlockFindsIt() {
        ClickableBlock crate = blocks.at(at(world, 10, 64, -3)).register();

        assertSame(crate, Blocks.at(at(world, 10.9, 64.4, -2.1)));
        assertSame(crate, Blocks.at(at(world, 10, 64, -3)));
        assertNull(Blocks.at(at(world, 11, 64, -3)));
    }

    @Test
    @DisplayName("the same coordinates in another world are another block")
    void worldsDoNotShareCoordinates() {
        blocks.at(at(world, 0, 64, 0)).register();

        assertNull(Blocks.at(at(otherWorld, 0, 64, 0)));
    }

    @Test
    @DisplayName("registering over a location replaces what was there")
    void registeringReplaces() {
        ClickableBlock first = blocks.at(at(world, 5, 70, 5)).register();
        ClickableBlock second = blocks.at(at(world, 5, 70, 5)).register();

        assertSame(second, Blocks.at(at(world, 5, 70, 5)));
        assertFalse(first.isRegistered());
        assertEquals(1, Blocks.active());
    }

    @Test
    @DisplayName("a replaced registration cannot take down the one that replaced it")
    void unregisterOnlyRemovesItself() {
        ClickableBlock first = blocks.at(at(world, 5, 70, 5)).register();
        ClickableBlock second = blocks.at(at(world, 5, 70, 5)).register();

        first.unregister();

        assertSame(second, Blocks.at(at(world, 5, 70, 5)));
    }

    @Test
    @DisplayName("a plugin only sees and removes its own")
    void ownershipIsRespected() {
        Plugin other = FakeServer.newPlugin("Events", null);
        Blocks.of(other).at(at(world, 1, 64, 1)).register();
        blocks.at(at(world, 2, 64, 2)).register();

        assertNull(blocks.registered(at(world, 1, 64, 1)));
        assertFalse(blocks.unregister(at(world, 1, 64, 1)));
        assertNotNull(Blocks.at(at(world, 1, 64, 1)));

        assertEquals(1, blocks.count());
        assertTrue(blocks.unregister(at(world, 2, 64, 2)));
        assertEquals(0, blocks.count());
    }

    @Test
    @DisplayName("disabling a plugin takes its registrations with it and leaves the rest")
    void releaseIsPerPlugin() {
        Plugin other = FakeServer.newPlugin("Events", null);
        Blocks.of(other).at(at(world, 1, 64, 1)).register();
        blocks.at(at(world, 2, 64, 2)).register();

        Blocks.release(plugin.getName());

        assertNull(Blocks.at(at(world, 2, 64, 2)));
        assertNotNull(Blocks.at(at(world, 1, 64, 1)));
    }

    @Test
    @DisplayName("only the handler for the button that was pressed runs")
    void buttonsAreSeparate() {
        AtomicInteger left = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();
        ClickableBlock crate = blocks.at(at(world, 0, 64, 0))
                .onLeft(click -> left.incrementAndGet())
                .onRight(click -> right.incrementAndGet())
                .register();

        assertTrue(crate.fire(click(BlockButton.RIGHT)));
        assertEquals(0, left.get());
        assertEquals(1, right.get());
    }

    @Test
    @DisplayName("a button with no handler is refused rather than answered")
    void missingHandlerFiresNothing() {
        ClickableBlock crate = blocks.at(at(world, 0, 64, 0))
                .onRight(click -> {
                })
                .register();

        assertFalse(crate.fire(click(BlockButton.LEFT)));
    }

    @Test
    @DisplayName("a held button is one click, and the other button is not")
    void repeatedClicksAreDebounced() {
        UUID player = UUID.randomUUID();
        BlockRuntime.Position position = new BlockRuntime.Position(world.getUID(), 0, 64, 0);

        assertFalse(BlockRuntime.isRepeat(player, position, BlockButton.RIGHT));
        assertTrue(BlockRuntime.isRepeat(player, position, BlockButton.RIGHT));
        assertFalse(BlockRuntime.isRepeat(player, position, BlockButton.LEFT));
        assertFalse(BlockRuntime.isRepeat(UUID.randomUUID(), position, BlockButton.RIGHT));
        assertFalse(BlockRuntime.isRepeat(player,
                new BlockRuntime.Position(world.getUID(), 1, 64, 0), BlockButton.RIGHT));
    }

    /** A click with no player behind it: enough to drive {@code fire}. */
    private BlockClick click(BlockButton button) {
        return new BlockClick(null, null, button, false);
    }
}
