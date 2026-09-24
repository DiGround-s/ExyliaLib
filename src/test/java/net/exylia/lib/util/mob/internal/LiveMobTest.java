package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobBehaviour;
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

    private static LiveMob hitsMob(int hits, long cooldownMillis, long lifetimeMillis) {
        return new LiveMob(MobTemplate.of("pinata", EntityType.LLAMA).withBehaviour(new MobBehaviour(hits,
                Duration.ofMillis(cooldownMillis), Duration.ofMillis(lifetimeMillis), 0)), null, false, 0);
    }

    @Test
    @DisplayName("hits count down, one per player per cooldown, and each counts one in the ledger")
    void hitsCountDown() {
        LiveMob mob = hitsMob(3, 500, 0);

        assertTrue(mob.usesHits());
        assertEquals(2, mob.hit(ALEX, 1_000));
        assertEquals(-1, mob.hit(ALEX, 1_499), "Alex is still on his cooldown");
        assertEquals(1, mob.hit(STEVE, 1_100), "the cooldown is per player");
        assertEquals(0, mob.hit(ALEX, 1_500), "the last hit breaks it");
        assertEquals(-1, mob.hit(STEVE, 5_000), "a broken mob counts nothing");

        assertEquals(Map.of(ALEX, 2.0, STEVE, 1.0), mob.damage());
        assertEquals(ALEX, mob.topDamager());
        assertEquals(1.0, mob.playerShare());
        assertEquals(0, mob.hitsLeft());
        assertEquals(3, mob.maxHits());
    }

    @Test
    @DisplayName("a mob with no hits is a health mob and counts none")
    void healthMobCountsNoHits() {
        LiveMob mob = mob();

        assertFalse(mob.usesHits());
        assertEquals(-1, mob.hit(ALEX, 0));
    }

    @Test
    @DisplayName("the lifetime runs out at its length, and zero never does")
    void lifetime() {
        assertFalse(hitsMob(1, 0, 60_000).expired(59_999));
        assertTrue(hitsMob(1, 0, 60_000).expired(60_000));
        assertFalse(mob().expired(Long.MAX_VALUE / 2));
    }

    @Test
    @DisplayName("the leash walks back past the roam and teleports eight blocks further")
    void leash() {
        assertEquals(LiveMob.Leash.STAY, LiveMob.leash(true, 10 * 10, 10));
        assertEquals(LiveMob.Leash.WALK_BACK, LiveMob.leash(true, 10.1 * 10.1, 10));
        assertEquals(LiveMob.Leash.WALK_BACK, LiveMob.leash(true, 18 * 18, 10));
        assertEquals(LiveMob.Leash.TELEPORT_BACK, LiveMob.leash(true, 18.1 * 18.1, 10));
        assertEquals(LiveMob.Leash.TELEPORT_BACK, LiveMob.leash(false, 0, 10), "another world");
        assertEquals(LiveMob.Leash.STAY, LiveMob.leash(false, 1e9, 0), "no roam, no leash");
    }

    @Test
    @DisplayName("only the latest speed boost puts the speed back")
    void speedBoost() {
        LiveMob mob = mob();
        mob.baseSpeed(0.175);
        int first = mob.boostSpeed();
        int second = mob.boostSpeed();

        assertFalse(mob.endBoost(first), "an earlier boost must not cut the later one short");
        assertTrue(mob.endBoost(second));
        assertEquals(0.175, mob.baseSpeed());
    }
}
