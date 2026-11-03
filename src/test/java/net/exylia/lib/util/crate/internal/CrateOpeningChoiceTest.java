package net.exylia.lib.util.crate.internal;

import net.exylia.lib.util.crate.CrateOpening;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateOpeningChoiceTest {

    @Test
    void aSpatialMenuAlwaysOpensTheScene() {
        assertTrue(CrateScreens.spatial(CrateOpening.INVENTORY, true, true));
        assertTrue(CrateScreens.spatial(CrateOpening.SPATIAL, true, true));
    }

    @Test
    void everyOtherEntryFollowsTheSetting() {
        assertFalse(CrateScreens.spatial(CrateOpening.INVENTORY, false, true));
        assertTrue(CrateScreens.spatial(CrateOpening.SPATIAL, false, true));
    }

    @Test
    void aPlayerWhoCannotSeeTheSceneGetsTheReels() {
        assertFalse(CrateScreens.spatial(CrateOpening.SPATIAL, true, false));
        assertFalse(CrateScreens.spatial(CrateOpening.SPATIAL, false, false));
    }
}
