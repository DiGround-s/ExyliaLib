package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveMobTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static LiveMob mob(MobSkill... skills) {
        return new LiveMob(MobTemplate.of("knight", EntityType.ZOMBIE).withSkills(List.of(skills)), null, false, 0);
    }

    @Test
    @DisplayName("a cooldown starts when the skill fires and holds until it runs out")
    void cooldown() {
        MobSkill leap = MobSkill.of(MobSkill.Type.LEAP, MobSkill.Trigger.ATTACK).withCooldown(Duration.ofSeconds(5));
        LiveMob mob = mob(leap);

        assertTrue(mob.attempt(0, leap, 1_000, 0));
        assertFalse(mob.attempt(0, leap, 5_999, 0));
        assertTrue(mob.attempt(0, leap, 6_000, 0));
    }

    @Test
    @DisplayName("a lost roll does not start the cooldown of an event skill")
    void lostRollKeepsReady() {
        MobSkill half = MobSkill.of(MobSkill.Type.HEAL, MobSkill.Trigger.DAMAGED)
                .withChance(0.5).withCooldown(Duration.ofSeconds(10));
        LiveMob mob = mob(half);

        assertFalse(mob.attempt(0, half, 1_000, 0.9));
        assertTrue(mob.attempt(0, half, 1_001, 0.1));
    }

    @Test
    @DisplayName("an interval skill waits its first period and spends each period, won or lost")
    void interval() {
        MobSkill every = MobSkill.of(MobSkill.Type.PUSH, MobSkill.Trigger.INTERVAL)
                .withChance(0.5).withCooldown(Duration.ofSeconds(10));
        LiveMob mob = mob(every);

        assertFalse(mob.attempt(0, every, 9_999, 0), "not before its first period");
        assertFalse(mob.attempt(0, every, 10_000, 0.9), "lost the roll");
        assertFalse(mob.attempt(0, every, 11_000, 0.1), "and that spent the period");
        assertTrue(mob.attempt(0, every, 20_000, 0.1));
    }

    @Test
    @DisplayName("an interval shorter than the mob's timer runs at the timer")
    void intervalFloor() {
        MobSkill fast = MobSkill.of(MobSkill.Type.PUSH, MobSkill.Trigger.INTERVAL).withCooldown(Duration.ZERO);

        assertEquals(MobSkill.MIN_INTERVAL, fast.period());
    }

    @Test
    @DisplayName("a low-health skill fires once, the first time health reaches the threshold")
    void lowHealthOnce() {
        MobSkill enrage = MobSkill.of(MobSkill.Type.HEAL, MobSkill.Trigger.LOW_HEALTH);
        LiveMob mob = mob(enrage);

        assertFalse(mob.lowHealth(0, enrage, 0.8, 0));
        assertTrue(mob.lowHealth(0, enrage, 0.3, 0));
        assertFalse(mob.lowHealth(0, enrage, 0.1, 0));
    }

    @Test
    @DisplayName("the top damager is whoever dealt most, and the share counts every source")
    void damage() {
        LiveMob mob = mob();
        mob.hurt(ALEX, 6);
        mob.hurt(STEVE, 10);
        mob.hurt(ALEX, 6);
        mob.hurt(null, 8);
        mob.hurt(STEVE, Double.NaN);

        assertEquals(Map.of(ALEX, 12.0, STEVE, 10.0), mob.damage());
        assertEquals(ALEX, mob.topDamager());
        assertEquals(22.0 / 30.0, mob.playerShare(), 1e-9);
    }

    @Test
    @DisplayName("a mob only the environment hurt has no top damager and no player share")
    void environmentOnly() {
        LiveMob mob = mob();
        mob.hurt(null, 20);

        assertNull(mob.topDamager());
        assertEquals(0, mob.playerShare());
        assertEquals(0, mob().playerShare(), "untouched is zero, not a division by zero");
    }

    @Test
    @DisplayName("a mob with no timer yet counts as gone")
    void aliveNeedsTimer() {
        assertFalse(mob().alive());
    }
}
