package net.exylia.lib.util.mob.internal;

import net.exylia.lib.util.mob.MobAttack;
import net.exylia.lib.util.mob.MobBody;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The carrier under a body, its size and weight, what it decides and how its blows land. */
class BodiesTest {

    @Test
    @DisplayName("a walker is carried by the vanilla mob shaped most like the model")
    void carrierByShape() {
        assertEquals(EntityType.WANDERING_TRADER, Carriers.of(MobBody.Kind.WALKER, 0.7, 2.0).type(), "a person");
        assertEquals(EntityType.IRON_GOLEM, Carriers.of(MobBody.Kind.HEAVY, 1.8, 3.2).type(), "a golem");
        assertEquals(EntityType.PIG, Carriers.of(MobBody.Kind.WALKER, 1.6, 1.2).type(), "a beast");
        assertEquals(EntityType.PIG, Carriers.of(MobBody.Kind.STATIC, 3, 0.5).type(), "flat and wide");
        assertEquals(EntityType.SPIDER, Carriers.of(MobBody.Kind.CLIMBER, 0.7, 2.0).type());
        assertEquals(EntityType.BEE, Carriers.of(MobBody.Kind.HOVER, 0.5, 0.5).type());
        assertEquals(EntityType.BEE, Carriers.of(MobBody.Kind.FLYER, 4, 2).type());
        assertEquals(EntityType.DOLPHIN, Carriers.of(MobBody.Kind.SWIMMER, 2, 1).type());
    }

    @Test
    @DisplayName("the carrier is grown as big as it can be without sticking out of the model")
    void fit() {
        Carriers.Carrier golem = Carriers.of(MobBody.Kind.HEAVY, 2.8, 5.4);
        double scale = golem.fit(2.8, 5.4);

        assertEquals(2.0, scale, 1e-9);
        assertTrue(golem.width() * scale <= 2.8 + 1e-9 && golem.height() * scale <= 5.4 + 1e-9);
        Carriers.Carrier pig = Carriers.of(MobBody.Kind.WALKER, 3, 0.9);
        assertEquals(1.0, pig.fit(3, 0.9), 1e-9, "the height bounds a flat model");
        assertEquals(Carriers.MIN_SCALE, pig.fit(0.01, 0.01));
        assertEquals(Carriers.MAX_SCALE, pig.fit(400, 400));
    }

    @Test
    @DisplayName("a heavy body steps up blocks and shrugs off pushes; a turret never moves")
    void physics() {
        Map<String, Double> heavy = Carriers.physics(MobBody.of(MobBody.Kind.HEAVY), 3);
        Map<String, Double> walker = Carriers.physics(MobBody.of(MobBody.Kind.WALKER).withSpeed(5), 1.8);
        Map<String, Double> turret = Carriers.physics(MobBody.of(MobBody.Kind.STATIC).withSpeed(9), 1);

        assertTrue(heavy.get("step_height") >= 1.0);
        assertEquals(0.85, heavy.get("knockback_resistance"));
        assertEquals(0.2, heavy.get("movement_speed"), 1e-9);
        assertEquals(0.5, walker.get("movement_speed"), 1e-9);
        assertTrue(walker.get("step_height") < 1.0);
        assertEquals(0.0, turret.get("movement_speed"));
        assertEquals(1.0, turret.get("knockback_resistance"));
        assertTrue(Carriers.physics(MobBody.of(MobBody.Kind.HOVER), 1).containsKey("flying_speed"));
        assertFalse(walker.containsKey("flying_speed"));
    }

    private static Brain.Sense sense(boolean target, double distance, double keep, double health, double fromHome) {
        return new Brain.Sense(target, distance, 2, keep, health, 0.25, fromHome, 20, true, true);
    }

    @Test
    @DisplayName("it hunts what is out of reach, holds what is in it, flees when nearly dead and goes home past its roam")
    void decisions() {
        assertEquals(Brain.Intent.HUNT, Brain.decide(sense(true, 6, 0, 1, 0)));
        assertEquals(Brain.Intent.HOLD, Brain.decide(sense(true, 1.5, 0, 1, 0)));
        assertEquals(Brain.Intent.FLEE, Brain.decide(sense(true, 1.5, 0, 0.2, 0)));
        assertEquals(Brain.Intent.HOME, Brain.decide(sense(true, 1.5, 0, 1, 25)));
        assertEquals(Brain.Intent.ROAM, Brain.decide(sense(false, 0, 0, 1, 3)));
        assertEquals(Brain.Intent.IDLE, Brain.decide(new Brain.Sense(false, 0, 2, 0, 1, 0, 3, 0, false, true)));
        assertEquals(Brain.Intent.HOLD, Brain.decide(new Brain.Sense(true, 9, 2, 0, 1, 0, 0, 0, false, false)),
                "a turret only turns");
    }

    @Test
    @DisplayName("a mob that keeps its distance backs off when crowded and follows when left behind")
    void keepDistance() {
        assertEquals(Brain.Intent.BACK_OFF, Brain.decide(sense(true, 2, 8, 1, 0)));
        assertEquals(Brain.Intent.HOLD, Brain.decide(sense(true, 8, 8, 1, 0)));
        assertEquals(Brain.Intent.HUNT, Brain.decide(sense(true, 11, 8, 1, 0)));
    }

    @Test
    @DisplayName("it strikes with the first ready attack that reaches, a blow before a throw")
    void pick() {
        MobAttack bite = MobAttack.melee("bite").withReach(1.5);
        MobAttack spit = MobAttack.ranged("spit", "SNOWBALL").withReach(10);
        List<MobAttack> attacks = List.of(spit, bite);
        long[] ready = new long[2];

        assertEquals(1, Strikes.pick(attacks, ready, 1, 0), "close: the bite");
        assertEquals(0, Strikes.pick(attacks, ready, 6, 0), "far: the spit");
        assertEquals(-1, Strikes.pick(attacks, ready, 12, 0), "out of reach");
        ready[1] = 500;
        assertEquals(0, Strikes.pick(attacks, ready, 1, 100), "the bite cools down, so it spits");
        assertEquals(10, Strikes.reach(attacks));
    }

    @Test
    @DisplayName("a blow lands on the animation's beat, or on its own wind-up")
    void timing() {
        assertEquals(400, Strikes.delay(400, 0));
        assertEquals(900, Strikes.delay(400, 900));
        assertEquals(Strikes.DEFAULT_WINDUP, Strikes.delay(-1, 0));
    }

    @Test
    @DisplayName("a blow misses a target that stepped out of reach, round behind it or behind a wall")
    void lands() {
        MobAttack slam = MobAttack.melee("slam").withReach(2).withArc(90);

        assertTrue(Strikes.lands(slam, 2.2, 30, true));
        assertFalse(Strikes.lands(slam, 2.6, 30, true), "stepped back");
        assertFalse(Strikes.lands(slam, 1, 60, true), "round its side");
        assertFalse(Strikes.lands(slam, 1, 0, false), "behind a wall");
        assertTrue(Strikes.lands(MobAttack.ranged("bolt", "ARROW"), 5, 170, true), "a throw has no arc");
        assertEquals(0, Strikes.angle(0, 0, 1), 1e-9, "yaw 0 faces south");
        assertEquals(90, Strikes.angle(0, 1, 0), 1e-9);
        assertEquals(180, Strikes.angle(90, 1, 0), 1e-9);
        assertEquals(7, Strikes.damage(MobAttack.melee("x").withDamage(7), 4.0));
        assertEquals(4, Strikes.damage(MobAttack.DEFAULT, 4.0));
        assertEquals(3, Strikes.damage(MobAttack.DEFAULT, null));
    }

    @Test
    @DisplayName("attacks read sensibly whatever is written")
    void attackDefaults() {
        MobAttack odd = new MobAttack(" Big Swing ", Double.NaN, -5, -1, Duration.ofMillis(-1), 99, " arrow ", "", null);

        assertEquals("big swing", odd.name());
        assertEquals(1.2, odd.reach());
        assertEquals(10, odd.arc());
        assertEquals(10, odd.knockback());
        assertEquals("ARROW", odd.projectile());
        assertTrue(odd.ranged());
        assertEquals("attack", MobAttack.DEFAULT.state());
        assertEquals("attack_slam", MobAttack.melee("slam").state());
        assertEquals("smash", MobAttack.melee("slam").withAnimation("smash").state());
    }
}
