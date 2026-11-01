package net.exylia.lib.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AttributesTest {

    /** The keys a 1.21.4 server has. */
    private static final Map<String, String> NEW = Map.of(
            "max_health", "health", "entity_interaction_range", "reach", "jump_strength", "jump");

    /** The keys a 1.21.1 server has. */
    private static final Map<String, String> OLD = Map.of(
            "generic.max_health", "health", "player.entity_interaction_range", "reach",
            "horse.jump_strength", "jump");

    @Test
    void everySpellingResolvesOnANewServer() {
        for (String written : new String[]{"max_health", "generic.max_health", "minecraft:max_health",
                "minecraft:generic.max_health", "generic_max_health"}) {
            assertEquals("health", Attributes.resolve(written, NEW::get), written);
        }
        assertEquals("reach", Attributes.resolve("player.entity_interaction_range", NEW::get));
        assertEquals("reach", Attributes.resolve("player_entity_interaction_range", NEW::get));
    }

    @Test
    void everySpellingResolvesOnAnOldServer() {
        for (String written : new String[]{"max_health", "generic.max_health", "minecraft:max_health",
                "generic_max_health"}) {
            assertEquals("health", Attributes.resolve(written, OLD::get), written);
        }
        assertEquals("reach", Attributes.resolve("entity_interaction_range", OLD::get));
        assertEquals("jump", Attributes.resolve("jump_strength", OLD::get));
        assertEquals("jump", Attributes.resolve("horse_jump_strength", OLD::get));
    }

    @Test
    void anUnknownNameIsNull() {
        assertNull(Attributes.resolve("flight_power", NEW::get));
        assertNull(Attributes.resolve("flight_power", OLD::get));
    }
}
