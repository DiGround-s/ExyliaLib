package net.exylia.lib.util.mob;

import org.bukkit.DyeColor;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Llama;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * How a custom mob looks beyond its equipment: its colour, what it wears on
 * its back, the colour of its outline and the aura drawn around it, and how it
 * reacts to what happens to it.
 *
 * <pre>{@code
 * MobLook look = new MobLook("CREAMY", MobLook.CYCLE, MobLook.RANDOM, "confetti")
 *         .withSpawn("portal")
 *         .withDeath(MobLook.OFF);
 * }</pre>
 *
 * <p>Every part of the appearance is a name, {@link #CYCLE} (a new one every
 * second, from the part's list), {@link #RANDOM} (one from that list, picked as
 * it spawns) or blank (vanilla; {@code NONE} reads as blank). Names are
 * case-insensitive.
 *
 * <h2>Reactions (since 1.199.0)</h2>
 * How the mob enters, takes a hit, dies and looks while it is nearly done:
 * a reaction id from {@link #SPAWNS}, {@link #HURTS}, {@link #DEATHS} or
 * {@link #LOWS}, {@link #OFF} for none, or blank ({@link #AUTO}) for what suits
 * the mob: a humanoid's body comes apart as a ragdoll, a hits-mode mob pops like
 * a piñata. {@code numbers} shows the damage and healing it takes as floating
 * numbers, or the hits it has left in hits mode. Reactions are visuals only; a
 * server can turn them all off with {@link MobVisuals#reactions()}.
 *
 * @param variant its colour or kind: a {@link #variants} name for its type
 * @param body    the item on its back: a llama's carpet, a horse's armour, a
 *                wolf's armour, as a material name; {@link #bodies} is what
 *                CYCLE and RANDOM pick from
 * @param glow    its outline colour, a named text colour such as {@code light_purple};
 *                any value but blank also makes it glow. {@link #GLOWS} is what CYCLE
 *                and RANDOM pick from. Coloured through a team on the main
 *                scoreboard, so it stays white on Folia
 * @param aura    a registered aura's name ({@link PluginMobs#auras}); an unknown name
 *                wears the first registered one
 * @param spawn   how it enters: {@code rise}, {@code portal}, {@code drop} or {@code bolt}
 * @param hurt    how a hit shows: {@code spark} or {@code pop}
 * @param death   how it leaves: {@code ragdoll}, {@code shatter} or {@code pinata}
 * @param low     how it looks nearly done: {@code wounded} or {@code frantic}
 * @param numbers whether damage, healing and hits left float out of it
 * @since 1.195.0
 */
public record MobLook(@NotNull String variant, @NotNull String body, @NotNull String glow, @NotNull String aura,
                      @NotNull String spawn, @NotNull String hurt, @NotNull String death, @NotNull String low,
                      boolean numbers) {

    /** A new pick every second. */
    public static final String CYCLE = "CYCLE";

    /** One pick, as it spawns. */
    public static final String RANDOM = "RANDOM";

    /** A reaction that suits the mob. @since 1.199.0 */
    public static final String AUTO = "";

    /** No reaction at all. @since 1.199.0 */
    public static final String OFF = "none";

    /** Vanilla appearance, reactions that suit the mob, numbers on. */
    public static final MobLook NONE = new MobLook("", "", "", "");

    /** The outline colours CYCLE walks and RANDOM picks from; the dark ones are left out. */
    public static final List<String> GLOWS = List.of("LIGHT_PURPLE", "AQUA", "YELLOW", "GREEN", "RED",
            "BLUE", "GOLD", "WHITE");

    /**
     * How a mob can enter: up out of the ground, through a portal, dropped from
     * above onto a warning ring, or struck down as a bolt. AUTO rises; a mob in
     * hits mode drops, and a mob standing on nothing solid comes through a portal.
     *
     * @since 1.199.0
     */
    public static final List<String> SPAWNS = List.of("rise", "portal", "drop", "bolt");

    /**
     * How a hit shows: sparks and, on a heavy one, chips of its body; or a
     * piñata's squash, candies and confetti. AUTO pops in hits mode and sparks
     * otherwise.
     *
     * @since 1.199.0
     */
    public static final List<String> HURTS = List.of("spark", "pop");

    /**
     * How it leaves: a humanoid's body thrown apart, blocks of its colours
     * shattering, or a piñata bursting into candies and fireworks. AUTO breaks
     * a hits-mode mob like a piñata, throws a humanoid apart and shatters the rest.
     *
     * @since 1.199.0
     */
    public static final List<String> DEATHS = List.of("ragdoll", "shatter", "pinata");

    /**
     * How it looks nearly done, below its lowest LOW_HEALTH threshold or a
     * quarter: dripping and a heartbeat, or a piñata's frantic sweat. AUTO is
     * frantic in hits mode and wounded otherwise.
     *
     * @since 1.199.0
     */
    public static final List<String> LOWS = List.of("wounded", "frantic");

    private static final List<String> CARPETS = List.of("MAGENTA_CARPET", "LIGHT_BLUE_CARPET", "YELLOW_CARPET",
            "LIME_CARPET", "PINK_CARPET", "CYAN_CARPET", "ORANGE_CARPET", "PURPLE_CARPET", "RED_CARPET",
            "WHITE_CARPET");
    private static final List<String> HORSE_ARMOUR = List.of("LEATHER_HORSE_ARMOR", "IRON_HORSE_ARMOR",
            "GOLDEN_HORSE_ARMOR", "DIAMOND_HORSE_ARMOR");

    public MobLook {
        variant = clean(variant);
        body = clean(body);
        glow = clean(glow);
        aura = clean(aura);
        spawn = reaction(spawn);
        hurt = reaction(hurt);
        death = reaction(death);
        low = reaction(low);
    }

    /** The appearance alone; every reaction AUTO and numbers on. */
    public MobLook(@NotNull String variant, @NotNull String body, @NotNull String glow, @NotNull String aura) {
        this(variant, body, glow, aura, AUTO, AUTO, AUTO, AUTO, true);
    }

    public @NotNull MobLook withVariant(@NotNull String variant) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    public @NotNull MobLook withBody(@NotNull String body) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    public @NotNull MobLook withGlow(@NotNull String glow) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    public @NotNull MobLook withAura(@NotNull String aura) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    /** @since 1.199.0 */
    public @NotNull MobLook withSpawn(@NotNull String spawn) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    /** @since 1.199.0 */
    public @NotNull MobLook withHurt(@NotNull String hurt) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    /** @since 1.199.0 */
    public @NotNull MobLook withDeath(@NotNull String death) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    /** @since 1.199.0 */
    public @NotNull MobLook withLow(@NotNull String low) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    /** @since 1.199.0 */
    public @NotNull MobLook withNumbers(boolean numbers) {
        return new MobLook(variant, body, glow, aura, spawn, hurt, death, low, numbers);
    }

    /**
     * Whether it only sets reactions, or nothing at all: what a menu shows as a
     * vanilla appearance.
     *
     * @since 1.199.0
     */
    public boolean vanillaAppearance() {
        return variant.isEmpty() && body.isEmpty() && glow.isEmpty() && aura.isEmpty();
    }

    /**
     * The variants a type has: a llama's or horse's colour, a sheep's wool,
     * a parrot's, axolotl's, rabbit's, fox's or mooshroom's kind.
     *
     * @param type the entity type
     * @return the names, empty for a type with none the library sets
     */
    public static @NotNull List<String> variants(@NotNull EntityType type) {
        Class<? extends Entity> kind = type.getEntityClass();
        if (kind == null) return List.of();
        if (Llama.class.isAssignableFrom(kind)) return names(Llama.Color.values());
        if (Horse.class.isAssignableFrom(kind)) return names(Horse.Color.values());
        if (Sheep.class.isAssignableFrom(kind)) return names(DyeColor.values());
        if (Parrot.class.isAssignableFrom(kind)) return names(Parrot.Variant.values());
        if (Axolotl.class.isAssignableFrom(kind)) return names(Axolotl.Variant.values());
        if (Rabbit.class.isAssignableFrom(kind)) return names(Rabbit.Type.values());
        if (Fox.class.isAssignableFrom(kind)) return names(Fox.Type.values());
        if (MushroomCow.class.isAssignableFrom(kind)) return names(MushroomCow.Variant.values());
        return List.of();
    }

    /**
     * What a type can wear on its back, and what CYCLE and RANDOM pick from:
     * ten bright carpets for a llama, the four horse armours, wolf armour.
     * Any other carpet or armour can still be written by name.
     *
     * @param type the entity type
     * @return material names, empty for a type with no body slot the library fills
     */
    public static @NotNull List<String> bodies(@NotNull EntityType type) {
        Class<? extends Entity> kind = type.getEntityClass();
        if (kind == null) return List.of();
        if (Llama.class.isAssignableFrom(kind)) return CARPETS;
        if (Horse.class.isAssignableFrom(kind)) return HORSE_ARMOUR;
        if (Wolf.class.isAssignableFrom(kind)) return List.of("WOLF_ARMOR");
        return List.of();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    /** A reaction id: lower case, blank or {@code auto} for AUTO, {@code none} or {@code off} for OFF. */
    private static String reaction(String value) {
        if (value == null) return AUTO;
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.equals("auto")) return AUTO;
        if (trimmed.equals("off")) return OFF;
        return trimmed;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        // What a form sends to clear a prefilled box: a blank one keeps the old value.
        if (trimmed.equalsIgnoreCase("NONE")) return "";
        if (trimmed.equalsIgnoreCase(CYCLE)) return CYCLE;
        if (trimmed.equalsIgnoreCase(RANDOM)) return RANDOM;
        return trimmed;
    }
}
