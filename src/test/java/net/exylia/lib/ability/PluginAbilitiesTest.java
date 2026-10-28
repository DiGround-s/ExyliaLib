package net.exylia.lib.ability;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.ability.internal.ChainGuard;
import net.exylia.lib.util.CooldownScope;
import net.exylia.lib.util.Cooldowns;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one attempt does and in which order, driven through {@link PluginAbilities#fire}
 * with recording effects, conditions, gate and dice: no event, no world.
 */
class PluginAbilitiesTest {

    private static final ContextKey<Boolean> PASS = ContextKey.of("pass", Boolean.class);

    private final List<String> log = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private PluginAbilities abilities;
    private Player holder;
    private double roll;
    private List<Held> holdings = List.of();
    private int nested;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Cooldowns.clearEverything();
        Plugin plugin = FakeServer.newPlugin("AbilityTest");
        abilities = PluginAbilities.detached(plugin);
        abilities.effect("record", args -> {
            String tag = args.string("tag", "effect");
            return (ctx, report) -> {
                log.add(tag + "@" + ctx.scale());
                report.damage(2).target();
            };
        });
        abilities.effect("refire", args -> (ctx, report) -> {
            nested++;
            ctx.abilities().fire(ctx.holder(), Triggers.ATTACK, null);
        });
        abilities.condition("flag", args -> ctx -> {
            log.add("condition");
            return ctx.getOr(PASS, true);
        });
        abilities.dice(() -> {
            log.add("chance");
            return roll;
        });
        abilities.holdings(player -> holdings);
        holder = new FakePlayer("Steve").player();
    }

    @AfterEach
    void tearDown() {
        Cooldowns.clearEverything();
        FakeServer.reset();
    }

    private Ability compile(String id, Map<String, Object> source) {
        Ability ability = abilities.compile(id, source, problems::add);
        assertNotNull(ability, "should compile: " + problems);
        return ability;
    }

    private Ability attack(String id, Object chance, String cooldown) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("trigger", "ATTACK");
        source.put("chance", chance);
        if (cooldown != null) source.put("cooldown", cooldown);
        source.put("conditions", List.of(Map.of("flag", "")));
        source.put("effects", List.of("record"));
        return compile(id, source);
    }

    private void hold(Ability... held) {
        List<Held> list = new ArrayList<>();
        for (Ability ability : held) list.add(new Held(ability, 1, null));
        holdings = list;
        abilities.refresh(holder);
    }

    @Test
    @DisplayName("gate, then conditions, then cooldown, then the roll, then the effects")
    void order() {
        hold(attack("order", 50, "8s"));
        abilities.gate((ctx, ability) -> {
            log.add("gate");
            return 1;
        });
        roll = 0.1;

        assertEquals(1, abilities.fire(holder, Triggers.ATTACK, null));
        assertEquals(List.of("gate", "condition", "chance", "effect@1.0"), log);

        log.clear();
        assertEquals(0, abilities.fire(holder, Triggers.ATTACK, null));
        assertEquals(List.of("gate", "condition"), log, "on cooldown: no roll, no effect");
    }

    @Test
    @DisplayName("a gate of 0 stops before the conditions; any other factor scales the effects")
    void gate() {
        hold(attack("gated", 100, null));
        abilities.gate((ctx, ability) -> 0);
        assertEquals(0, abilities.fire(holder, Triggers.ATTACK, null));
        assertTrue(log.isEmpty(), "nothing after a denying gate: " + log);

        abilities.gate((ctx, ability) -> 0.75);
        assertEquals(1, abilities.fire(holder, Triggers.ATTACK, null));
        assertEquals(List.of("condition", "effect@0.75"), log);
    }

    @Test
    @DisplayName("the cooldown starts only when the roll succeeds")
    void cooldownStartsOnProc() {
        Ability ability = hold1(attack("cooling", 50, "8s"));
        CooldownScope scope = CooldownScope.player(holder.getUniqueId());

        roll = 0.99;
        assertEquals(0, abilities.fire(holder, Triggers.ATTACK, null));
        assertFalse(Cooldowns.isActive(scope, ability.cooldownKey()), "a miss never locks the ability");

        roll = 0.0;
        assertEquals(1, abilities.fire(holder, Triggers.ATTACK, null));
        assertTrue(Cooldowns.isActive(scope, ability.cooldownKey()));
    }

    @Test
    @DisplayName("a failed condition neither rolls nor starts the cooldown")
    void conditionsBeforeRoll() {
        Ability ability = hold1(attack("conditional", 50, "8s"));
        assertEquals(0, abilities.fire(holder, Triggers.ATTACK, ctx -> ctx.with(PASS, false)));
        assertEquals(List.of("condition"), log);
        assertFalse(Cooldowns.isActive(CooldownScope.player(holder.getUniqueId()), ability.cooldownKey()));
    }

    @Test
    @DisplayName("a chance of 100 always procs and 0 never does, and neither rolls")
    void certainChances() {
        hold(attack("always", 100, null), attack("never", 0, null));
        for (int i = 0; i < 20; i++) {
            assertEquals(1, abilities.fire(holder, Triggers.ATTACK, null));
        }
        assertFalse(log.contains("chance"), "no dice for a certain answer");
    }

    @Test
    @DisplayName("chance and cooldown are formulas of level")
    void formulas() {
        Ability ability = attack("formula", "6 + level * 4", "10 - level");
        assertEquals(10, ability.chance(1));
        assertEquals(18, ability.chance(3));
        assertEquals(7, ability.cooldown(3).toSeconds());
        assertEquals(100, attack("percent", "150%", null).chance(1), "clamped to 100");
        assertEquals(15, attack("sign", "15%", null).chance(1), "a trailing % is read");
    }

    @Test
    @DisplayName("an unreadable formula is reported and falls back to the key's default")
    void formulaFallback() {
        Ability broken = attack("broken", "6 + banana", "8 parsecs");
        assertEquals(100, broken.chance(1));
        assertTrue(broken.cooldown(1).isZero());
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("'chance'")), problems.toString());
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("'cooldown'")), problems.toString());

        Ability noAnswer = attack("division", "10 / (level - 1)", null);
        assertEquals(100, noAnswer.chance(1), "arithmetic with no answer at this level falls back too");
        assertEquals(10, noAnswer.chance(2));
    }

    @Test
    @DisplayName("an ability's own damage never fires ATTACK again unless it chains, and chains stop")
    void chainGuard() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("trigger", "ATTACK");
        source.put("effects", List.of("refire"));
        hold(compile("loner", source));
        abilities.fire(holder, Triggers.ATTACK, null);
        assertEquals(1, nested);

        nested = 0;
        source.put("chain", true);
        hold(compile("chainer", source));
        abilities.fire(holder, Triggers.ATTACK, null);
        assertEquals(ChainGuard.MAX_DEPTH, nested, "chain: true nests, up to the cap");
        assertFalse(ChainGuard.blocksDamageTriggers(), "the guard is released afterwards");
    }

    @Test
    @DisplayName("unknown keys, effects and conditions are reported once and kept in the source")
    void unknownKeysKept() {
        Map<String, Object> damage = new LinkedHashMap<>();
        damage.put("target", "victim");
        damage.put("amout", 3);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("trigger", "ATTACK");
        source.put("colour", "red");
        source.put("conditions", List.of(Map.of("moon-phase", "full")));
        source.put("effects", List.of(Map.of("damage", damage), Map.of("explode-everything", true), "record"));

        Ability ability = compile("unknowns", source);

        assertEquals(2, ability.effects().size(), "the unknown effect is skipped, the rest compile");
        assertEquals(1, problems.stream().filter(problem -> problem.contains("'colour'")).count(), problems.toString());
        assertEquals(1, problems.stream().filter(problem -> problem.contains("'amout'")).count(), problems.toString());
        assertEquals(1, problems.stream().filter(problem -> problem.contains("'explode-everything'")).count());
        assertEquals(1, problems.stream().filter(problem -> problem.contains("'moon-phase'")).count());

        Map<String, Object> kept = ability.source();
        assertEquals("red", kept.get("colour"));
        assertTrue(kept.get("effects").toString().contains("amout"));
        assertTrue(kept.get("effects").toString().contains("explode-everything"));
    }

    @Test
    @DisplayName("an ability with no trigger or an unknown one does not compile")
    void unknownTrigger() {
        assertNull(abilities.compile("none", Map.of("effects", List.of("record")), problems::add));
        assertNull(abilities.compile("odd", Map.of("trigger", "MINE_DROP"), problems::add));
        assertEquals(2, problems.size(), problems.toString());

        abilities.trigger("mine_drop");
        assertNotNull(abilities.compile("custom", Map.of("trigger", "MINE_DROP"), problems::add));
    }

    @Test
    @DisplayName("the spec's example compiles with no problems")
    void specExample() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("trigger", "ATTACK");
        source.put("chance", "6 + level * 4");
        source.put("cooldown", "8s");
        source.put("conditions", List.of(Map.of("target", "player"), Map.of("not", "blocking"),
                Map.of("placeholder", "%player_level% >= 10")));
        source.put("effects", List.of(
                Map.of("damage", Map.of("target", "victim", "amount", "0.5 * level", "every", "0.5s", "times", 4)),
                Map.of("sequence", "bleed"),
                Map.of("knockback", Map.of("strength", "0.4 + level * 0.1")),
                Map.of("heal", Map.of("amount", 2, "mode", "percent")),
                Map.of("break-area", Map.of("shape", "vein", "max", "16 * level")),
                Map.of("cooldown-reset", "all")));
        Ability ability = compile("bleed", source);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(3, ability.conditions().size());
        assertEquals(6, ability.effects().size());
    }

    @Test
    @DisplayName("holdings are cached until refreshed, and refreshing fires EQUIP and UNEQUIP")
    void holdingsCache() {
        Ability ability = attack("cached", 100, null);
        Map<String, Object> equip = new LinkedHashMap<>();
        equip.put("trigger", "EQUIP");
        equip.put("effects", List.of(Map.of("record", Map.of("tag", "on"))));
        Map<String, Object> unequip = new LinkedHashMap<>();
        unequip.put("trigger", "UNEQUIP");
        unequip.put("effects", List.of(Map.of("record", Map.of("tag", "off"))));
        Ability on = compile("on", equip);
        Ability off = compile("off", unequip);

        assertEquals(0, abilities.fire(holder, Triggers.ATTACK, null), "nothing cached yet");

        holdings = List.of(new Held(ability, 1, null), new Held(on, 1, null), new Held(off, 1, null));
        assertEquals(0, abilities.fire(holder, Triggers.ATTACK, null), "not asked again until a refresh");
        abilities.refresh(holder);
        assertTrue(log.contains("on@1.0"), "EQUIP fired for what was added: " + log);
        assertEquals(1, abilities.fire(holder, Triggers.ATTACK, null));

        log.clear();
        abilities.refresh(holder);
        assertFalse(log.contains("on@1.0"), "the same holdings fire nothing again: " + log);

        holdings = List.of();
        assertEquals(1, abilities.fire(holder, Triggers.ATTACK, null), "still the cached answer");
        abilities.refresh(holder);
        assertTrue(log.contains("off@1.0"), "UNEQUIP fired for what was removed: " + log);
        assertEquals(0, abilities.fire(holder, Triggers.ATTACK, null));
        assertEquals(0, abilities.held(holder).size());
    }

    @Test
    @DisplayName("every attempt is reported, with what a proc did")
    void outcomes() {
        hold(attack("observed", 50, null));
        List<AbilityOutcome> seen = new ArrayList<>();
        abilities.onOutcome(seen::add);

        roll = 0.99;
        abilities.fire(holder, Triggers.ATTACK, null);
        roll = 0.0;
        abilities.fire(holder, Triggers.ATTACK, null);

        assertEquals(2, seen.size());
        assertEquals(AbilityOutcome.Result.CHANCE, seen.get(0).result());
        AbilityOutcome proc = seen.get(1);
        assertEquals(AbilityOutcome.Result.PROC, proc.result());
        assertEquals(2.0, proc.damage());
        assertEquals(1, proc.targets());
        assertEquals("observed", proc.ability());
        assertEquals(holder.getUniqueId(), proc.holder());
    }

    private Ability hold1(Ability ability) {
        hold(ability);
        return ability;
    }
}
