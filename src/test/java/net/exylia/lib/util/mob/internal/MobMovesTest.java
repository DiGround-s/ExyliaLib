package net.exylia.lib.util.mob.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.mob.MobBehaviour;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobSkills;
import net.exylia.lib.util.mob.MobTemplate;
import net.exylia.lib.util.mob.MobTheme;
import net.exylia.lib.util.mob.MobVisuals;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The types that take their time: a zone ends whichever way its mob goes, and
 * a shield takes its share off a hit, or the whole hit in hits mode.
 */
class MobMovesTest {

    private Plugin plugin;
    private MobMoves moves;
    private Location centre;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Mobs");
        moves = new MobEngine(plugin).moves();
        centre = new Location(loadedWorld(FakeServer.newWorld("zones")), 0, 64, 0);
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    private LiveMob alive(MobTemplate template) {
        LivingEntity entity = body(centre);
        LiveMob mob = new LiveMob(template, entity, false, 0);
        mob.timer(Tasks.of(plugin).runTimer(1_000, 1_000, () -> { }));
        return mob;
    }

    private Stage stage(LiveMob mob, MobSkill skill) {
        return new Stage(plugin, skill, null, MobAim.lock(centre, centre), mob.entity(), mob, List.of(), false,
                Stage.WIDTH, Stage.HEIGHT, MobBodies.FALLBACK, MobBodies.block(Material.STONE), MobTheme.DEFAULT,
                MobVisuals.DEFAULT, false);
    }

    @Test
    @DisplayName("a zone keeps going while its mob is there and its time is not up")
    void zoneKeeps() {
        assertTrue(MobMoves.Zone.keeps(1_000, 2_000, true, true));
        assertFalse(MobMoves.Zone.keeps(2_000, 2_000, true, true), "its time is up");
        assertFalse(MobMoves.Zone.keeps(1_000, 2_000, false, true), "its mob is gone");
        assertFalse(MobMoves.Zone.keeps(1_000, 2_000, true, false), "its ground unloaded");
    }

    @Test
    @DisplayName("a zone ends on the unload path: its mob's timer stops, and the next pulse closes it")
    void zoneEndsOnUnload() {
        MobSkill miasma = MobSkills.preset("miasma").skill().withText("");
        LiveMob mob = alive(MobTemplate.of("witch", EntityType.WITCH));
        MobMoves.Zone zone = moves.zone(mob.entity(), mob, miasma, stage(mob, miasma), centre, false);
        assertNotNull(zone);

        FakeServer.tick(25);
        assertFalse(zone.stopped(), "still there and loaded, it pulses on");

        mob.end();
        FakeServer.tick(11);
        assertTrue(zone.stopped(), "the mob unloaded, so the zone closed");
        assertTrue(mob.openZone(MobMoves.MAX_ZONES) && mob.openZone(MobMoves.MAX_ZONES), "and gave its slot back");
    }

    @Test
    @DisplayName("a mob's death stops its zones at once, and two at most stand at a time")
    void zoneEndsOnDeath() {
        MobSkill blades = MobSkills.preset("blades").skill().withCast(MobSkill.Cast.NONE);
        LiveMob mob = alive(MobTemplate.of("knight", EntityType.ZOMBIE));
        MobMoves.Zone first = moves.zone(mob.entity(), mob, blades, stage(mob, blades), centre, false);
        MobMoves.Zone second = moves.zone(mob.entity(), mob, blades, stage(mob, blades), centre, false);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(moves.zone(mob.entity(), mob, blades, stage(mob, blades), centre, false), "a third waits");

        mob.endLingering();
        assertTrue(first.stopped());
        assertTrue(second.stopped());
        FakeServer.tick(15);
        assertNotNull(moves.zone(mob.entity(), mob, blades, stage(mob, blades), centre, false), "slots are free again");
    }

    @Test
    @DisplayName("a zone that runs its time out closes itself")
    void zoneRunsOut() {
        MobSkill miasma = MobSkills.preset("miasma").skill();
        MobSkill short_ = new MobSkill(miasma.trigger(), miasma.type(), 1, Duration.ZERO, 0, 3, 1,
                Duration.ofMillis(250), "", "", miasma.cast());
        LiveMob mob = alive(MobTemplate.of("witch", EntityType.WITCH));
        MobMoves.Zone zone = moves.zone(mob.entity(), mob, short_, stage(mob, short_), centre, false);
        assertNotNull(zone);
        try {
            Thread.sleep(300);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        FakeServer.tick(11);
        assertTrue(zone.stopped());
    }

    @Test
    @DisplayName("a shield takes its share off a hit while it lasts, and all of it at 100")
    void shieldReduces() {
        LiveMob mob = alive(MobTemplate.of("knight", EntityType.ZOMBIE));
        assertEquals(10, MobMoves.throughShield(mob, 10, 0), 1.0E-9, "no shield up");

        mob.shield(0.6, 5_000);
        assertEquals(4, MobMoves.throughShield(mob, 10, 1_000), 1.0E-9);
        assertEquals(10, MobMoves.throughShield(mob, 10, 5_000), 1.0E-9, "it dropped");

        mob.shield(1.0, 5_000);
        assertEquals(0, MobMoves.throughShield(mob, 10, 1_000), 1.0E-9);
        mob.shield(7, 5_000);
        assertEquals(0, MobMoves.throughShield(mob, 10, 1_000), 1.0E-9, "never more than all of it");
    }

    @Test
    @DisplayName("in hits mode a shield lets no hit count while it lasts")
    void shieldIgnoresHits() {
        LiveMob pinata = alive(MobTemplate.of("pinata", EntityType.LLAMA)
                .withBehaviour(new MobBehaviour(10, Duration.ZERO, Duration.ZERO, 0)));
        UUID player = UUID.randomUUID();
        pinata.shield(0.25, 5_000);

        assertFalse(MobMoves.countsHit(pinata, 1_000), "even a small shield stops the count");
        assertTrue(MobMoves.countsHit(pinata, 5_000));
        assertEquals(9, pinata.hit(player, 5_000));
    }

    @Test
    @DisplayName("a barrage, a chain and a dash read their numbers with sane defaults and caps")
    void numbers() {
        MobSkill barrage = MobSkill.of(MobSkill.Type.BARRAGE, MobSkill.Trigger.INTERVAL);
        assertEquals(5, MobMoves.strikes(barrage));
        assertEquals(MobMoves.MAX_STRIKES, MobMoves.strikes(new MobSkill(MobSkill.Trigger.INTERVAL,
                MobSkill.Type.BARRAGE, 1, Duration.ZERO, 0, 6, 99, Duration.ZERO, "4")));
        assertEquals(4, MobMoves.strikeDamage(barrage));
        assertEquals(4, MobMoves.strikeDamage(barrage.withText("lots")), "unreadable is the default");
        MobSkill chain = MobSkill.of(MobSkill.Type.CHAIN, MobSkill.Trigger.INTERVAL);
        assertEquals(4, MobMoves.jumps(chain));
        assertEquals(MobMoves.MAX_JUMPS, MobMoves.jumps(chain.withText("40")));
        assertEquals(1, MobMoves.jumps(chain.withText("0")));
        MobSkill dash = MobSkill.of(MobSkill.Type.DASH, MobSkill.Trigger.INTERVAL);
        assertEquals(12, MobMoves.dashReach(dash));
        assertEquals(MobMoves.MAX_DASH, MobMoves.dashReach(new MobSkill(dash.trigger(), dash.type(), 1,
                Duration.ZERO, 0, 90, 5, Duration.ZERO, "")));
    }

    /** The fake world, answering that every chunk is loaded. */
    private static World loadedWorld(World world) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isChunkLoaded" -> true;
                    case "getNearbyEntities" -> List.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(world, args);
                });
    }

    private static LivingEntity body(Location at) {
        UUID id = UUID.randomUUID();
        return (LivingEntity) Proxy.newProxyInstance(LivingEntity.class.getClassLoader(),
                new Class<?>[]{LivingEntity.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> at.clone();
                    case "getWorld" -> at.getWorld();
                    case "getUniqueId" -> id;
                    case "getWidth" -> 0.6;
                    case "getHeight" -> 1.95;
                    case "isValid" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }
}
