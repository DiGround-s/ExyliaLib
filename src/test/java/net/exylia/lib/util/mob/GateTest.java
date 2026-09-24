package net.exylia.lib.util.mob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateTest {

    private static final double NOBODY = Double.NaN;

    @Test
    @DisplayName("ANY lets everything through, with or without a target")
    void any() {
        assertTrue(MobSkill.Gate.ANY.admits(0, NOBODY, NOBODY, 1));
        assertTrue(MobSkill.Gate.ANY.admits(1, 50, 50, 4));
    }

    @Test
    @DisplayName("the health band holds both ends")
    void health() {
        MobSkill.Gate gate = MobSkill.Gate.ANY.withHealth(0.2, 0.5);
        assertFalse(gate.admits(0.1, NOBODY, NOBODY, 1));
        assertTrue(gate.admits(0.2, NOBODY, NOBODY, 1));
        assertTrue(gate.admits(0.5, NOBODY, NOBODY, 1));
        assertFalse(gate.admits(0.6, NOBODY, NOBODY, 1));
    }

    @Test
    @DisplayName("a range needs a target inside it; 0 is no limit on that side")
    void range() {
        MobSkill.Gate melee = MobSkill.Gate.ANY.withRange(0, 6);
        assertTrue(melee.admits(1, 3, NOBODY, 1));
        assertFalse(melee.admits(1, 7, NOBODY, 1));
        assertFalse(melee.admits(1, NOBODY, NOBODY, 1), "no target, no range");
        MobSkill.Gate ranged = MobSkill.Gate.ANY.withRange(8, 0);
        assertFalse(ranged.admits(1, 3, NOBODY, 1));
        assertTrue(ranged.admits(1, 40, NOBODY, 1));
    }

    @Test
    @DisplayName("nearby needs a player within the distance; the phase must match when set")
    void nearbyAndPhase() {
        MobSkill.Gate gate = MobSkill.Gate.ANY.withNearby(16);
        assertTrue(gate.admits(1, NOBODY, 16, 1));
        assertFalse(gate.admits(1, NOBODY, 16.5, 1));
        assertFalse(gate.admits(1, NOBODY, NOBODY, 1));
        MobSkill.Gate second = MobSkill.Gate.ANY.withPhase(2);
        assertFalse(second.admits(1, NOBODY, NOBODY, 1));
        assertTrue(second.admits(1, NOBODY, NOBODY, 2));
    }

    @Test
    @DisplayName("nonsense reads clamped")
    void clamped() {
        MobSkill.Gate gate = new MobSkill.Gate(-1, 7, -3, Double.NaN, -2, -5);
        assertEquals(MobSkill.Gate.ANY, gate);
    }
}
