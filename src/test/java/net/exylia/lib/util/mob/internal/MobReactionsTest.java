package net.exylia.lib.util.mob.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.mob.MobBehaviour;
import net.exylia.lib.util.mob.MobLook;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobTemplate;
import net.exylia.lib.util.mob.MobVisuals;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobReactionsTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Mobs");
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    @Test
    @DisplayName("AUTO suits the mob: a piñata in hits mode, a ragdoll for a humanoid, a shatter for the rest")
    void autoPicks() {
        MobLook auto = MobLook.NONE;
        assertEquals("ragdoll", MobReactions.deathOf(auto, EntityType.ZOMBIE, false));
        assertEquals("shatter", MobReactions.deathOf(auto, EntityType.COW, false));
        assertEquals("pinata", MobReactions.deathOf(auto, EntityType.ZOMBIE, true), "hits mode wins over the body");
        assertEquals("rise", MobReactions.spawnOf(auto, false));
        assertEquals("drop", MobReactions.spawnOf(auto, true));
        assertEquals("spark", MobReactions.hurtOf(auto, false));
        assertEquals("pop", MobReactions.hurtOf(auto, true));
        assertEquals("wounded", MobReactions.lowOf(auto, false));
        assertEquals("frantic", MobReactions.lowOf(auto, true));
    }

    @Test
    @DisplayName("a chosen reaction beats AUTO, NONE turns it off, and an id this version lacks plays AUTO")
    void chosenPicks() {
        MobLook look = MobLook.NONE.withDeath("SHATTER").withSpawn("off").withHurt("sizzle").withLow("Frantic");
        assertEquals("shatter", MobReactions.deathOf(look, EntityType.ZOMBIE, true));
        assertEquals(MobLook.OFF, MobReactions.spawnOf(look, false));
        assertEquals("spark", MobReactions.hurtOf(look, false));
        assertEquals("frantic", MobReactions.lowOf(look, false));
    }

    @Test
    @DisplayName("the low look starts at the lowest LOW_HEALTH threshold, or a quarter")
    void lowThreshold() {
        MobTemplate plain = MobTemplate.of("knight", EntityType.ZOMBIE);
        assertEquals(MobReactions.LOW, MobReactions.lowAt(plain));
        MobSkill enrage = low(MobSkill.Type.SPEED, 0.4);
        MobSkill last = low(MobSkill.Type.HEAL, 0.1);
        assertEquals(0.1, MobReactions.lowAt(plain.withSkills(List.of(enrage, last))), 1.0E-9);
    }

    private static MobSkill low(MobSkill.Type type, double threshold) {
        return new MobSkill(MobSkill.Trigger.LOW_HEALTH, type, 1, Duration.ZERO, threshold, 0, 1, Duration.ZERO, "", "");
    }

    @Test
    @DisplayName("hurt bursts are held to one per gap per mob, heals to their own, and each mob keeps its own clock")
    void throttle() {
        LiveMob mob = new LiveMob(MobTemplate.of("knight", EntityType.ZOMBIE), null, false, 0);
        LiveMob other = new LiveMob(MobTemplate.of("knight", EntityType.ZOMBIE), null, false, 0);

        assertTrue(mob.hurtShown(1_000, MobReactions.HURT_GAP), "the first hit always shows");
        assertFalse(mob.hurtShown(1_150, MobReactions.HURT_GAP));
        assertTrue(other.hurtShown(1_150, MobReactions.HURT_GAP), "another mob is not held back");
        assertTrue(mob.healShown(1_150, MobReactions.HEAL_GAP), "healing has its own clock");
        assertTrue(mob.hurtShown(1_200, MobReactions.HURT_GAP));
        assertFalse(mob.healShown(1_600, MobReactions.HEAL_GAP));
        assertTrue(mob.healShown(1_650, MobReactions.HEAL_GAP));
        assertFalse(mob.hurtShown(0, MobReactions.HURT_GAP), "a clock going back does not reopen it");
    }

    @Test
    @DisplayName("a drawn death takes the real body away exactly once, a tick later, and a cancelled one gives it back")
    void hideOnce() {
        MobReactions reactions = new MobReactions(plugin, Tasks.of(plugin), () -> 64);
        World world = FakeServer.newWorld("world");
        Body dying = new Body(new Location(world, 0, 64, 0), true);

        reactions.hideBody(dying.entity);
        assertTrue(dying.invisible.get(), "hidden in the tick it dies");
        assertEquals(0, dying.removed.get(), "the drops and experience come out after the event");
        FakeServer.tick(3);
        assertEquals(1, dying.removed.get());

        Body spared = new Body(new Location(world, 4, 64, 0), false);
        reactions.hideBody(spared.entity);
        FakeServer.tick(3);
        assertEquals(0, spared.removed.get(), "somebody cancelled the death");
        assertFalse(spared.invisible.get(), "and it is seen again");
    }

    @Test
    @DisplayName("a death with nothing drawn never hides the body: no display runtime, nobody watching")
    void breakIsNotHidden() {
        // The engine only hides the body when death() says a drawn one replaced it;
        // a server without displays keeps the vanilla death, in hits mode too.
        MobReactions reactions = new MobReactions(plugin, Tasks.of(plugin), () -> 64);
        World world = FakeServer.newWorld("world");
        Body broken = new Body(new Location(world, 0, 64, 0), false);
        LiveMob mob = new LiveMob(MobTemplate.of("pinata", EntityType.LLAMA)
                .withBehaviour(new MobBehaviour(10, Duration.ZERO, Duration.ZERO, 0)), broken.entity, false, 0);

        assertFalse(reactions.death(broken.entity, mob, null));
        FakeServer.tick(3);
        assertEquals(0, broken.removed.get());
    }

    @Test
    @DisplayName("visuals clamp what they are given, and the shake scales its beats down to none")
    void visuals() {
        MobVisuals visuals = new MobVisuals(500, -3, true, Double.NaN);
        assertEquals(MobVisuals.MAX_INDICATOR_RANGE, visuals.indicatorRange());
        assertEquals(0, visuals.maxCasts());
        assertEquals(1, visuals.shake());
        assertEquals(2, MobVisuals.DEFAULT.shakes(2));
        assertEquals(0, MobVisuals.DEFAULT.withShake(0).shakes(3));
        assertEquals(3, MobVisuals.DEFAULT.withShake(1.5).shakes(2));
        assertEquals(MobVisuals.MAX_SHAKE, MobVisuals.DEFAULT.withShake(10).shake());
    }

    /** A living entity that only knows where it is, whether it is dead, and how often it was removed. */
    private static final class Body {
        final AtomicInteger removed = new AtomicInteger();
        final AtomicBoolean invisible = new AtomicBoolean();
        final LivingEntity entity;

        Body(Location at, boolean dead) {
            entity = (LivingEntity) Proxy.newProxyInstance(LivingEntity.class.getClassLoader(),
                    new Class<?>[]{LivingEntity.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getLocation" -> at.clone();
                        case "isDead" -> dead;
                        case "isInvisible" -> invisible.get();
                        case "setInvisible" -> {
                            invisible.set((Boolean) args[0]);
                            yield null;
                        }
                        case "remove" -> {
                            removed.incrementAndGet();
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> FakeServer.defaultValue(method.getReturnType());
                    });
        }
    }
}
