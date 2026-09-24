package net.exylia.lib.util.mob.internal;

import net.exylia.lib.FakeServer;
import net.exylia.lib.display.Vfx;
import net.exylia.lib.util.mob.MobSkill;
import net.exylia.lib.util.mob.MobSkills;
import net.exylia.lib.util.mob.MobTheme;
import net.exylia.lib.util.mob.MobVisuals;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every style, built with no server and counted: it stays within one effect's
 * budget at both levels of detail, a crowd gets fewer pieces, and every sound
 * it names is a real one.
 */
class MobStylesTest {

    /** The per-effect ceiling {@code displays.yml} ships. */
    private static final int BUDGET = 128;

    private Plugin plugin;
    private World world;
    private Location origin;
    private Location target;
    private final Set<String> sounds = ConcurrentHashMap.newKeySet();
    private Function<Material, ItemStack> items;
    private Function<String, Sound> resolver;

    /** An item with nothing behind it: {@code new ItemStack(...)} needs a real server. */
    private static final class Piece extends ItemStack {
        final Material material;

        Piece(Material material) {
            this.material = material;
        }

        @Override
        public @NotNull Material getType() {
            return material;
        }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        plugin = FakeServer.newPlugin("Mobs");
        world = FakeServer.newWorld("styles");
        origin = new Location(world, 0, 64, 0, 0, 0);
        target = new Location(world, 0, 64, 8);
        items = Shapes.items;
        resolver = Shapes.sounds;
        Shapes.items = Piece::new;
        Shapes.sounds = name -> {
            sounds.add(name);
            return null;
        };
    }

    @AfterEach
    void tearDown() {
        Shapes.items = items;
        Shapes.sounds = resolver;
        FakeServer.reset();
    }

    // -------------------------------------------------------------- building

    /** The skill a style is tested with: its preset, or the type it is the default of. */
    private static MobSkill skillOf(MobStyle style) {
        MobSkills.Preset preset = MobSkills.preset(style.id);
        if (preset != null) return preset.skill();
        MobSkill.Type type = switch (style) {
            case BURST -> MobSkill.Type.PUSH;
            case HOP -> MobSkill.Type.JUMP;
            case INFLATE -> MobSkill.Type.SIZE;
            case ZOOM -> MobSkill.Type.SPEED;
            case SHRINK -> MobSkill.Type.BABY;
            case PUFF -> MobSkill.Type.POTION;
            default -> throw new AssertionError(style + " has neither a preset nor a type");
        };
        return MobSkill.of(type, MobSkill.Trigger.INTERVAL)
                .withCast(MobSkill.Cast.NONE.withStyle(style.id).withWindup(Duration.ofMillis(500)));
    }

    private Stage stage(MobSkill skill, boolean lod) {
        MobStyle style = MobStyle.of(MobSkills.styleOf(skill));
        Stage stage = new Stage(plugin, skill, style, new MobAim.Lock(origin, 0f, target), null, null, List.of(),
                lod, Stage.WIDTH, Stage.HEIGHT, MobBodies.FALLBACK, MobBodies.block(Material.STONE),
                MobTheme.DEFAULT, MobVisuals.DEFAULT, true);
        stage.reached(List.of(body(target)));
        for (int spot = 0; spot < 5; spot++) stage.spots.add(origin.clone().add(spot - 2, 0, 2));
        return stage;
    }

    /** Every effect one cast of a style builds: wind-up, impact, and what its type draws over time. */
    private List<Vfx> build(MobStyle style, boolean lod) {
        MobSkill skill = skillOf(style);
        Stage stage = stage(skill, lod);
        List<Vfx> built = new ArrayList<>();
        if (stage.windup() > 0) {
            Vfx windup = stage.vfx(origin);
            style.windup(stage, windup);
            built.add(windup);
        }
        Vfx impact = stage.vfx(origin);
        style.impact(stage, impact, origin);
        built.add(impact);
        switch (skill.type()) {
            case DASH -> {
                Vfx dash = stage.vfx(origin);
                for (int stride = 0; stride < 12; stride++) {
                    MobShows.afterimage(stage, dash, stride * 100L, origin.clone().add(0, 0, stride * 2));
                }
                MobShows.daze(stage, dash, 1_200, null, origin);
                built.add(dash);
            }
            case CHAIN -> {
                for (int jump = 0; jump < MobMoves.MAX_JUMPS; jump++) {
                    Vfx arc = stage.vfx(origin);
                    MobStyle.arc(stage, arc, 0, origin.clone().add(0, 1, 0), target.clone().add(jump, 1, 0), jump);
                    built.add(arc);
                }
            }
            case SHIELD -> {
                Vfx ward = stage.vfx(origin);
                MobShows.ward(stage, ward, 0, null, origin, MobMoves.MAX_LINGER);
                built.add(ward);
            }
            case ZONE -> {
                Vfx zone = stage.vfx(origin);
                if (style == MobStyle.BLADES) {
                    MobShows.blades(stage, zone, origin, null, MobMoves.zoneRadius(skill), MobMoves.MAX_LINGER);
                } else {
                    MobShows.pool(stage, zone, target, MobMoves.zoneRadius(skill), MobMoves.MAX_LINGER);
                }
                built.add(zone);
            }
            case BARRAGE -> {
                Vfx strike = Vfx.at(target).lod(true);
                MobShows.strike(stage, strike, 0, target);
                built.add(strike);
            }
            case LEAP -> {
                Vfx landing = stage.vfx(target);
                MobShows.landing(stage, landing, 0, target, 8);
                built.add(landing);
            }
            default -> { }
        }
        return built;
    }

    private static int displays(List<Vfx> built) {
        return built.stream().mapToInt(Vfx::displays).sum();
    }

    // ----------------------------------------------------------------- tests

    @Test
    @DisplayName("the library and the recipes name the same styles, and every preset is drawn in its own")
    void idsMatch() {
        List<String> recipes = java.util.Arrays.stream(MobStyle.values()).map(style -> style.id).toList();
        assertEquals(MobSkills.STYLES, recipes);
        for (MobSkills.Preset preset : MobSkills.library()) {
            assertEquals(preset.id(), MobSkills.styleOf(preset.skill()), preset.id());
            assertNotNull(MobStyle.of(preset.id()), preset.id());
        }
    }

    @Test
    @DisplayName("every style builds within one effect's budget at full detail and at low detail")
    void withinBudget() {
        for (MobStyle style : MobStyle.values()) {
            for (boolean lod : new boolean[]{false, true}) {
                List<Vfx> built = assertDoesNotThrow(() -> build(style, lod), style + " lod " + lod);
                for (Vfx vfx : built) {
                    assertEquals(0, vfx.dropped(), style + " lod " + lod + " went over the budget");
                    assertTrue(vfx.displays() <= BUDGET, style + " lod " + lod + ": " + vfx.displays());
                }
            }
        }
    }

    @Test
    @DisplayName("a crowd sees fewer pieces, never more, and the heavy styles clearly fewer")
    void lowerDetail() {
        Set<MobStyle> heavy = Set.of(MobStyle.SLAM, MobStyle.METEOR, MobStyle.NOVA, MobStyle.PORTAL,
                MobStyle.BUBBLE, MobStyle.MIASMA, MobStyle.BLADES, MobStyle.CHAIN, MobStyle.ENRAGE, MobStyle.BURST,
                MobStyle.BLINK, MobStyle.RENEW, MobStyle.CHARGE, MobStyle.POUNCE, MobStyle.HOP);
        for (MobStyle style : MobStyle.values()) {
            int full = displays(build(style, false));
            int low = displays(build(style, true));
            assertTrue(low <= full, style + ": " + low + " at low detail, " + full + " at full");
            if (heavy.contains(style)) assertTrue(low < full, style + ": " + low + " is not fewer than " + full);
        }
    }

    @Test
    @DisplayName("the counts the design promises: a slam's circle and rings, a meteor, a nova, portals, a shield")
    void counts() {
        MobSkill slam = MobSkills.preset("slam").skill();
        assertEquals(List.of(80, 37), counts(MobStyle.SLAM, slam, false), "forty plates of circle, fill and outline");
        assertEquals(List.of(40, 18), counts(MobStyle.SLAM, slam, true));
        MobSkill meteor = MobSkills.preset("meteor").skill();
        assertEquals(List.of(86, 11), counts(MobStyle.METEOR, meteor, false), "circle, cross, rock, trail; debris, splat");
        assertEquals(List.of(44, 6), counts(MobStyle.METEOR, meteor, true));
        MobSkill nova = MobSkills.preset("nova").skill();
        assertEquals(22, counts(MobStyle.NOVA, nova, false).getLast(), "sixteen spikes and a ring round the frozen");
        assertEquals(12, counts(MobStyle.NOVA, nova, true).getLast());
        MobSkill portal = MobSkills.preset("portal").skill();
        assertEquals(90, counts(MobStyle.PORTAL, portal, false).getFirst(), "five portals of eighteen");
        assertEquals(60, counts(MobStyle.PORTAL, portal, true).getFirst());

        Stage shield = stage(MobSkills.preset("bubble").skill(), false);
        Vfx ward = shield.vfx(origin);
        MobShows.ward(shield, ward, 0, null, origin, 5_000);
        assertEquals(58, ward.displays(), "three stacked rings, turning and then breaking");
    }

    private List<Integer> counts(MobStyle style, MobSkill skill, boolean lod) {
        Stage stage = stage(skill, lod);
        Vfx windup = stage.vfx(origin);
        style.windup(stage, windup);
        Vfx impact = stage.vfx(origin);
        style.impact(stage, impact, origin);
        return List.of(windup.displays(), impact.displays());
    }

    @Test
    @DisplayName("every sound a style names is a real sound")
    void soundsExist() {
        for (MobStyle style : MobStyle.values()) build(style, false);
        assertFalse(sounds.isEmpty());
        for (String name : sounds) {
            assertDoesNotThrow(() -> Sound.class.getField(name), name + " is no sound");
        }
    }

    @Test
    @DisplayName("each type draws in the style that suits it, and a skill with its own lines keeps them")
    void autoStyles() {
        for (MobSkill.Type type : MobSkill.Type.values()) {
            MobSkill skill = MobSkill.of(type, MobSkill.Trigger.INTERVAL);
            String style = MobSkills.autoStyle(skill);
            boolean silent = type == MobSkill.Type.EFFECT || type == MobSkill.Type.COMMAND;
            assertEquals(silent, style.equals(MobSkills.NO_STYLE), type.name());
            if (!silent) assertNotNull(MobStyle.of(style), type + " draws " + style);
        }
        MobSkill potion = MobSkill.of(MobSkill.Type.POTION, MobSkill.Trigger.INTERVAL);
        assertEquals("puff", MobSkills.autoStyle(potion.withText("SPEED|1|5")));
        assertEquals("puff", MobSkills.autoStyle(potion.withText("SLOWNESS|2|3")), "slowness on the target alone");
        assertEquals("nova", MobSkills.autoStyle(new MobSkill(MobSkill.Trigger.INTERVAL, MobSkill.Type.POTION, 1,
                Duration.ZERO, 0, 5, 0, Duration.ZERO, "SLOWNESS|2|3")));
        MobSkill zone = MobSkill.of(MobSkill.Type.ZONE, MobSkill.Trigger.INTERVAL);
        assertEquals("miasma", MobSkills.autoStyle(zone));
        assertEquals("blades", MobSkills.autoStyle(zone.withCast(MobSkill.Cast.NONE.withAim(MobSkill.Aim.SELF))));

        MobSkill jump = MobSkill.of(MobSkill.Type.JUMP, MobSkill.Trigger.INTERVAL);
        assertEquals("hop", MobSkills.styleOf(jump));
        assertEquals(MobSkills.NO_STYLE, MobSkills.styleOf(jump.withEffect("[SOUND] ENTITY_LLAMA_SPIT;1;1")),
                "an older skill with its own look keeps exactly that");
        assertEquals("slam", MobSkills.styleOf(jump.withEffect("[SOUND] X;1;1")
                .withCast(MobSkill.Cast.NONE.withStyle("SLAM"))), "a chosen style plays beside the lines");
        assertEquals(MobSkills.NO_STYLE, MobSkills.styleOf(jump.withCast(MobSkill.Cast.NONE.withStyle("None"))));
        assertEquals("hop", MobSkills.styleOf(jump.withCast(MobSkill.Cast.NONE.withStyle("sparkles"))),
                "an id this version does not draw plays as AUTO");
    }

    @Test
    @DisplayName("a tint stands in for its style's main colour only, and a theme value nobody can read falls back")
    void colours() {
        MobSkill slam = MobSkills.preset("slam").skill();
        Stage plain = stage(slam, false);
        Stage tinted = stage(slam.withCast(slam.cast().withTint("#123456")), false);
        assertEquals(0x123456, tinted.main());
        assertEquals(plain.colour(MobTheme.Role.FIRE), tinted.colour(MobTheme.Role.FIRE));
        assertEquals(0xFF7A1A, plain.colour(MobTheme.Role.FIRE));
        assertEquals(0x9BE7FF, Shapes.colour("<#9be7ff>"));
        assertEquals(-1, Shapes.colour("blue-ish"));
        Stage broken = new Stage(plugin, slam, MobStyle.SLAM, new MobAim.Lock(origin, 0f, target), null, null,
                List.of(), false, 0.6, 1.9, MobBodies.FALLBACK, MobBodies.block(Material.STONE),
                MobTheme.DEFAULT.withFire("not a colour"), MobVisuals.DEFAULT, true);
        assertEquals(0xFF7A1A, broken.colour(MobTheme.Role.FIRE));
    }

    /** A living body that only knows where it stands. */
    private static LivingEntity body(Location at) {
        UUID id = UUID.randomUUID();
        return (LivingEntity) Proxy.newProxyInstance(LivingEntity.class.getClassLoader(),
                new Class<?>[]{LivingEntity.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getLocation" -> at.clone();
                    case "getWorld" -> at.getWorld();
                    case "getUniqueId" -> id;
                    case "getWidth" -> 0.6;
                    case "getHeight" -> 1.8;
                    case "isValid" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }
}
