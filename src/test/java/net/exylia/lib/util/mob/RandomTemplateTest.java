package net.exylia.lib.util.mob;

import org.bukkit.Material;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomTemplateTest {

    /**
     * An item with nothing behind it: {@code new ItemStack(...)} needs a real
     * server, and the material and enchantment are all there is to check.
     */
    private static final class Piece extends ItemStack {
        final Material material;
        final String enchantment;

        Piece(Material material, String enchantment) {
            this.material = material;
            this.enchantment = enchantment;
        }

        @Override
        public @NotNull Material getType() {
            return material;
        }
    }

    private static MobTemplate roll(long seed) {
        return RandomTemplate.roll("random_" + seed, new SplittableRandom(seed),
                (material, enchantment, level) -> new Piece(material, enchantment));
    }

    /** Read straight from the list: {@code Loadout.at} asks the registry whether it is air. */
    private static ItemStack slot(MobTemplate template, int index) {
        return index < template.equipment().size() ? template.equipment().get(index) : null;
    }

    @Test
    @DisplayName("five hundred seeds all give a valid template that stores and reads back")
    void validTemplates() {
        List<String> slots = List.of("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS");
        for (long seed = 0; seed < 500; seed++) {
            MobTemplate template = roll(seed);
            String where = "seed " + seed + " " + template.type();
            RandomTemplate.Kind kind = RandomTemplate.kindOf(template.type());
            assertNotNull(kind, where);
            assertTrue(template.type().isSpawnable(), where);
            assertTrue(LivingEntity.class.isAssignableFrom(template.type().getEntityClass()), where);

            assertTrue(template.name().startsWith("{primary}&l"), where);
            assertTrue(template.name().endsWith("&8[{success}%health%&8/{info}%max_health%&8]"), where);
            assertFalse(template.name().contains("#"), where);

            for (int slot = 0; slot < 4; slot++) {
                ItemStack item = slot(template, slot);
                if (item == null) continue;
                assertTrue(kind.armour(), where);
                assertTrue(item.getType().name().endsWith(slots.get(slot)), where + " " + item.getType());
            }
            ItemStack hand = slot(template, MobTemplate.MAIN_HAND);
            assertEquals(kind.weapon() != null, hand != null, where);
            if (template.type() == EntityType.SKELETON || template.type() == EntityType.STRAY) {
                assertEquals(Material.BOW, hand.getType(), where);
            }
            if (template.type() == EntityType.PILLAGER) assertEquals(Material.CROSSBOW, hand.getType(), where);
            if (hand instanceof Piece piece && piece.enchantment != null) {
                assertTrue(List.of("sharpness", "power").contains(piece.enchantment), where);
            }

            assertTrue(MobTemplate.ATTRIBUTES.containsAll(template.attributes().keySet()), where);
            double health = template.attributes().get("max_health");
            assertTrue(health >= 40 && health <= 500, where);
            double damage = template.attributes().get("attack_damage");
            assertTrue(damage >= 4 && damage <= 14, where);
            Double scale = template.attributes().get("scale");
            if (scale != null) assertTrue(scale >= 0.85 && scale <= 1.6, where);

            assertTrue(RandomTemplate.allowedFlags(kind).containsAll(template.flags()), where + " " + template.flags());
            if (template.has(MobFlag.BABY)) {
                assertTrue(Ageable.class.isAssignableFrom(template.type().getEntityClass()), where);
            }
            if (template.type() == EntityType.WOLF || template.type() == EntityType.IRON_GOLEM) {
                assertTrue(template.has(MobFlag.AGGRESSIVE), where);
            }
            assertTrue(template.effects().size() <= 3, where);
            template.effects().forEach(effect -> assertNotNull(effect, where));

            List<MobSkill> moves = template.skills().stream()
                    .filter(skill -> skill.cast().group().equals("moves")).toList();
            assertTrue(moves.size() >= 2 && moves.size() <= 5, where + " " + moves.size() + " moves");
            Set<String> names = new HashSet<>();
            for (MobSkill move : moves) {
                assertEquals(MobSkill.Trigger.INTERVAL, move.trigger(), where);
                assertFalse(move.cast().windup().isZero() && move.type() != MobSkill.Type.TELEPORT, where);
                assertTrue(names.add(move.cast().name()), where + " one of each move");
            }
            for (MobSkill move : moves) {
                if (!move.cast().then().isEmpty()) assertTrue(names.contains(move.cast().then()), where);
            }
            int phases = template.fight().phases().size();
            assertTrue(phases >= 1 && phases <= 2, where);
            assertTrue(template.fight().groups().containsKey("moves"), where);
            assertFalse(template.fight().globalCooldown().isZero(), where);
            List<MobSkill> tricks = template.skills().stream()
                    .filter(skill -> skill.trigger() == MobSkill.Trigger.PHASE).toList();
            assertEquals(phases, tricks.size(), where + " one trick per phase");
            for (int phase = 2; phase <= phases + 1; phase++) {
                int wanted = phase;
                assertTrue(tricks.stream().anyMatch(trick -> trick.cast().when().phase() == wanted), where);
            }
            for (MobSkill skill : template.skills()) {
                assertFalse(skill.type() == MobSkill.Type.COMMAND, where);
                if (skill.type() == MobSkill.Type.SUMMON) {
                    assertEquals(template.id(), skill.text(), where);
                    assertTrue(health <= 220, where + " a boss never summons itself");
                }
            }

            assertTrue(template.exp() >= 15 && template.exp() <= 400, where);
            assertTrue(template.money() >= 15 && template.money() <= 800, where);
            assertTrue(template.rewards().isEmpty(), where);
            assertEquals(MobBehaviour.NONE, template.behaviour(), where + " never hits mode");
            assertEquals(template.look().glow().isEmpty(), template.look().spawn().isEmpty(), where);

            List<String> problems = new ArrayList<>();
            assertEquals(template.skills(), MobCodec.decodeSkills(MobCodec.encodeSkills(template.skills()),
                    (at, problem) -> problems.add(at + ": " + problem)), where);
            assertEquals(template.attributes(), MobCodec.decodeAttributes(MobCodec.encodeAttributes(template.attributes())), where);
            assertEquals(template.flags(), MobCodec.decodeFlags(MobCodec.encodeFlags(template.flags())), where);
            assertEquals(template.effects(), MobCodec.decodeEffects(MobCodec.encodeEffects(template.effects()),
                    (at, problem) -> problems.add(at + ": " + problem)), where);
            assertEquals(template.fight(), MobCodec.decodeFight(MobCodec.encodeFight(template.fight()),
                    (at, problem) -> problems.add(at + ": " + problem)), where);
            assertEquals(template.look(), MobCodec.decodeLook(MobCodec.encodeLook(template.look()),
                    (at, problem) -> problems.add(at + ": " + problem)), where);
            assertTrue(problems.isEmpty(), where + " " + problems);
        }
    }

    @Test
    @DisplayName("every rank turns up, and bosses are rare")
    void ranks() {
        int bosses = 0;
        int elites = 0;
        for (long seed = 0; seed < 500; seed++) {
            MobTemplate template = roll(seed);
            if (template.fight().phases().size() == 2) bosses++;
            if (template.look().equals(MobLook.NONE)) elites++;
        }
        assertTrue(bosses > 20 && bosses < 90, bosses + " bosses");
        assertTrue(elites > 220 && elites < 380, elites + " elites");
    }

    @Test
    @DisplayName("the same seed gives the same mob")
    void deterministic() {
        for (long seed = 0; seed < 50; seed++) {
            MobTemplate first = roll(seed);
            MobTemplate second = roll(seed);
            assertEquals(first.type(), second.type());
            assertEquals(first.name(), second.name());
            assertEquals(first.attributes(), second.attributes());
            assertEquals(first.flags(), second.flags());
            assertEquals(first.effects(), second.effects());
            assertEquals(first.skills(), second.skills());
            assertEquals(first.fight(), second.fight());
            assertEquals(first.look(), second.look());
            assertEquals(first.exp(), second.exp());
            assertEquals(first.money(), second.money());
            for (int index : List.of(0, 1, 2, 3, MobTemplate.MAIN_HAND)) {
                ItemStack a = slot(first, index);
                ItemStack b = slot(second, index);
                if (a == null) assertNull(b);
                else assertEquals(a.getType(), b.getType());
            }
        }
    }
}
