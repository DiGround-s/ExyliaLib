package net.exylia.lib.util;

import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every spawn reason lands in an origin, and the farm ones are the player-made ones. */
class SpawnOriginTest {

    @Test
    void farmsArePlayerMade() {
        assertEquals(SpawnOrigin.SPAWNER, SpawnOrigin.ofReason("SPAWNER"));
        assertEquals(SpawnOrigin.SPAWNER, SpawnOrigin.ofReason("TRIAL_SPAWNER"));
        assertEquals(SpawnOrigin.EGG, SpawnOrigin.ofReason("SPAWNER_EGG"));
        assertEquals(SpawnOrigin.EGG, SpawnOrigin.ofReason("DISPENSE_EGG"));
        assertEquals(SpawnOrigin.EGG, SpawnOrigin.ofReason("BUCKET"));
        assertEquals(SpawnOrigin.BREEDING, SpawnOrigin.ofReason("BREEDING"));
        assertTrue(SpawnOrigin.SPAWNER.playerMade());
        assertTrue(SpawnOrigin.EGG.playerMade());
        assertTrue(SpawnOrigin.BREEDING.playerMade());
    }

    @Test
    void theWorldIsNatural() {
        assertEquals(SpawnOrigin.NATURAL, SpawnOrigin.ofReason("NATURAL"));
        assertEquals(SpawnOrigin.NATURAL, SpawnOrigin.ofReason("CHUNK_GEN"));
        assertEquals(SpawnOrigin.NATURAL, SpawnOrigin.ofReason("SLIME_SPLIT"));
        assertFalse(SpawnOrigin.NATURAL.playerMade());
        assertFalse(SpawnOrigin.BUILT.playerMade());
        assertEquals(SpawnOrigin.BUILT, SpawnOrigin.ofReason("BUILD_IRONGOLEM"));
        assertEquals(SpawnOrigin.PLUGIN, SpawnOrigin.ofReason("CUSTOM"));
    }

    @Test
    void anUnknownReasonIsOther() {
        assertEquals(SpawnOrigin.OTHER, SpawnOrigin.ofReason("SOMETHING_FROM_1_30"));
    }

    @Test
    void everyReasonOfTheCompiledApiIsListedExceptTheOddOnes() {
        for (CreatureSpawnEvent.SpawnReason reason : CreatureSpawnEvent.SpawnReason.values()) {
            SpawnOrigin origin = SpawnOrigin.ofReason(reason.name());
            if (origin == SpawnOrigin.OTHER) {
                assertTrue(java.util.Set.of("ENDER_PEARL", "SHOULDER_ENTITY", "ENCHANTMENT", "POTION_EFFECT")
                        .contains(reason.name()), reason + " is not classified");
            }
        }
    }
}
