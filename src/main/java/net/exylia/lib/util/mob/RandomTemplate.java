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
            "BLINDNESS|1|3", "WITHER|1|4", "HUNGER|2|6");

    /**
     * How dangerous a rolled mob is, and everything that follows from it.
     *
     * @param name     what it is called, in capitals
     * @param weight   how often it is rolled
     * @param health   {@code max_health} range, end exclusive
     * @param damage   {@code attack_damage} range, end exclusive
     * @param moves    how many rotation moves it knows, end exclusive
     * @param period   seconds between two rotation moves
     * @param gear     lowest and highest armour tier
     * @param phases   health shares it changes phase below
     * @param exp      experience range, end exclusive
     * @param money    money range, end exclusive
     */
    record Rank(String name, int weight, int[] health, int[] damage, int[] moves, int period, int[] gear,
                double[] phases, int[] exp, int[] money) { }

    static final List<Rank> RANKS = List.of(
            new Rank("ELITE", 6, new int[]{40, 91}, new int[]{4, 8}, new int[]{2, 4}, 7,
                    new int[]{1, 3}, new double[]{0.35}, new int[]{15, 41}, new int[]{15, 61}),
            new Rank("CHAMPION", 3, new int[]{100, 221}, new int[]{6, 11}, new int[]{3, 5}, 6,
                    new int[]{2, 4}, new double[]{0.5}, new int[]{40, 121}, new int[]{60, 201}),
            new Rank("BOSS", 1, new int[]{250, 501}, new int[]{9, 15}, new int[]{4, 6}, 5,
                    new int[]{4, 5}, new double[]{0.6, 0.3}, new int[]{150, 401}, new int[]{250, 801}));

    /** Kinds that keep their distance: they get ranged and caster moves. */
    private static final Set<EntityType> RANGED = EnumSet.of(EntityType.SKELETON, EntityType.STRAY,
            EntityType.PILLAGER, EntityType.EVOKER, EntityType.BLAZE, EntityType.WITCH);

    /** Rotation moves by preset id, for fighters up close and for those at range. */
    private static final List<String> MELEE_MOVES = List.of("slam", "cleave", "charge", "pounce", "hook",
            "blades", "fissure", "vortex", "eruption", "chain", "nova", "miasma", "portal");
    private static final List<String> RANGED_MOVES = List.of("volley", "rain", "chain", "smite", "meteor",
            "miasma", "eruption", "fissure", "blink", "dread", "nova", "portal");

    /** What a mob casts as it enters a phase, and on low health when it has no phase left. */
    private static final List<String> PHASE_MOVES = List.of("bubble", "enrage", "renew", "dread", "vortex");

    /** One move straight after another, when the mob knows both: {first, then}. */
    private static final String[][] COMBOS = {
            {"hook", "cleave"}, {"hook", "slam"}, {"vortex", "slam"}, {"vortex", "blades"},
            {"pounce", "slam"}, {"charge", "cleave"}, {"blink", "cleave"}, {"nova", "eruption"},
            {"dread", "smite"}, {"vortex", "eruption"}};

    /** Phase suffixes, in order. */
    private static final String[] PHASE_SUFFIX = {"{warning}✦", "{error}☠"};

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
        Rank rank = weighted(RANKS, Rank::weight, random);
        List<MobSkill> skills = skills(id, kind, rank, random);
        return new MobTemplate(id, kind.type(), name(kind, rank, random), equipment(kind, rank, random, items),
                attributes(kind, rank, random), flags(kind, random), effects(rank, random), skills,
                List.of(), between(rank.exp(), random), between(rank.money(), random), MobBehaviour.NONE,
                look(rank, random), fight(rank, random));
    }

    /** Whether the random generator may pick this type, and what it may give it. */
    static @Nullable Kind kindOf(@NotNull EntityType type) {
        for (Kind kind : KINDS) if (kind.type() == type) return kind;
        return null;
    }

    private static Kind kind(RandomGenerator random) {
        return weighted(KINDS, Kind::weight, random);
    }

    private static <T> T weighted(List<T> list, java.util.function.ToIntFunction<T> weight, RandomGenerator random) {
        int roll = random.nextInt(list.stream().mapToInt(weight).sum());
        for (T each : list) {
            roll -= weight.applyAsInt(each);
            if (roll < 0) return each;
        }
        throw new AssertionError("weights");
    }

    private static String name(Kind kind, Rank rank, RandomGenerator random) {
        String noun = kind.type().name().replace('_', ' ');
        String name = pick(PREFIXES, random) + " " + noun;
        // A boss always carries a title; the others sometimes do.
        if (rank == RANKS.getLast() || random.nextInt(3) == 0) name += " " + pick(SUFFIXES, random);
        return "{primary}&l" + name.toUpperCase(Locale.ROOT) + HEALTH;
    }

    private static List<ItemStack> equipment(Kind kind, Rank rank, RandomGenerator random, Items items) {
        ItemStack[] loadout = new ItemStack[MobTemplate.MAIN_HAND + 1];
        int step = RANKS.indexOf(rank);
        int tier = random.nextInt(rank.gear()[0], rank.gear()[1] + 1);
        boolean enchanted = random.nextInt(3) < step + 1;
        int level = random.nextInt(1, 3) + step;
        if (kind.armour()) {
            int count = step == 2 ? 4 : random.nextInt(1 + step, 5);
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

    private static Map<String, Double> attributes(Kind kind, Rank rank, RandomGenerator random) {
        int step = RANKS.indexOf(rank);
        Map<String, Double> attributes = new LinkedHashMap<>();
        attributes.put("max_health", (double) between(rank.health(), random));
        attributes.put("attack_damage", (double) between(rank.damage(), random));
        attributes.put("follow_range", (double) random.nextInt(24, 41));
        if (step > 0) attributes.put("armor", (double) random.nextInt(4 * step, 8 * step + 1));
        if (step > 0) attributes.put("knockback_resistance", round(random.nextDouble(0.3 * step, 0.45 * step), 10));
        if (step == 2) {
            attributes.put("armor_toughness", (double) random.nextInt(2, 7));
            attributes.put("scale", round(kind.big() ? random.nextDouble(1, 1.2) : random.nextDouble(1.25, 1.6), 10));
        } else if (random.nextInt(3) == 0) {
            attributes.put("scale", round(random.nextDouble(0.85, kind.big() ? 1.1 : 1.3), 10));
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

    private static List<ParsedEffect> effects(Rank rank, RandomGenerator random) {
        List<String> names = new ArrayList<>(EFFECTS);
        int count = random.nextInt(0, 2 + RANKS.indexOf(rank));
        List<ParsedEffect> effects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = names.remove(random.nextInt(names.size()));
            effects.add(Effects.parse(name + "|" + random.nextInt(1, 3) + "|infinite"));
        }
        return effects;
    }

    /**
     * A moveset: library moves taking turns in one rotation, a combo when two
     * of them chain, a trick on each phase and a sting on its hits.
     */
    private static List<MobSkill> skills(String id, Kind kind, Rank rank, RandomGenerator random) {
        boolean boss = rank == RANKS.getLast();
        List<String> pool = new ArrayList<>(RANGED.contains(kind.type()) ? RANGED_MOVES : MELEE_MOVES);
        // Its minions are itself: a boss calling more bosses is not a fight.
        if (boss) pool.remove("portal");
        int count = between(rank.moves(), random);
        List<String> moves = new ArrayList<>();
        for (int i = 0; i < count && !pool.isEmpty(); i++) moves.add(pool.remove(random.nextInt(pool.size())));

        List<String> tricks = new ArrayList<>(PHASE_MOVES);
        tricks.removeAll(moves);
        String[] combo = null;
        if (!rank.name().equals("ELITE")) {
            for (String[] each : COMBOS) {
                if (moves.contains(each[0]) && moves.contains(each[1])) {
                    combo = each;
                    break;
                }
            }
        }

        List<MobSkill> skills = new ArrayList<>();
        for (String move : moves) {
            MobSkill skill = preset(move);
            MobSkill.Cast cast = skill.cast().withGroup("moves");
            if (combo != null && combo[0].equals(move)) cast = cast.withThen(combo[1]);
            skill = skill.withCast(cast);
            if (skill.type() == MobSkill.Type.SUMMON) skill = skill.withText(id);
            skills.add(skill);
        }
        for (int phase = 2; phase <= rank.phases().length + 1; phase++) {
            MobSkill trick = preset(tricks.remove(random.nextInt(tricks.size())));
            skills.add(trick.withTrigger(MobSkill.Trigger.PHASE)
                    .withCast(trick.cast().withWhen(MobSkill.Gate.ANY.withPhase(phase))));
        }
        if (random.nextBoolean() || boss) skills.add(sting(kind, random));
        return skills;
    }

    private static MobSkill preset(String id) {
        MobSkills.Preset preset = MobSkills.preset(id);
        if (preset == null) throw new AssertionError("no preset " + id);
        return preset.skill();
    }

    /** A chance on each hit it lands: a curse, or fire from a kind that burns. */
    private static MobSkill sting(Kind kind, RandomGenerator random) {
        Duration cooldown = Duration.ofSeconds(random.nextInt(5, 9));
        double chance = round(random.nextDouble(0.2, 0.35), 100);
        if (kind.fireproof() && random.nextBoolean()) {
            return new MobSkill(MobSkill.Trigger.ATTACK, MobSkill.Type.IGNITE, chance, cooldown, 0.3, 0, 0,
                    Duration.ofSeconds(random.nextInt(3, 6)), "");
        }
        return new MobSkill(MobSkill.Trigger.ATTACK, MobSkill.Type.POTION, chance, cooldown, 0.3, 0, 0,
                Duration.ZERO, pick(CURSES, random));
    }

    /** One rotation, a breath between big moves, and a phase per rank threshold. */
    private static MobFight fight(Rank rank, RandomGenerator random) {
        List<MobPhase> phases = new ArrayList<>();
        for (int i = 0; i < rank.phases().length; i++) {
            double push = 1 + 0.15 * (i + 1);
            phases.add(new MobPhase(rank.phases()[i], "", PHASE_SUFFIX[i], round(push, 100),
                    round(push + 0.1, 100), round(1 + 0.1 * i, 100)));
        }
        Duration period = Duration.ofSeconds(rank.period() + random.nextInt(0, 2));
        return new MobFight(Duration.ofMillis(1500 - 250L * RANKS.indexOf(rank)), Map.of("moves", period), phases);
    }

    /** Elites look vanilla; champions and bosses glow and make an entrance. */
    private static MobLook look(Rank rank, RandomGenerator random) {
        return switch (RANKS.indexOf(rank)) {
            case 0 -> MobLook.NONE;
            case 1 -> MobLook.NONE.withGlow(pick(MobLook.GLOWS, random)).withSpawn("portal");
            default -> MobLook.NONE.withGlow(pick(MobLook.GLOWS, random)).withSpawn("bolt").withLow("frantic");
        };
    }

    private static int between(int[] range, RandomGenerator random) {
        return random.nextInt(range[0], range[1]);
    }

    private static <T> T pick(List<T> list, RandomGenerator random) {
        return list.get(random.nextInt(list.size()));
    }

    private static double round(double value, int scale) {
        return Math.round(value * scale) / (double) scale;
    }
}
