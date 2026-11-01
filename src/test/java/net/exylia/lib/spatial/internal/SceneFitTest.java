package net.exylia.lib.spatial.internal;

import net.exylia.lib.spatial.SpatialLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The preview's turntable, its facing words, and which blocks a menu's cleared view takes in. */
class SceneFitTest {

    @Test
    void turnsOnItsOwnUntilTurnedThenHoldsAndResumesFromThere() {
        Turntable table = new Turntable(0, 12, 8000, 0);
        assertEquals(12, table.angle(1000), 1e-9);
        table.nextSide(1, 1000);
        assertTrue(table.holding(1200));
        assertEquals(90, table.angle(1000 + Turntable.GLIDE_MILLIS), 1e-9);
        // Held: the auto-turn has stopped.
        assertEquals(90, table.angle(5000), 1e-9);
        table.nextSide(1, 5000);
        assertEquals(180, table.angle(6000), 1e-9);
        // The pause runs out 8 s after the last turn: it turns on its own again, from the back.
        assertFalse(table.holding(13_001));
        assertEquals(180 + 12, table.angle(14_000), 1e-6);
    }

    @Test
    void stepsBackAQuarterAndTheWheelTurnsALittle() {
        Turntable table = new Turntable(0, 0, 1000, 0);
        table.nextSide(-1, 0);
        assertEquals(-90, table.angle(500), 1e-9);
        table.turnBy(30, 500);
        assertEquals(-60, table.angle(1000), 1e-9);
        // A still body stays where it was left once the hold ends.
        assertEquals(-60, table.angle(10_000), 1e-9);
    }

    @Test
    void facingWordsReadAsDegrees() {
        SpatialLayout.Preview preview = new SpatialLayout.Preview();
        assertEquals(0, preview.facingDegrees());
        assertEquals(90, with(preview, "side").facingDegrees());
        assertEquals(180, with(preview, "BACK").facingDegrees());
        assertEquals(270, with(preview, "other-side").facingDegrees());
        assertEquals(45, with(preview, "45").facingDegrees());
        assertEquals(0, with(preview, "sideways").facingDegrees());
    }

    private static SpatialLayout.Preview with(SpatialLayout.Preview p, String facing) {
        return new SpatialLayout.Preview(p.at(), p.turnSpeed(), p.pedestal(), p.scale(), p.vanishOnPlay(), p.effectScale(),
                p.skins(), facing, p.turnControls(), p.turnHint(), p.turnPause());
    }

    @Test
    void clearsOnlyWhatStandsBetweenTheFaceAndTheBoard() {
        // Facing south (+z): ahead is +z, the right is -x.
        assertTrue(SceneShield.inFront(0, 2, 0, 1, -1, 0, 4, 2.5), "a block two ahead");
        assertFalse(SceneShield.inFront(0, -2, 0, 1, -1, 0, 4, 2.5), "nothing behind");
        assertFalse(SceneShield.inFront(0, 6, 0, 1, -1, 0, 4, 2.5), "nothing past the board");
        assertFalse(SceneShield.inFront(4, 2, 0, 1, -1, 0, 4, 2.5), "nothing beyond the widest panel");
        // The floor is the block under the feet, a slab or a carpet counted as theirs.
        assertEquals(64, SceneShield.floorLayer(65.0));
        assertEquals(64, SceneShield.floorLayer(64.5));
        // On a carpet the block under it is the floor layer; the carpet itself is kept as it holds the feet up.
        assertEquals(63, SceneShield.floorLayer(64.0625));
    }
}
