package net.exylia.lib.npc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcAnimationTest {

    private static List<String> timeline(NpcAnimation animation) {
        return animation.steps().stream().map(step -> step.atMillis() + " " + step.move()).toList();
    }

    @Test
    @DisplayName("moves and waits are read in order, a wait moving every later move")
    void readsMovesAndWaits() {
        NpcAnimation animation = NpcAnimation.parse("swing, 250ms, offhand, hurt, 1s, swing");
        assertEquals(List.of("0 SWING", "250 SWING_OFFHAND", "250 HURT", "1250 SWING"), timeline(animation));
        assertEquals(1250, animation.lengthMillis());
    }

    @Test
    @DisplayName("a preset expands in place and can be mixed with moves")
    void presetsExpand() {
        assertEquals(List.of("0 SWING", "250 SWING", "500 SWING"), timeline(NpcAnimation.parse("WAVE")));
        assertEquals(List.of("0 HURT", "500 SWING", "750 SWING", "1000 SWING"),
                timeline(NpcAnimation.parse("flinch, 500ms, wave")));
        assertTrue(NpcAnimation.presets().contains("nod"));
    }

    @Test
    @DisplayName("a crouch with no stand after it stands up at the end")
    void crouchAlwaysEnds() {
        assertEquals(List.of("0 CROUCH", "400 SWING", "400 STAND"),
                timeline(NpcAnimation.parse("crouch, 400ms, swing")));
        assertEquals(List.of("0 CROUCH", "200 STAND"), timeline(NpcAnimation.parse("crouch, 200ms, stand")));
    }

    @Test
    @DisplayName("unknown words are skipped and reported, blank is none")
    void unknownWords() {
        List<String> unknown = new ArrayList<>();
        NpcAnimation animation = NpcAnimation.parse("swing, dance, , 2x", unknown::add);
        assertEquals(List.of("0 SWING"), timeline(animation));
        assertEquals(List.of("dance", "2x"), unknown);
        assertTrue(NpcAnimation.parse("  ").isEmpty());
        assertTrue(NpcAnimation.parse(null).isEmpty());
        assertTrue(NpcAnimation.parse("dance").isEmpty());
    }
}
