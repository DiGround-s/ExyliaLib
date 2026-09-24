package net.exylia.lib.util.mob;

import net.exylia.lib.util.Effects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobCodecTest {

    private final List<String> problems = new ArrayList<>();

    private void problem(String where, String problem) {
        problems.add(where + ": " + problem);
    }

    @Test
    @DisplayName("every skill type round-trips with every field it can hold")
    void skillsRoundTrip() {
        List<MobSkill> skills = new ArrayList<>();
        for (MobSkill.Type type : MobSkill.Type.values()) {
            for (MobSkill.Trigger trigger : MobSkill.Trigger.values()) {
                skills.add(MobSkill.of(type, trigger));
            }
        }
        skills.add(new MobSkill(MobSkill.Trigger.LOW_HEALTH, MobSkill.Type.COMMAND, 0.25,
                Duration.ofMillis(1500), 0.4, 2.5, 3, Duration.ofSeconds(7), "say %player% \"hi\"\nline two"));

        List<MobSkill> read = MobCodec.decodeSkills(MobCodec.encodeSkills(skills), this::problem);

        assertEquals(skills, read);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("defaults are not written, and times are seconds")
    void skillsCompact() {
        String stored = MobCodec.encodeSkills(List.of(
                MobSkill.of(MobSkill.Type.TELEPORT, MobSkill.Trigger.ATTACK).withCooldown(Duration.ofMillis(2500))));

        assertEquals("[{\"trigger\":\"ATTACK\",\"type\":\"TELEPORT\",\"cooldown\":2.5}]", stored);
    }

    @Test
    @DisplayName("an unknown skill costs itself, not the list")
    void unknownSkillSkipped() {
        List<MobSkill> read = MobCodec.decodeSkills("""
                [{"trigger":"ATTACK","type":"BLACK_HOLE"},
                 {"trigger":"SOMEDAY","type":"LEAP"},
                 "nonsense",
                 {"trigger":"interval","type":"heal","amount":30}]""", this::problem);

        assertEquals(1, read.size());
        assertEquals(MobSkill.Type.HEAL, read.get(0).type());
        assertEquals(30, read.get(0).amount());
        assertEquals(1.0, read.get(0).chance(), "a missing chance always fires");
        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    @DisplayName("out-of-range numbers are clamped, not trusted")
    void skillClamped() {
        MobSkill skill = MobCodec.decodeSkills(
                "[{\"trigger\":\"DAMAGED\",\"type\":\"PUSH\",\"chance\":7,\"radius\":-3,\"cooldown\":-1}]").get(0);

        assertEquals(1, skill.chance());
        assertEquals(0, skill.radius());
        assertEquals(Duration.ZERO, skill.cooldown());
    }

    @Test
    @DisplayName("empty parts are stored as null, and null reads as empty")
    void emptyIsNull() {
        assertNull(MobCodec.encodeSkills(List.of()));
        assertNull(MobCodec.encodeAttributes(Map.of()));
        assertNull(MobCodec.encodeFlags(Set.of()));
        assertNull(MobCodec.encodeEffects(List.of()));
        assertTrue(MobCodec.decodeSkills(null).isEmpty());
        assertTrue(MobCodec.decodeAttributes(null).isEmpty());
        assertTrue(MobCodec.decodeFlags(null).isEmpty());
        assertTrue(MobCodec.decodeEffects(null).isEmpty());
    }

    @Test
    @DisplayName("attributes round-trip sorted, and old key spellings read as the new one")
    void attributes() {
        Map<String, Double> attributes = Map.of("max_health", 80.0, "attack_damage", 7.5, "scale", 1.4);

        String stored = MobCodec.encodeAttributes(attributes);

        assertEquals("{\"attack_damage\":7.5,\"max_health\":80.0,\"scale\":1.4}", stored);
        assertEquals(attributes, MobCodec.decodeAttributes(stored));
        assertEquals(Map.of("max_health", 20.0, "movement_speed", 0.3),
                MobCodec.decodeAttributes("{\"generic.max_health\":20,\"minecraft:movement_speed\":0.3,"
                        + "\"armor\":\"lots\"}", this::problem));
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    @DisplayName("flags round-trip in declaration order, unknown ones reported")
    void flags() {
        Set<MobFlag> flags = EnumSet.of(MobFlag.NO_SUN_BURN, MobFlag.GLOWING);

        assertEquals("[\"GLOWING\",\"NO_SUN_BURN\"]", MobCodec.encodeFlags(flags));
        assertEquals(flags, MobCodec.decodeFlags("[\"GLOWING\",\"NO_SUN_BURN\",\"FLYING\"]", this::problem));
        assertEquals(1, problems.size());
    }

    @Test
    @DisplayName("potion effects store as the lines a config already writes")
    void effects() {
        List<Effects.ParsedEffect> effects = List.of(Effects.parse("SPEED|2|infinite"),
                Effects.parse("SLOWNESS|1|5|false|false"));

        String stored = MobCodec.encodeEffects(effects);

        assertEquals("[\"SPEED|2|infinite\",\"SLOWNESS|1|5|false|false\"]", stored);
        assertEquals(effects, MobCodec.decodeEffects(stored));
        assertEquals(1, MobCodec.decodeEffects("[\"SPEED|2\",\"minecraft:speed\"]", this::problem).size());
        assertEquals(1, problems.size());
    }

    @Test
    @DisplayName("a column that is not JSON is reported and reads as nothing")
    void malformed() {
        assertTrue(MobCodec.decodeSkills("{oops", this::problem).isEmpty());
        assertTrue(MobCodec.decodeFlags("{\"a\":1}", this::problem).isEmpty());
        assertEquals(2, problems.size());
    }
}
