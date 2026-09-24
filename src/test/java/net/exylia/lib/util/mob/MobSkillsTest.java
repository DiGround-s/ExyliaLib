package net.exylia.lib.util.mob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobSkillsTest {

    @Test
    @DisplayName("every preset stores and reads back as itself")
    void presetsRoundTrip() {
        List<MobSkill> skills = MobSkills.library().stream().map(MobSkills.Preset::skill).toList();
        List<MobSkill> read = MobCodec.decodeSkills(MobCodec.encodeSkills(skills),
                (where, problem) -> { throw new AssertionError(where + ": " + problem); });
        assertEquals(skills, read);
        assertEquals(MobCodec.encodeSkills(skills), MobCodec.encodeSkills(read), "and writes back byte for byte");
    }

    @Test
    @DisplayName("the library has one preset per id, each with a wind-up, a style of its own and a label")
    void library() {
        Set<String> ids = new HashSet<>();
        for (MobSkills.Preset preset : MobSkills.library()) {
            assertTrue(ids.add(preset.id()), "one " + preset.id());
            assertTrue(MobSkills.STYLES.contains(preset.id()), preset.id());
            assertEquals(preset.id(), preset.skill().cast().style());
            assertFalse(preset.label().isBlank());
            assertFalse(preset.blurb().isBlank());
            assertTrue(preset.blurb().length() <= 48, preset.id() + "'s blurb fits a tooltip");
            if (!preset.id().equals("blink")) {
                assertFalse(preset.skill().cast().windup().isZero(), preset.id() + " gives fair warning");
            }
        }
        assertEquals(16, MobSkills.library().size());
        assertNotNull(MobSkills.preset(" SLAM "));
        assertNull(MobSkills.preset("fireworks"));
    }

    @Test
    @DisplayName("the new types come with numbers they can be cast with")
    void newTypes() {
        MobSkill chain = MobSkill.of(MobSkill.Type.CHAIN, MobSkill.Trigger.INTERVAL);
        assertEquals("4", chain.text());
        assertTrue(chain.needsTarget());
        assertTrue(MobSkill.of(MobSkill.Type.DASH, MobSkill.Trigger.INTERVAL).needsTarget());
        assertFalse(MobSkill.of(MobSkill.Type.ZONE, MobSkill.Trigger.INTERVAL).needsTarget());
        assertFalse(MobSkill.of(MobSkill.Type.SHIELD, MobSkill.Trigger.DAMAGED).needsTarget());
        assertFalse(MobSkill.of(MobSkill.Type.BARRAGE, MobSkill.Trigger.INTERVAL).needsTarget());
        assertEquals(Duration.ofSeconds(5), MobSkill.of(MobSkill.Type.SHIELD, MobSkill.Trigger.DAMAGED).duration());
        assertTrue(MobSkill.of(MobSkill.Type.SHIELD, MobSkill.Trigger.DAMAGED).major());
    }

    @Test
    @DisplayName("a theme keeps what it is given and fills in what is blank")
    void theme() {
        MobTheme theme = new MobTheme(" {accent} ", "", null, "#abcdef", "{primary}", "#ff7a1a", "#9be7ff",
                "#7bc043", "#8fd3ff", "{warning}");
        assertEquals("{accent}", theme.telegraph());
        assertEquals("{error}", theme.danger());
        assertEquals("{success}", theme.heal());
        assertEquals("#abcdef", theme.of(MobTheme.Role.SHIELD));
        assertEquals("#123456", MobTheme.DEFAULT.withFrost("#123456").frost());
    }
}
