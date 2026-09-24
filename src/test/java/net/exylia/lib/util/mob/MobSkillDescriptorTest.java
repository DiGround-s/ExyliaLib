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
}
