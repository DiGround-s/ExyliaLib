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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("a behaviour round-trips, writes only what is not a default, and none is null")
    void behaviourRoundTrip() {
        MobBehaviour behaviour = new MobBehaviour(40, Duration.ofMillis(500), Duration.ofMinutes(5), 12.5);

        assertEquals(behaviour, MobCodec.decodeBehaviour(MobCodec.encodeBehaviour(behaviour), this::problem));
        assertEquals("{\"hits\":40,\"hitCooldown\":0.5,\"lifetime\":300.0,\"roam\":12.5}",
                MobCodec.encodeBehaviour(behaviour));
        assertEquals("{\"roam\":3.0}", MobCodec.encodeBehaviour(MobBehaviour.NONE.withRoam(3)));
        assertEquals("{\"hits\":5}", MobCodec.encodeBehaviour(MobBehaviour.NONE.withHits(5)));
        assertNull(MobCodec.encodeBehaviour(MobBehaviour.NONE));
        assertEquals(MobBehaviour.NONE, MobCodec.decodeBehaviour(null));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("a behaviour it cannot read costs the field, never the template")
    void behaviourTolerant() {
        assertEquals(MobBehaviour.NONE.withHits(5),
                MobCodec.decodeBehaviour("{\"hits\":5,\"roam\":\"far\",\"lifetime\":-3,\"wings\":true}", this::problem));
        assertEquals(MobBehaviour.NONE, MobCodec.decodeBehaviour("[1,2]", this::problem));
        assertEquals(MobBehaviour.NONE, MobCodec.decodeBehaviour("{broken", this::problem));
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    @DisplayName("a look round-trips, blanks are not written, and keywords read in any case")
    void lookRoundTrip() {
        MobLook look = new MobLook("CREAMY", MobLook.CYCLE, "light_purple", "confetti");

        assertEquals(look, MobCodec.decodeLook(MobCodec.encodeLook(look), this::problem));
        assertEquals("{\"body\":\"CYCLE\",\"aura\":\"confetti\"}",
                MobCodec.encodeLook(new MobLook("", "cycle", " ", "confetti")));
        assertNull(MobCodec.encodeLook(MobLook.NONE));
        assertEquals(MobLook.NONE.withGlow(MobLook.RANDOM),
                MobCodec.decodeLook("{\"glow\":\"random\",\"sparkle\":1}", this::problem));
        assertEquals(MobLook.NONE, MobCodec.decodeLook("\"text\"", this::problem));
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    @DisplayName("a skill stored before effects existed still reads, and effects round-trip")
    void skillEffect() {
        List<MobSkill> old = MobCodec.decodeSkills(
                "[{\"trigger\":\"DAMAGED\",\"type\":\"PUSH\",\"radius\":3.0,\"amount\":1.2}]", this::problem);
        assertEquals(1, old.size());
        assertEquals("", old.get(0).effect());

        MobSkill blink = new MobSkill(MobSkill.Trigger.INTERVAL, MobSkill.Type.TELEPORT, 0.25, Duration.ofSeconds(4),
                0.3, 6, 0, Duration.ZERO, "", "[SOUND] ENTITY_ENDERMAN_TELEPORT;1;1.2\n[PARTICLE] PORTAL;count:50");
        String stored = MobCodec.encodeSkills(List.of(blink));
        assertTrue(stored.contains("\"effect\":"), stored);
        assertEquals(List.of(blink), MobCodec.decodeSkills(stored, this::problem));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    @DisplayName("a chance saved out of range by the old percent form reads back clamped")
    void chanceClamped() {
        List<MobSkill> read = MobCodec.decodeSkills(
                "[{\"trigger\":\"DAMAGED\",\"type\":\"JUMP\",\"chance\":1000000.0,\"threshold\":-2}]");

        assertEquals(1.0, read.get(0).chance());
        assertEquals(0.0, read.get(0).threshold());
    }
}
