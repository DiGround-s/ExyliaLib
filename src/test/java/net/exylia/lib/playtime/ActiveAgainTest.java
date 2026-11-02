package net.exylia.lib.playtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Back from idle is a change, seen once: not every second a player is active, and never for one who left. */
class ActiveAgainTest {

    private final UUID ann = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    @DisplayName("only a player who was idle and is active now came back, once")
    void transitionOnly() {
        Set<UUID> idle = new HashSet<>();

        assertEquals(List.of(), Playtime.cameBack(idle, Map.of(ann, true, bob, false)), "active from the start is not a return");
        assertEquals(Set.of(bob), idle);

        assertEquals(List.of(bob), Playtime.cameBack(idle, Map.of(ann, true, bob, true)));
        assertEquals(List.of(), Playtime.cameBack(idle, Map.of(ann, true, bob, true)), "seen once");
    }

    @Test
    @DisplayName("a player who left while idle is forgotten")
    void leaversAreForgotten() {
        Set<UUID> idle = new HashSet<>();
        Playtime.cameBack(idle, Map.of(bob, false));
        Playtime.cameBack(idle, Map.of());
        assertEquals(List.of(), Playtime.cameBack(idle, Map.of(bob, true)), "rejoining is not coming back from idle");
    }
}
