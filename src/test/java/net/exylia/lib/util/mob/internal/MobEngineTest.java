package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobFlag;
import net.exylia.lib.util.mob.MobLook;
import net.exylia.lib.util.mob.MobTemplate;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobEngineTest {

    @Test
    @DisplayName("an aura's three angles turn together, a third of a turn apart")
    void auraFrames() {
        String[] frames = MobEngine.frames(List.of("a:%angle%", "b:%angle2%;c:%angle3%"));

        assertEquals(MobEngine.FRAMES, frames.length);
        assertEquals("a:0\nb:120;c:240", frames[0]);
        assertEquals("a:20\nb:140;c:260", frames[1]);
        assertEquals("a:340\nb:100;c:220", frames[17]);
        for (int frame = 0; frame < MobEngine.FRAMES; frame++) {
            String[] parts = frames[frame].split("[\\n;]");
            int a = Integer.parseInt(parts[0].substring(2));
            int b = Integer.parseInt(parts[1].substring(2));
            int c = Integer.parseInt(parts[2].substring(2));
            assertEquals(120, Math.floorMod(b - a, 360), "frame " + frame);
            assertEquals(120, Math.floorMod(c - b, 360), "frame " + frame);
        }
    }

    @Test
    @DisplayName("a size range reads both orders, one number, and never goes below 0.1")
    void sizeRange() {
        assertArrayEquals(new double[]{0.7, 1.8}, MobEngine.sizeRange("0.7|1.8"));
        assertArrayEquals(new double[]{0.7, 1.8}, MobEngine.sizeRange(" 1.8 | 0.7 "));
        assertArrayEquals(new double[]{1.2, 1.2}, MobEngine.sizeRange("1.2"));
        assertArrayEquals(new double[]{0.1, 0.5}, MobEngine.sizeRange("0|0.5"));
        assertArrayEquals(new double[]{0.1, 0.1}, MobEngine.sizeRange("0.01"));
        assertNull(MobEngine.sizeRange(""));
        assertNull(MobEngine.sizeRange("big"));
        assertNull(MobEngine.sizeRange("NaN"));
    }

    @Test
    @DisplayName("only a mob with an aura, a rainbow name or a wander runs the fast timer")
    void fastTimer() {
        MobTemplate plain = MobTemplate.of("zombie", EntityType.ZOMBIE).withName("{primary}&lZOMBIE %health%");

        assertFalse(MobEngine.fast(plain), "a normal mob keeps its one-second timer");
        assertFalse(MobEngine.fast(plain.withLook(new MobLook("", "", MobLook.CYCLE, ""))));
        assertTrue(MobEngine.fast(plain.withLook(MobLook.NONE.withAura("sparks"))));
        assertTrue(MobEngine.fast(plain.withName("<rainbow>PIÑATA")));
        assertTrue(MobEngine.fast(plain.withFlags(Set.of(MobFlag.WANDERS))));
    }
}
