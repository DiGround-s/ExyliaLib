package net.exylia.lib.region.internal;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.SelectionHeight;
import net.exylia.lib.region.UnboundedYRectangle;
import net.exylia.lib.region.WorldIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a selection covers and how big it is said to be, at both heights.
 *
 * <p>A full-height selection used to be drawn and counted as the box between
 * the clicked blocks, and then protected as the whole column: what the player
 * looked at was not what they paid for.
 */
class SelectionHeightTest {

    private static final WorldIdentity WORLD = new WorldIdentity(UUID.randomUUID(), "world");

    @Test
    @DisplayName("a full-height selection is the rectangle of columns, both corners included")
    void fullIsColumns() {
        var shape = SelectionRuntime.shapeOf(SelectionHeight.FULL, at(5, 70, -3), at(1, -40, 2));

        assertEquals(new UnboundedYRectangle(1, -3, 6, 3), shape);
        assertEquals(30L, SelectionRuntime.sizeOf(shape), "5 by 6 columns, whatever heights were clicked");
    }

    @Test
    @DisplayName("a normal selection is still the box, and still counts blocks")
    void normalIsTheBox() {
        var shape = SelectionRuntime.shapeOf(SelectionHeight.NORMAL, at(5, 70, -3), at(1, 68, 2));

        assertEquals(Cuboid.blocks(1, 68, -3, 5, 70, 2), shape);
        assertEquals(90L, SelectionRuntime.sizeOf(shape));
    }

    @Test
    @DisplayName("one corner is the one block, or the one column, it is")
    void oneCorner() {
        assertEquals(new UnboundedYRectangle(4, 9, 5, 10),
                SelectionRuntime.shapeOf(SelectionHeight.FULL, null, at(4, 0, 9)));
        assertEquals(Cuboid.block(4, 0, 9), SelectionRuntime.shapeOf(SelectionHeight.NORMAL, at(4, 0, 9), null));
        assertNull(SelectionRuntime.shapeOf(SelectionHeight.FULL, null, null));
        assertEquals(0L, SelectionRuntime.sizeOf(null));
    }

    private static BlockPosition at(int x, int y, int z) {
        return new BlockPosition(WORLD, x, y, z);
    }
}
