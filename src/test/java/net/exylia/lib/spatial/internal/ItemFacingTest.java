package net.exylia.lib.spatial.internal;

import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A shield or a banner shows its front; an item preview at turn-speed 0 never turns round. */
class ItemFacingTest {

    @Test
    void shieldsAndBannersAreDrawnAsInAnItemFrame() {
        assertEquals(ItemFacing.FIXED, ItemFacing.context("SHIELD"));
        assertEquals(ItemFacing.FIXED, ItemFacing.context("RED_BANNER"));
        assertEquals(ItemFacing.GUI, ItemFacing.context("FLOWER_BANNER_PATTERN"));
        assertEquals(ItemFacing.GUI, ItemFacing.context("DIAMOND_SWORD"));
    }

    @Test
    void aStillItemOnlySwaysAFewDegrees() {
        for (DisplayKeyframe frame : ItemFacing.stand(Rotation.around(Rotation.Axis.Y, 0), 0, 0, 1, 60_000).poses()) {
            // The quaternion's y is sin(half the angle): a few degrees either way at most.
            double degrees = Math.toDegrees(2 * Math.asin(Math.abs(frame.rotation().y())));
            assertTrue(degrees <= ItemFacing.SWAY_DEGREES + 1e-3, degrees + "°");
        }
    }
}
