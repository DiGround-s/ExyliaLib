package net.exylia.lib.ability;

import java.util.List;

/**
 * The names of the triggers the library fires on its own.
 *
 * <p>A trigger is a name, not an enum, so a plugin adds its own with
 * {@link PluginAbilities#trigger(String)} and fires it with
 * {@link PluginAbilities#fire}. Names are upper case.
 *
 * @since 1.385.0
 */
public final class Triggers {

    private Triggers() {
        throw new AssertionError("No instances.");
    }

    /** The holder hits a living entity in melee. Victim: what was hit. */
    public static final String ATTACK = "ATTACK";
    /** The holder is hurt by an entity. Attacker: who hurt them, a shooter for a projectile. */
    public static final String HIT_TAKEN = "HIT_TAKEN";
    /** The holder kills a living entity. Victim: the dead; its drops are {@link ContextKey#DROPS}. */
    public static final String KILL = "KILL";
    /** The holder dies. Attacker: the killer, if any. */
    public static final String DEATH = "DEATH";
    /** The holder shoots a bow or crossbow. */
    public static final String BOW_SHOOT = "BOW_SHOOT";
    /** A projectile the holder shot lands. Victim: the entity hit; block: the block hit. */
    public static final String PROJECTILE_HIT = "PROJECTILE_HIT";
    /** The holder breaks a block. Block: the block; never fired for the extra blocks of an area break. */
    public static final String BLOCK_BREAK = "BLOCK_BREAK";
    /** The holder catches something with a rod. Victim: a caught living entity. */
    public static final String FISH = "FISH";
    /** The holder jumps. */
    public static final String JUMP = "JUMP";
    /** The holder starts sneaking. */
    public static final String SNEAK = "SNEAK";
    /** A hit takes the holder from above 30% of their maximum health to 30% or less. */
    public static final String LOW_HEALTH = "LOW_HEALTH";
    /** The holder starts holding the ability. */
    public static final String EQUIP = "EQUIP";
    /** The holder stops holding the ability. */
    public static final String UNEQUIP = "UNEQUIP";
    /** Every {@code every} seconds while held; one timer per player, whatever the count. */
    public static final String INTERVAL = "INTERVAL";
    /**
     * Sugar for an EQUIP/UNEQUIP pair: the effects apply on equip and
     * {@link AbilityEffect#revert revert} on unequip.
     */
    public static final String PASSIVE = "PASSIVE";

    /** Every built-in trigger, in the order above. */
    public static final List<String> BUILT_IN = List.of(ATTACK, HIT_TAKEN, KILL, DEATH, BOW_SHOOT,
            PROJECTILE_HIT, BLOCK_BREAK, FISH, JUMP, SNEAK, LOW_HEALTH, EQUIP, UNEQUIP, INTERVAL, PASSIVE);
}
