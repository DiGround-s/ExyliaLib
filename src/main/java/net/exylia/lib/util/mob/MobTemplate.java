package net.exylia.lib.util.mob;

import net.exylia.lib.util.Effects.ParsedEffect;
import net.exylia.lib.util.editor.Loadout;
import net.exylia.lib.util.reward.RewardEntry;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Everything a custom mob is: what it is, what it wears, how strong it is and
 * what it does.
 *
 * <pre>{@code
 * MobTemplate knight = MobTemplate.of("frost_knight", EntityType.ZOMBIE)
 *         .withName("{primary}&lFROST KNIGHT &8[{success}%health%&8/{info}%max_health%&8]")
 *         .withAttributes(Map.of("max_health", 80.0, "attack_damage", 7.0))
 *         .withFlags(Set.of(MobFlag.NO_SUN_BURN, MobFlag.NO_VANILLA_DROPS))
 *         .withSkills(List.of(MobSkill.of(MobSkill.Type.LEAP, MobSkill.Trigger.INTERVAL)));
 * }</pre>
 *
 * <p>Immutable: every {@code with*} returns a new template, so a template a mob
 * was spawned from never changes under it when an admin edits the original.
 *
 * <h2>Equipment is a loadout</h2>
 * The list is in {@link Loadout} order, so {@code Editors.of(plugin).loadout(...)}
 * edits it as it is: the four armour slots, the offhand, and the first hotbar
 * slot as the main hand. Everything else in the loadout is ignored. Equipment
 * never drops.
 *
 * <h2>Attributes by key</h2>
 * {@code max_health}, {@code attack_damage}, {@code movement_speed} and the
 * rest of {@link #ATTRIBUTES} — the server's registry key, so a data pack's
 * attribute works as well. A key the server does not know, or the mob's type
 * does not have, is reported once and skipped.
 *
 * @param id         the template's id, unique within its plugin
 * @param type       a living entity type
 * @param name       the name over its head, in Exylia text notation; {@code %health%}
 *                   and {@code %max_health%} are kept current. Blank for no name
 * @param equipment  in {@link Loadout} order; may hold {@code null}s
 * @param attributes base values by attribute key
 * @param flags      the switches it has on
 * @param effects    potion effects applied as it spawns
 * @param skills     what it does on its own
 * @param rewards    what the consumer gives its killer; the library never gives them
 * @param exp        experience dropped on top of whatever vanilla drops
 * @param money      what the consumer pays its killer; the library never pays it
 * @since 1.192.0
 */
public record MobTemplate(@NotNull String id, @NotNull EntityType type, @NotNull String name,
                          @NotNull List<ItemStack> equipment, @NotNull Map<String, Double> attributes,
                          @NotNull Set<MobFlag> flags, @NotNull List<ParsedEffect> effects,
                          @NotNull List<MobSkill> skills, @NotNull List<RewardEntry> rewards,
                          int exp, double money) {

    /** The attribute keys the editor offers, in the order it offers them. */
    public static final List<String> ATTRIBUTES = List.of("max_health", "attack_damage",
            "movement_speed", "armor", "armor_toughness", "knockback_resistance",
            "follow_range", "attack_knockback", "scale");

    public MobTemplate {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("a template needs an id");
        Objects.requireNonNull(type, "type");
        name = name == null ? "" : name;
        // Nulls are empty loadout positions, so List.copyOf cannot hold them.
        equipment = Collections.unmodifiableList(new ArrayList<>(equipment == null ? List.of() : equipment));
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        flags = flags == null || flags.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(flags));
        effects = effects == null ? List.of() : List.copyOf(effects);
        skills = skills == null ? List.of() : List.copyOf(skills);
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        exp = Math.max(0, exp);
        money = Double.isFinite(money) ? Math.max(0, money) : 0;
    }

    /**
     * A template with nothing but an id and a type: a vanilla mob, tagged.
     *
     * @param id   the template's id
     * @param type a living entity type
     * @return the template
     */
    public static @NotNull MobTemplate of(@NotNull String id, @NotNull EntityType type) {
        return new MobTemplate(id, type, "", List.of(), Map.of(), Set.of(), List.of(),
                List.of(), List.of(), 0, 0);
    }

    /**
     * A random but playable template, to show what a mob can be.
     *
     * <p>A hostile-capable type from a curated list (no warden, ravagers are
     * rare), a {@code {primary}} name with the health bar, armour of one
     * material tier and a weapon that fits the type where it can wear them,
     * sometimes enchanted, two to five attributes in sensible ranges, flags
     * the type can use, up to two potion effects, one to four skills on
     * triggers that suit them, {@code 5-60} experience and {@code 5-80} money.
     * Never a {@link MobSkill.Type#COMMAND} skill; a {@link MobSkill.Type#SUMMON}
     * summons this very template. No rewards: those are the admin's.
     *
     * <p>The same seed gives the same template. Needs a running server for the
     * equipment items.
     *
     * @param id     the new template's id
     * @param random where every choice comes from
     * @return the template
     * @since 1.193.0
     */
    public static @NotNull MobTemplate random(@NotNull String id, @NotNull RandomGenerator random) {
        return RandomTemplate.roll(id, random, RandomTemplate.SERVER_ITEMS);
    }

    /** Whether a flag is on. */
    public boolean has(@NotNull MobFlag flag) {
        return flags.contains(flag);
    }

    /**
     * The item at one equipment position.
     *
     * @param index a {@link Loadout} position: {@code 0-3} armour, {@link Loadout#OFFHAND},
     *              {@link #MAIN_HAND}
     * @return the item, or {@code null} for none
     */
    public @Nullable ItemStack equipment(int index) {
        return Loadout.at(equipment, index);
    }

    /** The loadout position that is the main hand: the first hotbar slot. */
    public static final int MAIN_HAND = Loadout.HOTBAR_START;

    public @NotNull MobTemplate withId(@NotNull String id) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withType(@NotNull EntityType type) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withName(@NotNull String name) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withEquipment(@NotNull List<ItemStack> equipment) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withAttributes(@NotNull Map<String, Double> attributes) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withFlags(@NotNull Set<MobFlag> flags) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withEffects(@NotNull List<ParsedEffect> effects) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withSkills(@NotNull List<MobSkill> skills) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withRewards(@NotNull List<RewardEntry> rewards) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withExp(int exp) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }

    public @NotNull MobTemplate withMoney(double money) {
        return new MobTemplate(id, type, name, equipment, attributes, flags, effects, skills, rewards, exp, money);
    }
}
