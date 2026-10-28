package net.exylia.lib.ability;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.ability.internal.AreaPlanner;
import net.exylia.lib.ability.internal.PackedBlocks;
import net.exylia.lib.util.Cooldowns;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.387.0 additions: hit shaping (bonus-damage, armour-pierce applied last), drop transforms
 * that run last, projectile stamps, the lazily resolved potion, column shapes and replace tables.
 */
class AbilityShapingTest {

    private final List<String> problems = new ArrayList<>();
    private PluginAbilities abilities;
    private Player holder;
    private List<Held> holdings = List.of();

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Cooldowns.clearEverything();
        Plugin plugin = FakeServer.newPlugin("ShapingTest");
        abilities = PluginAbilities.detached(plugin);
        abilities.holdings(player -> holdings);
        holder = new FakePlayer("Steve").player();
    }

    @AfterEach
    void tearDown() {
        Cooldowns.clearEverything();
        FakeServer.reset();
    }

    private Ability compile(String id, String trigger, Object... effects) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("trigger", trigger);
        source.put("effects", List.of(effects));
        Ability ability = abilities.compile(id, source, problems::add);
        assertNotNull(ability, "should compile: " + problems);
        return ability;
    }

    private void hold(Ability... held) {
        List<Held> list = new ArrayList<>();
        for (Ability ability : held) list.add(new Held(ability, 1, null));
        holdings = list;
        abilities.refresh(holder);
    }

    /** A hit of {@code base} that armour cuts by 40%, recomputed whenever the base changes. */
    @SuppressWarnings({"deprecation", "removal"})
    private EntityDamageByEntityEvent armouredHit(double base) {
        Entity victim = (Entity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Entity.class},
                (self, method, args) -> FakeServer.defaultValue(method.getReturnType()));
        DamageSource source = (DamageSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DamageSource.class}, (self, method, args) -> FakeServer.defaultValue(method.getReturnType()));
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, base);
        modifiers.put(DamageModifier.ARMOR, -base * 0.4);
        Map<DamageModifier, com.google.common.base.Function<? super Double, Double>> functions = new EnumMap<>(DamageModifier.class);
        functions.put(DamageModifier.BASE, value -> 0.0);
        functions.put(DamageModifier.ARMOR, value -> -value * 0.4);
        return new EntityDamageByEntityEvent(holder, victim, DamageCause.ENTITY_ATTACK, source, modifiers, functions,
                false);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static double armour(EntityDamageEvent event) {
        return event.getDamage(DamageModifier.ARMOR);
    }

    // ------------------------------------------------------------------ bonus-damage

    @Test
    @DisplayName("bonus-damage raises the hit itself: percent of it, or flat")
    void bonusDamage() {
        hold(compile("pct", "ATTACK", Map.of("bonus-damage", Map.of("amount", 20))),
                compile("flat", "ATTACK", Map.of("bonus-damage", Map.of("amount", 3, "mode", "flat"))));
        EntityDamageByEntityEvent hit = armouredHit(10);
        abilities.fire(holder, Triggers.ATTACK, ctx -> ctx.event(hit));
        assertEquals(15, hit.getDamage(), 1e-9, "10 + 20% = 12, then + 3");
        assertEquals(List.of(), problems);
    }

    @Test
    @DisplayName("by multiplies the amount by a registered scale's units, max caps it, an unknown one is reported")
    void bonusDamageScales() {
        abilities.scale("stacks", args -> {
            double each = args.number("each", 1);
            return ctx -> 4 * each;
        });
        hold(compile("stacked", "ATTACK", Map.of("bonus-damage",
                Map.of("by", "stacks", "each", 1, "amount", 10, "max", 25))));
        EntityDamageByEntityEvent hit = armouredHit(10);
        abilities.fire(holder, Triggers.ATTACK, ctx -> ctx.event(hit));
        assertEquals(12.5, hit.getDamage(), 1e-9, "4 units * 10% capped at 25%");
        assertEquals(List.of(), problems, "the scale's own keys count as read");

        compile("typo", "ATTACK", Map.of("bonus-damage", Map.of("by", "sprnt")));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("sprnt")), problems.toString());
        assertEquals(0.5, DamageShaping.healthGap(20, 30), 1e-9);
        assertEquals(1, DamageShaping.healthGap(20, 80), 1e-9);
        assertEquals(0, DamageShaping.healthGap(20, 20), 1e-9);
    }

    // ------------------------------------------------------------------ armour-pierce

    @Test
    @DisplayName("armour-pierce applies after every ability changed the hit, whatever order they ran in")
    void pierceLast() {
        // The pierce runs first; the bonus after it recomputes armour, and the pierce must still win.
        hold(compile("pierce", "ATTACK", Map.of("armour-pierce", Map.of("ignore", 100)),
                Map.of("bonus-damage", Map.of("amount", 50))));
        EntityDamageByEntityEvent hit = armouredHit(10);
        abilities.fire(holder, Triggers.ATTACK, ctx -> ctx.event(hit));
        assertEquals(15, hit.getDamage(), 1e-9);
        assertEquals(0, armour(hit), 1e-9);
        assertEquals(15, hit.getFinalDamage(), 1e-9);
    }

    @Test
    @DisplayName("inside the library's damage handler the pierce waits until both sides fired")
    void pierceDeferred() {
        hold(compile("half", "ATTACK", Map.of("armour-pierce", Map.of("ignore", 50))));
        EntityDamageByEntityEvent hit = armouredHit(10);
        DamageShaping shaping = abilities.shaping();
        shaping.deferring(true);
        try {
            abilities.fire(holder, Triggers.ATTACK, ctx -> ctx.event(hit));
        } finally {
            shaping.deferring(false);
        }
        assertEquals(-4, armour(hit), 1e-9, "not yet");
        assertArrayEquals(new double[]{0.5, 0}, shaping.pending(hit), 1e-9);
        shaping.apply(hit);
        assertEquals(-2, armour(hit), 1e-9);
        assertNull(shaping.pending(hit), "applied once, then forgotten");
    }

    // ------------------------------------------------------------------ drops

    @Test
    @DisplayName("transformDropsLast runs after every transformDrops, even one added later")
    void dropsLast() {
        // The list's size stands for the amounts: an ItemStack needs a server.
        abilities.effect("cap", args -> (ctx, report) -> ctx.transformDropsLast(items -> {
            while (items.size() > 2) items.removeLast();
        }));
        abilities.effect("triple", args -> (ctx, report) -> ctx.transformDrops(items -> {
            int size = items.size();
            for (int i = 0; i < size * 2; i++) items.add(null);
        }));
        hold(compile("scarce", "KILL", "cap", "triple"));
        List<ItemStack> drops = new ArrayList<>();
        drops.add(null);
        abilities.fire(holder, Triggers.KILL, ctx -> ctx.with(ContextKey.DROPS, drops));
        assertEquals(2, drops.size(), "tripled to 3 first, then capped at 2");
    }

    // ------------------------------------------------------------------ projectiles

    @Test
    @DisplayName("a stamp carries ids and levels; it resolves to what is compiled now and skips the rest")
    void stamps() {
        Ability flame = compile("flame", Triggers.PROJECTILE_HIT, Map.of("bonus-damage", Map.of("amount", 1)));
        String stamp = PluginAbilities.encodeShot(new Held[]{new Held(flame, 3, null)});
        Map<String, Ability> now = Map.of("flame", flame);
        List<Held> carried = PluginAbilities.decodeShot(stamp + "\ngone\t2\nbroken\tx", now::get);
        assertEquals(List.of(new Held(flame, 3, null)), carried);
        assertEquals(List.of(), PluginAbilities.decodeShot(PluginAbilities.encodeShot(null), now::get));
        assertTrue(!abilities.projectileLevels(), "off by default: the old behaviour");
    }

    @Test
    @DisplayName("firing with carried holdings uses their levels, not what the shooter holds now")
    void carriedLevels() {
        List<Integer> levels = new ArrayList<>();
        abilities.effect("level", args -> (ctx, report) -> levels.add(ctx.level()));
        Ability shot = compile("shot", Triggers.PROJECTILE_HIT, "level");
        holdings = List.of(new Held(shot, 1, null));
        abilities.refresh(holder);
        abilities.fire(holder, Triggers.PROJECTILE_HIT, new Held[]{new Held(shot, 4, null)}, null);
        assertEquals(List.of(4), levels);
    }

    // ------------------------------------------------------------------ registry

    @Test
    @DisplayName("potion compiles without a server and resolves its type on the first proc")
    void potionWithoutRegistry() {
        Ability ability = compile("venom", Triggers.ATTACK,
                Map.of("potion", Map.of("type", "poison", "level", 2, "duration", "3s")));
        assertEquals(List.of(), problems);
        assertEquals(1, ability.effects().size());
        hold(ability);
        abilities.fire(holder, Triggers.ATTACK, null); // no registry: does nothing, throws nothing
    }

    // ------------------------------------------------------------------ area

    @Test
    @DisplayName("a column is a 0-radius plane: the origin plus length - 1 along the look axis")
    void column() {
        AreaShape column = AreaShape.column(8);
        assertEquals(0, column.radius());
        assertEquals(7, column.depth());
        assertTrue(!column.vertical());
        assertTrue(AreaShape.column(3, true).vertical());
        assertEquals(0, AreaShape.column(0).depth(), "a length under 1 is the origin alone");

        PackedBlocks out = new PackedBlocks(16);
        int[] down = AreaBreak.verticalAxis(new Location(null, 0, 0, 0, 0, 10));
        AreaPlanner.plan(column.kind(), column.radius(), column.depth(), column.max(), 0, 64, 0,
                down[0], down[1], down[2], (x, y, z) -> Material.SAND, type -> true, out);
        assertEquals(7, out.size());
        for (int i = 0; i < out.size(); i++) {
            long next = out.get(i);
            assertEquals(0, PackedBlocks.x(next));
            assertEquals(63 - i, PackedBlocks.y(next));
        }
        assertArrayEquals(new int[]{0, 1, 0}, AreaBreak.verticalAxis(new Location(null, 0, 0, 0, 0, -30)));
    }

    @Test
    @DisplayName("strip knows every log, wood, stem and hyphae, and nothing already stripped")
    void stripTable() {
        Map<Material, Material> stripped = AreaBreak.Replacements.stripped();
        assertEquals(Material.STRIPPED_OAK_LOG, stripped.get(Material.OAK_LOG));
        assertEquals(Material.STRIPPED_CRIMSON_HYPHAE, stripped.get(Material.CRIMSON_HYPHAE));
        assertNull(stripped.get(Material.STRIPPED_OAK_LOG));
        assertTrue(AreaBreak.Replacements.TILLABLE.contains(Material.GRASS_BLOCK));
        assertTrue(AreaBreak.Replacements.PATHABLE.contains(Material.PODZOL));
        assertNotNull(AreaBreak.Replace.till());
    }
}
