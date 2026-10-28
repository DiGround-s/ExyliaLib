package net.exylia.lib.ability.internal;

import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A PASSIVE potion is finite, so a crash before the unequip cannot keep it forever. */
class PassivePotionTest {

    @Test
    @DisplayName("an untimed PASSIVE potion lasts 30 seconds, never infinite; timed ones keep their duration")
    void finiteDuration() {
        assertEquals(BuiltinEffects.PASSIVE_TICKS, BuiltinEffects.potionTicks(false, "PASSIVE", 5));
        assertTrue(BuiltinEffects.PASSIVE_TICKS > 0 && BuiltinEffects.PASSIVE_TICKS != PotionEffect.INFINITE_DURATION);
        assertEquals(60, BuiltinEffects.potionTicks(true, "PASSIVE", 3));
        assertEquals(100, BuiltinEffects.potionTicks(false, "EQUIP", 5));
    }

    @Test
    @DisplayName("revert removes what PASSIVE granted, old infinite ones included, and leaves longer potions")
    void revertMatches() {
        assertTrue(BuiltinEffects.passiveGranted(1, 450, 1));
        assertTrue(BuiltinEffects.passiveGranted(1, PotionEffect.INFINITE_DURATION, 1), "pre-fix leftovers");
        assertFalse(BuiltinEffects.passiveGranted(1, 450, 0), "another amplifier");
        assertFalse(BuiltinEffects.passiveGranted(1, 6000, 1), "a longer potion from elsewhere");
    }
}
