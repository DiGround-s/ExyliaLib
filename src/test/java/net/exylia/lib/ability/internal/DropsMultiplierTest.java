package net.exylia.lib.ability.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropsMultiplierTest {

    @Test
    void onlyPlainStackablesMultiply() {
        assertTrue(BuiltinEffects.multipliable(64, false, false));
        assertFalse(BuiltinEffects.multipliable(1, false, false), "a netherite sword is unstackable");
        assertFalse(BuiltinEffects.multipliable(64, true, false), "meta (enchants, names) never multiplies");
        assertFalse(BuiltinEffects.multipliable(64, false, true), "the victim's own gear never multiplies");
    }

    @Test
    void multipliedStackNeverExceedsMax() {
        assertEquals(16, BuiltinEffects.capAmount(40, 16));
        assertEquals(5, BuiltinEffects.capAmount(5, 64));
    }
}
