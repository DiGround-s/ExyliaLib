package net.exylia.lib.util.worldguard;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety property: a server without WorldGuard is a server where these
 * flags withhold nothing.
 *
 * <p>Nothing here loads a WorldGuard class, which is the point. If the facade
 * ever names one outside its internal accessor these tests stop running, and
 * so does every server that has no WorldGuard installed.
 */
class WorldGuardFlagsTest {

    @Test
    void nothingIsRegisteredWithoutWorldGuard() {
        assertFalse(WorldGuardFlags.register(WorldGuardFlags.KILL_EFFECTS, true),
                "registration must fail rather than throw without WorldGuard");
        assertFalse(WorldGuardFlags.registered(WorldGuardFlags.KILL_EFFECTS));
        assertTrue(WorldGuardFlags.names().isEmpty());
    }

    @Test
    void registeringTheDefaultsIsHarmlessWithoutWorldGuard() {
        assertEqualsZero(WorldGuardFlags.registerDefaults());
    }

    @Test
    void anUnregisteredFlagAllowsEverything() {
        // A null world is the shutdown case, and a location with no world must
        // answer "allowed" rather than throw inside an effect.
        assertTrue(WorldGuardFlags.allows(WorldGuardFlags.KILL_EFFECTS, new Location(null, 0, 64, 0)));
        assertTrue(WorldGuardFlags.allows(WorldGuardFlags.HIT_EFFECTS, new Location(null, 0, 64, 0)));
        assertTrue(WorldGuardFlags.allows(WorldGuardFlags.ARROWS_EFFECTS, new Location(null, 0, 64, 0)));
        assertTrue(WorldGuardFlags.allows("something-nobody-registered", new Location(null, 0, 64, 0)));
    }

    private static void assertEqualsZero(int registered) {
        assertTrue(registered == 0, "expected no flags to register, got " + registered);
    }
}
