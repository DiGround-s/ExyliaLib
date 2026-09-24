package net.exylia.lib.display;

import net.exylia.lib.FakeServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A beam laid along its segment, as numbers.
 */
class VfxBeamTest {

    private World world;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        world = FakeServer.newWorld("beams");
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    @Test
    @DisplayName("a block beam is one display, centred on the segment and stretched along it")
    void blockBeamLiesAlongTheSegment() {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 3, 68, 0);
        DisplayModel glass = DisplayModel.block(org.bukkit.Material.GLASS.createBlockData());

        List<Vfx.Shown> shown = Vfx.at(from).beam(0, from, to, glass, 0.1, 250).shown();

        assertEquals(1, shown.size());
        DisplayKeyframe pose = shown.get(0).motion().poses().get(0);
        assertEquals(1.5, pose.x(), 1e-5);
        assertEquals(2.0, pose.y(), 1e-5);
        assertEquals(5.0, pose.scaleZ(), 1e-5);
        float[] axis = pose.rotation().apply(new float[]{0f, 0f, 1f});
        assertEquals(0.6, axis[0], 1e-5);
        assertEquals(0.8, axis[1], 1e-5);
        assertEquals(0.0, axis[2], 1e-5);
        assertEquals(250, shown.get(0).motion().lifeMillis());
    }

    @Test
    @DisplayName("an item beam is the item repeated along the segment")
    void itemBeamRepeats() {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 0, 64, 5);

        List<Vfx.Shown> shown = Vfx.at(from)
                .beam(0, from, to, DisplayModel.text(Component.text("o")), 1.0, 300).shown();

        assertEquals(5, shown.size());
        assertEquals(0.5, shown.get(0).motion().poses().get(0).z(), 1e-5);
        assertEquals(4.5, shown.get(4).motion().poses().get(0).z(), 1e-5);
    }
}
