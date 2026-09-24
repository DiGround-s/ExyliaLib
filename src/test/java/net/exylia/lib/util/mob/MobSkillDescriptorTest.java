package net.exylia.lib.util.mob;

import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.internal.InputRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skill form showed a 100% chance as {@code 1E+2}; this pins the round trip.
 */
class MobSkillDescriptorTest {

    private static FormValues values(Map<String, Object> answers) throws ReflectiveOperationException {
        Constructor<FormValues> constructor = FormValues.class.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(answers);
    }

    @Test
    @DisplayName("a percent is shown plain, never in scientific notation")
    void plainDecimals() {
        assertEquals("100", MobSkillDescriptor.decimal(100).toString(), "plain even through toString");
        assertEquals("100", InputRuntime.display(MobSkillDescriptor.decimal(100)));
        assertEquals("0.5", InputRuntime.display(MobSkillDescriptor.decimal(0.5)));
        assertEquals("33.33", InputRuntime.display(MobSkillDescriptor.decimal(33.333)));
    }

    @Test
    @DisplayName("saving the form untouched keeps a 100% chance at 1.0, and typed percents stay in range")
    void editRoundTrip() throws ReflectiveOperationException {
        MobSkill skill = MobSkill.of(MobSkill.Type.JUMP, MobSkill.Trigger.DAMAGED);
        for (int save = 0; save < 3; save++) {
            String shown = InputRuntime.display(MobSkillDescriptor.decimal(skill.chance() * 100));
            BigDecimal typed = InputParser.decimal().parse(shown).value();
            skill = MobSkillDescriptor.rebuild(skill, values(Map.of("chance", typed)));
        }
        assertEquals(1.0, skill.chance());

        MobSkill wild = MobSkillDescriptor.rebuild(skill,
                values(Map.of("chance", new BigDecimal("1E+8"), "threshold", new BigDecimal("-5"))));
        assertEquals(1.0, wild.chance());
        assertEquals(0.0, wild.threshold());
    }

    @Test
    @DisplayName("NONE clears the effect lines, which a blank box cannot")
    void effectCleared() throws ReflectiveOperationException {
        MobSkill skill = MobSkill.of(MobSkill.Type.JUMP, MobSkill.Trigger.DAMAGED).withEffect("[SOUND] X;1;1");

        assertEquals("[SOUND] X;1;1", MobSkillDescriptor.rebuild(skill, values(Map.of("effect", "[SOUND] X;1;1"))).effect());
        assertEquals("", MobSkillDescriptor.rebuild(skill, values(Map.of("effect", "none"))).effect());
    }

    @Test
    @DisplayName("the timing form reads back into the cast, NONE clears text, and an unreadable aim keeps the old one")
    void timingRoundTrip() throws ReflectiveOperationException {
        MobSkill skill = MobSkill.of(MobSkill.Type.PUSH, MobSkill.Trigger.INTERVAL)
                .withCast(MobSkill.Cast.NONE.withAim(MobSkill.Aim.CONE).withThen("slam").withGroup("tricks"));

        MobSkill edited = MobSkillDescriptor.timing(skill, values(Map.of("windup", java.time.Duration.ofMillis(800),
                "aim", "line", "spread", new BigDecimal("2"), "group", "tricks", "then", "none", "name", "sweep")));

        assertEquals(MobSkill.Aim.LINE, edited.cast().aim());
        assertEquals(java.time.Duration.ofMillis(800), edited.cast().windup());
        assertEquals(2.0, edited.cast().spread());
        assertEquals("", edited.cast().then());
        assertEquals("sweep", edited.cast().name());
        assertEquals("tricks", edited.cast().group());
        assertEquals(MobSkill.Aim.LINE, MobSkillDescriptor.timing(edited, values(Map.of("aim", "sideways"))).cast().aim());
        assertEquals(MobSkill.Cast.NONE, MobSkillDescriptor.timing(MobSkill.of(MobSkill.Type.PUSH, MobSkill.Trigger.INTERVAL),
                values(Map.of("aim", "AUTO"))).cast(), "an untouched form keeps the skill a plain one");
    }

    @Test
    @DisplayName("the conditions form reads percents back as shares and leaves the rest of the cast alone")
    void conditionsRoundTrip() throws ReflectiveOperationException {
        MobSkill skill = MobSkill.of(MobSkill.Type.PUSH, MobSkill.Trigger.INTERVAL)
                .withCast(MobSkill.Cast.NONE.withName("shove"));

        MobSkill edited = MobSkillDescriptor.conditions(skill, values(Map.of("min_health", new BigDecimal("0"),
                "max_health", new BigDecimal("50"), "nearby", new BigDecimal("16"), "phase", 2L)));

        assertEquals(new MobSkill.Gate(0, 0.5, 0, 0, 16, 2), edited.cast().when());
        assertEquals("shove", edited.cast().name());
    }

    @Test
    @DisplayName("a grouped skill shows its weight as a share of its group")
    void groupedLore() {
        MobSkill.Cast tricks = MobSkill.Cast.NONE.withGroup("tricks");
        MobSkill one = MobSkill.of(MobSkill.Type.JUMP, MobSkill.Trigger.INTERVAL).withChance(0.25).withCast(tricks);
        MobSkill other = MobSkill.of(MobSkill.Type.PUSH, MobSkill.Trigger.INTERVAL).withChance(0.75).withCast(tricks);

        java.util.List<String> lore = new MobSkillDescriptor(proxyPlugin())
                .lore(one, java.util.List.of(one, other));

        assertTrue(lore.stream().anyMatch(line -> line.contains("Takes turns in tricks")), lore.toString());
        assertTrue(lore.stream().anyMatch(line -> line.contains("Weight") && line.contains("25%")), lore.toString());
    }

    private static org.bukkit.plugin.Plugin proxyPlugin() {
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                MobSkillDescriptorTest.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> null);
    }
}
