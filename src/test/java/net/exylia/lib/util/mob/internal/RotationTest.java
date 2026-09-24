package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobFight;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rotation group: exactly one weighted pick per period, and a period nobody could use is not spent. */
class RotationTest {

    private static final long PERIOD = 4_000;

    private static MobSkill trick(MobSkill.Type type, double weight) {
        return MobSkill.of(type, MobSkill.Trigger.INTERVAL).withChance(weight).withCooldown(Duration.ZERO)
                .withCast(MobSkill.Cast.NONE.withGroup("tricks"));
    }

    private static LiveMob mob(MobSkill... skills) {
        MobTemplate template = MobTemplate.of("pinata", EntityType.LLAMA).withSkills(List.of(skills))
                .withFight(MobFight.NONE.withGroups(Map.of("tricks", Duration.ofMillis(PERIOD))));
        return new LiveMob(template, null, false, 0);
    }

    @Test
    @DisplayName("the group waits its first period, then casts exactly one member per period")
    void onePerPeriod() {
        LiveMob mob = mob(trick(MobSkill.Type.JUMP, 0.5), trick(MobSkill.Type.PUSH, 0.5));
        List<Integer> both = List.of(0, 1);

        assertFalse(mob.groupDue("tricks", PERIOD - 1));
        assertEquals(-1, mob.rotate("tricks", PERIOD, both, PERIOD - 1, 0.1));
        int casts = 0;
        for (long now = PERIOD; now < PERIOD * 11; now += 1_000) {
            if (mob.rotate("tricks", PERIOD, both, now, 0.3) >= 0) casts++;
        }
        assertEquals(10, casts, "ten periods, ten casts, whatever the timer rate");
        assertEquals(java.util.Set.of("tricks"), mob.groups());
    }

    @Test
    @DisplayName("members are picked by weight")
    void weights() {
        LiveMob mob = mob(trick(MobSkill.Type.JUMP, 0.1), trick(MobSkill.Type.PUSH, 0.3), trick(MobSkill.Type.SIZE, 0.6));
        List<Integer> all = List.of(0, 1, 2);
        int[] picked = new int[3];
        long now = PERIOD;
        for (int step = 0; step < 100; step++, now += PERIOD) {
            picked[mob.rotate("tricks", PERIOD, all, now, step / 100.0)]++;
        }
        assertEquals(10, picked[0]);
        assertEquals(30, picked[1]);
        assertEquals(60, picked[2]);
    }

    @Test
    @DisplayName("a period in which no member may be cast is not spent")
    void ineligibleNotSpent() {
        LiveMob mob = mob(trick(MobSkill.Type.JUMP, 1));

        assertEquals(-1, mob.rotate("tricks", PERIOD, List.of(), PERIOD, 0.5), "nobody near");
        assertTrue(mob.groupDue("tricks", PERIOD + 1_000), "still due");
        assertEquals(0, mob.rotate("tricks", PERIOD, List.of(0), PERIOD + 1_000, 0.5), "fires as soon as it can");
        assertFalse(mob.groupDue("tricks", PERIOD + 1_001), "and now the period is spent");
    }

    @Test
    @DisplayName("a member with no weight is never picked, and a member's own cooldown holds it out")
    void weightlessAndCooldown() {
        MobSkill slow = trick(MobSkill.Type.PUSH, 1).withCooldown(Duration.ofMillis(PERIOD * 3));
        LiveMob mob = mob(trick(MobSkill.Type.JUMP, 0), slow);
        List<Integer> both = List.of(0, 1);

        assertEquals(1, mob.rotate("tricks", PERIOD, both, PERIOD, 0.0));
        assertEquals(-1, mob.rotate("tricks", PERIOD, both, PERIOD * 2, 0.0), "the only weighted one is cooling");
        assertEquals(1, mob.rotate("tricks", PERIOD, both, PERIOD * 4, 0.0));
    }

    @Test
    @DisplayName("grouped members are not rolled one by one, and a group nobody set a period for uses the default")
    void groupedIsNotInterval() {
        MobSkill grouped = trick(MobSkill.Type.JUMP, 1);
        assertTrue(grouped.grouped());
        assertFalse(grouped.withTrigger(MobSkill.Trigger.DAMAGED).grouped(), "only INTERVAL skills take turns");
        assertEquals(MobFight.DEFAULT_PERIOD, MobFight.NONE.period("anything"));
        assertEquals(MobSkill.MIN_INTERVAL, MobFight.NONE.withGroups(Map.of("x", Duration.ZERO)).period("x"));
    }
}
