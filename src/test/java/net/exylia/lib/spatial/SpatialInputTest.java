package net.exylia.lib.spatial;

import net.exylia.lib.stage.StagePlane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpatialInputTest {

    /** A board 3 blocks ahead of eyes at the origin, looking south. */
    private static StagePlane plane(double ahead) {
        return new StagePlane(null, 0, 0, ahead, 0f, 3, 3, 0.5, 0.45);
    }

    @Test
    void theNearestWinsAndPriorityBeatsDistance() {
        Hotspot far = Hotspot.rect(plane(3), 0, 0, 2, 2);
        Hotspot near = Hotspot.rect(plane(2), 0, 0, 1, 1);
        var target = SpatialPointer.target(List.of(far, near), 0, 0, 0, 0, 0, 1);
        assertSame(near, target.hotspot());
        assertEquals(2, target.distance(), 1e-9);
        far.priority(1);
        assertSame(far, SpatialPointer.target(List.of(far, near), 0, 0, 0, 0, 0, 1).hotspot());
        far.enabled(false);
        assertSame(near, SpatialPointer.target(List.of(far, near), 0, 0, 0, 0, 0, 1).hotspot(), "disabled is passed over");
        assertNull(SpatialPointer.target(List.of(far, near), 0, 0, 0, 1, 0, 0), "looking aside");
    }

    @Test
    void aCellPickerAnswersWithTheCell() {
        Hotspot board = Hotspot.cells(plane(3));
        // The top-left cell is to the viewer's left (east, +x) and up.
        var target = SpatialPointer.target(List.of(board), 0, 0, 0, 0.5 / 3, 0.5 / 3, 1);
        assertEquals(0, target.cell());
        assertEquals(4, SpatialPointer.target(List.of(board), 0, 0, 0, 0, 0, 1).cell());
        assertNull(SpatialPointer.target(List.of(board), 0, 0, 0, 0.25 / 3, 0, 1), "the gap between cells misses");
    }

    @Test
    void hoverChangesAreReportedOnce() {
        int[] calls = {0, 0};
        Hotspot spot = Hotspot.rect(plane(3), 0, 0, 1, 1).onHover(on -> calls[0]++).onCell(cell -> calls[1]++);
        spot.hover(0);
        spot.hover(0);
        spot.hover(-1);
        assertEquals(2, calls[0]);
        assertEquals(2, calls[1]);
    }

    @Test
    void theWheelWrapsRoundTheHotbar() {
        assertEquals(1, SpatialPointer.notch(3, 4));
        assertEquals(-1, SpatialPointer.notch(4, 3));
        assertEquals(1, SpatialPointer.notch(8, 0));
        assertEquals(-1, SpatialPointer.notch(0, 8));
        assertEquals(0, SpatialPointer.notch(2, 2));
    }
}
