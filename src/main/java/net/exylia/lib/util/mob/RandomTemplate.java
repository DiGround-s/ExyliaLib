package net.exylia.lib.util.mob;

import net.exylia.lib.util.Effects;
import net.exylia.lib.util.Effects.ParsedEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Rolls the random templates behind {@link MobTemplate#random}: every choice
 * is drawn from a table below, in a fixed order, so one seed is one mob.
 */
final class RandomTemplate {

    /** Builds one equipment item; the seam a test without a server replaces. */
    interface Items {
        @NotNull ItemStack make(@NotNull Material material, @Nullable String enchantment, int level);
    }

    /** What a type can be given, beyond the flags every type takes. */
    record Kind(EntityType type, int weight, boolean armour, @Nullable String weapon,
                        boolean baby, boolean burns, boolean fireproof, boolean neutral, boolean big) { }

    /** {@code weapon}: a material, or {@code "SWORD"} / {@code "AXE"} for one of the rolled tier. */
    static final List<Kind> KINDS = List.of(
            new Kind(EntityType.ZOMBIE, 10, true, "SWORD", true, true, false, false, false),
            new Kind(EntityType.HUSK, 8, true, "AXE", true, false, false, false, false),
            new Kind(EntityType.DROWNED, 6, true, "TRIDENT", true, true, false, false, false),
            new Kind(EntityType.SKELETON, 10, true, "BOW", false, true, false, false, false),
            new Kind(EntityType.STRAY, 6, true, "BOW", false, true, false, false, false),
            new Kind(EntityType.WITHER_SKELETON, 6, true, "SWORD", false, false, true, false, false),
            new Kind(EntityType.PIGLIN_BRUTE, 5, true, "AXE", false, false, false, false, false),
            new Kind(EntityType.VINDICATOR, 6, false, "AXE", false, false, false, false, false),
            new Kind(EntityType.PILLAGER, 6, false, "CROSSBOW", false, false, false, false, false),
            new Kind(EntityType.EVOKER, 3, false, null, false, false, false, false, false),
            new Kind(EntityType.SPIDER, 7, false, null, false, false, false, false, false),
            new Kind(EntityType.CAVE_SPIDER, 5, false, null, false, false, false, false, false),
            new Kind(EntityType.BLAZE, 5, false, null, false, false, true, false, false),
            new Kind(EntityType.ENDERMAN, 5, false, null, false, false, false, true, false),
            new Kind(EntityType.SLIME, 4, false, null, false, false, false, false, false),
            new Kind(EntityType.MAGMA_CUBE, 4, false, null, false, false, true, false, false),
            new Kind(EntityType.WITCH, 5, false, null, false, false, false, false, false),
            new Kind(EntityType.IRON_GOLEM, 4, false, null, false, false, false, true, true),
            new Kind(EntityType.WOLF, 5, false, null, true, false, false, true, false),
            new Kind(EntityType.RAVAGER, 1, false, null, false, false, false, false, true));

    private static final List<String> PREFIXES = List.of("ASTRAL", "VOID", "EMBER", "FROST", "NEBULA",
            "STARBORN", "SHADOW", "CRYSTAL", "STORM", "ECLIPSE", "NOVA", "COMET", "LUNAR", "ABYSSAL");
    private static final List<String> SUFFIXES = List.of("LORD", "WRAITH", "HERALD", "SENTINEL",
            "STALKER", "CHAMPION", "WARDEN");
    private static final String HEALTH = " &8[{success}%health%&8/{info}%max_health%&8]";

    /** Armour prefix and matching weapon prefix, weakest first. */
    private static final String[][] TIERS = {
            {"LEATHER", "WOODEN"}, {"CHAINMAIL", "STONE"}, {"IRON", "IRON"},
            {"GOLDEN", "GOLDEN"}, {"DIAMOND", "DIAMOND"}, {"NETHERITE", "NETHERITE"}};
    private static final String[] PIECES = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};

    private static final List<String> EFFECTS = List.of("SPEED", "STRENGTH", "RESISTANCE",
            "REGENERATION", "FIRE_RESISTANCE", "JUMP_BOOST");
    private static final List<String> CURSES = List.of("SLOWNESS|2|4", "WEAKNESS|1|5", "POISON|1|4",
            "BLINDNESS|1|3", "WITHER|1|4", "HUNGER|2|6", "SLOWNESS|1|6");
    private static final List<String> PROJECTILES = List.of("FIREBALL", "SMALL_FIREBALL",
            "WITHER_SKULL", "ARROW", "SNOWBALL");
    private static final List<String> SEQUENCES = List.of(
            "[PARTICLE] FLAME;count:30;offset:0.6,1,0.6\n[SOUND] ENTITY_BLAZE_SHOOT;1;0.8",
            "[PARTICLE] END_ROD;count:40;offset:0.6,1,0.6;speed:0.05\n[SOUND] BLOCK_AMETHYST_BLOCK_CHIME;1;1.2",
            "[PARTICLE] SOUL_FIRE_FLAME;count:25;offset:0.5,1,0.5\n[SOUND] ENTITY_WITHER_SHOOT;0.6;1.4",
            "[LIGHTNING]");

    /** The triggers each skill type makes sense on; COMMAND is never rolled. */
    private static final Map<MobSkill.Type, List<MobSkill.Trigger>> TRIGGERS = new LinkedHashMap<>();

    static {
        MobSkill.Trigger spawn = MobSkill.Trigger.SPAWN, interval = MobSkill.Trigger.INTERVAL,
                attack = MobSkill.Trigger.ATTACK, damaged = MobSkill.Trigger.DAMAGED,
                low = MobSkill.Trigger.LOW_HEALTH, death = MobSkill.Trigger.DEATH;
        TRIGGERS.put(MobSkill.Type.LEAP, List.of(interval, damaged));
        TRIGGERS.put(MobSkill.Type.PULL, List.of(interval, damaged));
        TRIGGERS.put(MobSkill.Type.PUSH, List.of(interval, damaged, low));
        TRIGGERS.put(MobSkill.Type.POTION, List.of(attack, interval, damaged));
        TRIGGERS.put(MobSkill.Type.SUMMON, List.of(spawn, interval, low, death));
        TRIGGERS.put(MobSkill.Type.LIGHTNING, List.of(interval, attack, death));
        TRIGGERS.put(MobSkill.Type.PROJECTILE, List.of(interval));
        TRIGGERS.put(MobSkill.Type.HEAL, List.of(low, damaged));
        TRIGGERS.put(MobSkill.Type.TELEPORT, List.of(interval, damaged));
        TRIGGERS.put(MobSkill.Type.AREA_DAMAGE, List.of(interval, low, death));
        TRIGGERS.put(MobSkill.Type.IGNITE, List.of(attack, damaged));
        TRIGGERS.put(MobSkill.Type.EFFECT, List.of(spawn, low, death));
    }

    private RandomTemplate() {
        throw new AssertionError("No instances.");
    }

    /** The server's own items, enchanted through the registry. */
    static final Items SERVER_ITEMS = (material, enchantment, level) -> {
        ItemStack item = new ItemStack(material);
        Enchantment found = enchantment == null ? null : Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantment));
        if (found != null) item.addUnsafeEnchantment(found, level);
        return item;
    };

    static @NotNull MobTemplate roll(@NotNull String id, @NotNull RandomGenerator random, @NotNull Items items) {
        Kind kind = kind(random);
        return new MobTemplate(id, kind.type(), name(kind, random), equipment(kind, random, items),
                attributes(kind, random), flags(kind, random), effects(random), skills(id, random),
                List.of(), random.nextInt(5, 61), random.nextInt(5, 81));
    }

    /** Whether the random generator may pick this type, and what it may give it. */
    static @Nullable Kind kindOf(@NotNull EntityType type) {
        for (Kind kind : KINDS) if (kind.type() == type) return kind;
        return null;
    }

    private static Kind kind(RandomGenerator random) {
        int total = KINDS.stream().mapToInt(Kind::weight).sum();
        int roll = random.nextInt(total);
        for (Kind kind : KINDS) {
            roll -= kind.weight();
            if (roll < 0) return kind;
        }
        throw new AssertionError("weights");
    }

    private static String name(Kind kind, RandomGenerator random) {
        String noun = kind.type().name().replace('_', ' ');
        String name = pick(PREFIXES, random) + " " + noun;
        if (random.nextInt(3) == 0) name += " " + pick(SUFFIXES, random);
        return "{primary}&l" + name.toUpperCase(Locale.ROOT) + HEALTH;
    }

    private static List<ItemStack> equipment(Kind kind, RandomGenerator random, Items items) {
        ItemStack[] loadout = new ItemStack[MobTemplate.MAIN_HAND + 1];
        int tier = random.nextInt(TIERS.length);
        boolean enchanted = random.nextInt(4) == 0;
        int level = random.nextInt(1, 4);
        if (kind.armour()) {
            int count = random.nextInt(0, 5);
            List<Integer> slots = new ArrayList<>(List.of(0, 1, 2, 3));
            for (int i = 0; i < count; i++) {
                int slot = slots.remove(random.nextInt(slots.size()));
                loadout[slot] = items.make(Material.valueOf(TIERS[tier][0] + "_" + PIECES[slot]),
                        enchanted ? "protection" : null, level);
            }
        }
        if (kind.weapon() != null) {
            String weapon = kind.weapon();
            String enchantment = null;
            if (weapon.equals("SWORD") || weapon.equals("AXE")) {
                weapon = TIERS[tier][1] + "_" + weapon;
                enchantment = "sharpness";
            } else if (weapon.equals("BOW")) {
                enchantment = "power";
            }
            loadout[MobTemplate.MAIN_HAND] = items.make(Material.valueOf(weapon),
                    enchanted ? enchantment : null, level);
        }
        return Arrays.asList(loadout);
    }

    private static Map<String, Double> attributes(Kind kind, RandomGenerator random) {
        List<String> keys = new ArrayList<>(MobTemplate.ATTRIBUTES);
        int count = random.nextInt(2, 6);
        Map<String, Double> attributes = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = keys.remove(random.nextInt(keys.size()));
            attributes.put(key, switch (key) {
                case "max_health" -> (double) random.nextInt(20, 151);
                case "attack_damage" -> (double) random.nextInt(2, 15);
                case "movement_speed" -> round(random.nextDouble(0.2, 0.4), 100);
                case "armor" -> (double) random.nextInt(2, 13);
                case "armor_toughness" -> (double) random.nextInt(1, 7);
                case "knockback_resistance" -> round(random.nextDouble(0.1, 0.8), 10);
                case "follow_range" -> (double) random.nextInt(16, 41);
                case "attack_knockback" -> round(random.nextDouble(0.5, 2), 10);
                case "scale" -> round(random.nextDouble(0.7, kind.big() ? 1.3 : 1.8), 10);
                default -> throw new AssertionError(key);
            });
        }
        return attributes;
    }

    /** The flags this type may carry; wolves and golems always hunt. */
    static Set<MobFlag> allowedFlags(Kind kind) {
        Set<MobFlag> flags = EnumSet.of(MobFlag.GLOWING, MobFlag.SILENT, MobFlag.NO_VANILLA_DROPS,
                MobFlag.NO_VANILLA_EXP);
        if (kind.baby()) flags.add(MobFlag.BABY);
        if (kind.burns()) flags.add(MobFlag.NO_SUN_BURN);
        if (!kind.fireproof()) flags.add(MobFlag.FIRE_IMMUNE);
        if (kind.armour()) flags.add(MobFlag.NO_ITEM_PICKUP);
        if (kind.neutral()) flags.add(MobFlag.AGGRESSIVE);
        return flags;
    }

    private static Set<MobFlag> flags(Kind kind, RandomGenerator random) {
        List<MobFlag> allowed = new ArrayList<>(allowedFlags(kind));
        Set<MobFlag> flags = EnumSet.noneOf(MobFlag.class);
        // A wolf or golem that does not hunt is a pet with a health bar.
        if (kind.type() == EntityType.WOLF || kind.type() == EntityType.IRON_GOLEM) flags.add(MobFlag.AGGRESSIVE);
        allowed.removeAll(flags);
        int count = random.nextInt(0, 4);
        for (int i = 0; i < count; i++) flags.add(allowed.remove(random.nextInt(allowed.size())));
        return flags;
    }

    private static List<ParsedEffect> effects(RandomGenerator random) {
        List<String> names = new ArrayList<>(EFFECTS);
        int count = random.nextInt(0, 3);
        List<ParsedEffect> effects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = names.remove(random.nextInt(names.size()));
            effects.add(Effects.parse(name + "|" + random.nextInt(1, 3) + "|infinite"));
        }
        return effects;
    }

    private static List<MobSkill> skills(String id, RandomGenerator random) {
        List<MobSkill.Type> types = new ArrayList<>(TRIGGERS.keySet());
        int count = random.nextInt(1, 5);
        List<MobSkill> skills = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MobSkill.Type type = types.remove(random.nextInt(types.size()));
            skills.add(skill(id, type, pick(TRIGGERS.get(type), random), random));
        }
        return skills;
    }

    private static MobSkill skill(String id, MobSkill.Type type, MobSkill.Trigger trigger, RandomGenerator random) {
        boolean onHit = trigger == MobSkill.Trigger.ATTACK || trigger == MobSkill.Trigger.DAMAGED;
        double chance = onHit ? round(random.nextDouble(0.15, 0.4), 100) : 1;
        Duration cooldown = Duration.ofSeconds(trigger == MobSkill.Trigger.INTERVAL
                ? random.nextInt(6, 16) : random.nextInt(3, 11));
        double threshold = trigger == MobSkill.Trigger.LOW_HEALTH ? round(random.nextDouble(0.25, 0.5), 100) : 0.3;
        double radius = 0;
        double amount = 0;
        Duration duration = Duration.ZERO;
        String text = "";
        switch (type) {
            case LEAP -> amount = round(random.nextDouble(0.8, 1.6), 10);
            case PULL -> amount = round(random.nextDouble(0.8, 1.5), 10);
            case PUSH -> {
                radius = random.nextInt(3, 7);
                amount = round(random.nextDouble(0.8, 1.6), 10);
            }
            case POTION -> {
                text = pick(CURSES, random);
                radius = random.nextBoolean() ? 0 : random.nextInt(3, 6);
            }
            case SUMMON -> {
                // Its own template: the only id a random mob can be sure exists.
                text = id;
                amount = random.nextInt(1, 4);
                radius = 3;
            }
            case LIGHTNING -> amount = random.nextInt(2, 7);
            case PROJECTILE -> {
                text = pick(PROJECTILES, random);
                amount = round(random.nextDouble(1, 2), 10);
            }
            case HEAL -> amount = random.nextInt(10, 31);
            case AREA_DAMAGE -> {
                radius = random.nextInt(3, 6);
                amount = random.nextInt(2, 7);
            }
            case IGNITE -> duration = Duration.ofSeconds(random.nextInt(2, 6));
            case EFFECT -> text = pick(SEQUENCES, random);
            case TELEPORT, COMMAND -> { }
        }
        return new MobSkill(trigger, type, chance, cooldown, threshold, radius, amount, duration, text);
    }

    private static <T> T pick(List<T> list, RandomGenerator random) {
        return list.get(random.nextInt(list.size()));
    }

    private static double round(double value, int scale) {
        return Math.round(value * scale) / (double) scale;
    }
}
