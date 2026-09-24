package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobFight;
import net.exylia.lib.util.mob.MobPhase;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobCasterTest {

    private static MobSkill named(String name, String then) {
        return MobSkill.of(MobSkill.Type.JUMP, MobSkill.Trigger.INTERVAL)
                .withCast(MobSkill.Cast.NONE.withName(name).withThen(then));
    }

    @Test
    @DisplayName("a chain follows names and stops at its depth cap, so A then B then A cannot loop")
    void chainCap() {
        List<MobSkill> skills = List.of(named("a", "B"), named("b", "a"), named("c", ""));

        assertEquals(1, MobCaster.next(skills, skills.get(0), 0), "names match in any case");
        assertEquals(0, MobCaster.next(skills, skills.get(1), 1));
        assertEquals(1, MobCaster.next(skills, skills.get(0), MobCaster.MAX_CHAIN - 1));
        assertEquals(-1, MobCaster.next(skills, skills.get(1), MobCaster.MAX_CHAIN), "past the cap");
        assertEquals(-1, MobCaster.next(skills, skills.get(2), 0), "no then");
        assertEquals(-1, MobCaster.next(skills, named("d", "nobody"), 0), "a name nobody has");
    }

    @Test
    @DisplayName("phases count the thresholds crossed, and a fight never goes back")
    void phasesMonotonic() {
        MobFight fight = MobFight.NONE.withPhases(List.of(
                new MobPhase(0.25, "", "&4☠", 1, 1, 2), new MobPhase(0.5, "", "&c⚡", 1.3, 1, 1)));
        assertEquals(0.5, fight.phases().get(0).below(), "sorted highest first");
        assertEquals(1, fight.phaseAt(0.9));
        assertEquals(1, fight.phaseAt(0.5), "at the threshold is not below it");
        assertEquals(2, fight.phaseAt(0.4));
        assertEquals(3, fight.phaseAt(0.1));
        assertNull(fight.phase(1));
        assertEquals("&c⚡", fight.phase(2).suffix());
        assertEquals("&4☠", fight.phase(3).suffix());

        LiveMob mob = new LiveMob(MobTemplate.of("boss", EntityType.ZOMBIE).withFight(fight), null, false, 0);
        assertEquals(1, mob.fightPhase());
        assertTrue(mob.enterPhase(fight.phaseAt(0.1)), "one big hit goes straight to 3");
        assertFalse(mob.enterPhase(fight.phaseAt(0.9)), "healing does not calm it");
        assertFalse(mob.enterPhase(fight.phaseAt(0.4)));
        assertEquals(3, mob.fightPhase());
        assertEquals(5, MobCaster.resisted(mob, 10), 1.0E-9, "phase 3 resists twice over");
    }

    @Test
    @DisplayName("phases outside 0-1 are dropped and multipliers stay in range")
    void phaseClamped() {
        MobFight fight = MobFight.NONE.withPhases(List.of(new MobPhase(1, "", "", 1, 1, 1),
                new MobPhase(0, "", "", 1, 1, 1), new MobPhase(0.3, "", "  x  ", 0, 99, Double.NaN)));
        assertEquals(1, fight.phases().size());
        MobPhase phase = fight.phases().get(0);
        assertEquals("x", phase.suffix());
        assertEquals(1, phase.speed(), "zero is no multiplier, not a frozen mob");
        assertEquals(10, phase.damage());
        assertEquals(1, phase.resist());
    }

    @Test
    @DisplayName("an aim decides whether the skill needs its target before the type does")
    void aimNeedsTarget() {
        MobSkill push = MobSkill.of(MobSkill.Type.PUSH, MobSkill.Trigger.INTERVAL);
        MobSkill leap = MobSkill.of(MobSkill.Type.LEAP, MobSkill.Trigger.INTERVAL);

        assertFalse(push.needsTarget());
        assertTrue(push.withCast(MobSkill.Cast.NONE.withAim(MobSkill.Aim.GROUND)).needsTarget());
        assertTrue(leap.needsTarget());
        assertFalse(leap.withCast(MobSkill.Cast.NONE.withAim(MobSkill.Aim.NEAREST)).needsTarget());
        assertFalse(MobSkill.of(MobSkill.Type.EFFECT, MobSkill.Trigger.INTERVAL).major());
        assertTrue(push.major());
        assertEquals(MobSkill.Cast.MAX_WINDUP, MobSkill.Cast.NONE.withWindup(Duration.ofMinutes(5)).windup());
    }
}
