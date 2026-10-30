package net.exylia.lib.spatial;

import net.exylia.lib.stage.StagePlane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void aCustomPickerAnswersFromTheGamesState() {
        boolean[] rolling = {false};
        // Columns including the gaps: the hit's across alone decides, -1 while the dice roll.
        Hotspot columns = Hotspot.custom(plane(3), hit -> rolling[0] ? -1 : hit.across() > 0 ? 0 : 1);
        assertEquals(1, SpatialPointer.target(List.of(columns), 0, 0, 0, 0.25 / 3, 0, 1).cell(), "the gap is a column too");
        assertEquals(0, SpatialPointer.target(List.of(columns), 0, 0, 0, -0.25 / 3, 0, 1).cell());
        rolling[0] = true;
        assertNull(SpatialPointer.target(List.of(columns), 0, 0, 0, 0.25 / 3, 0, 1), "-1 is a miss");
    }

    @Test
    void aRefusedCellIsAMissAndTheHotspotBehindTakesIt() {
        Hotspot board = Hotspot.cells(plane(2)).accepts(cell -> cell != 4);
        Hotspot behind = Hotspot.rect(plane(3), 0, 0, 2, 2);
        assertSame(behind, SpatialPointer.target(List.of(board, behind), 0, 0, 0, 0, 0, 1).hotspot());
        assertEquals(0, SpatialPointer.target(List.of(board, behind), 0, 0, 0, 0.5 / 2, 0.5 / 2, 1).cell());
        assertSame(board, SpatialPointer.target(List.of(board, behind), 0, 0, 0, 0.5 / 2, 0.5 / 2, 1).hotspot());
    }

    @Test
    void aHotspotThatDoesNotHoverStillTakesTheCrosshairButNeverLights() {
        int[] lit = {0};
        int[] cells = {0};
        Hotspot spot = Hotspot.cells(plane(3)).hoverable(false).onHover(on -> lit[0]++).onCell(cell -> cells[0]++);
        assertSame(spot, SpatialPointer.target(List.of(spot), 0, 0, 0, 0, 0, 1).hotspot());
        assertFalse(spot.hover(4), "no sound cue");
        assertEquals(4, spot.cell(), "the press still knows the cell");
        assertEquals(0, lit[0]);
        assertEquals(0, cells[0]);
        spot.hoverable(true);
        assertTrue(spot.hover(4), "made hoverable under the crosshair, it lights on the next poll");
        assertEquals(1, lit[0]);
        assertFalse(spot.hover(4));
    }

    @Test
    void aPressGuardKeepsTheHover() {
        boolean[] open = {false};
        Hotspot spot = Hotspot.rect(plane(3), 0, 0, 1, 1).pressWhen(() -> open[0]);
        assertTrue(spot.hover(0));
        assertFalse(spot.pressable());
        open[0] = true;
        assertTrue(spot.pressable());
        assertTrue(Hotspot.rect(plane(3), 0, 0, 1, 1).pressable(), "no guard lets every press in");
    }
}
