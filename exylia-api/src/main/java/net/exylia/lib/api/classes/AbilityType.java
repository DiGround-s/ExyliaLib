package net.exylia.lib.api.classes;

/**
 * Who an ability reaches when it is used.
 *
 * <p>The clan-aware values mean nothing on a server without clans, where they
 * find nobody and the ability behaves as {@link #SELF} does.
 *
 * @since 1.0.0
 */
public enum AbilityType {

    /** Only the player who used it. */
    SELF,

    /** Everyone in the caster's clan, within its radius. */
    TO_CLAN,

    /** Everyone in a clan allied to the caster's, within its radius. */
    TO_ALLY,

    /** Everyone who is neither, within its radius. */
    TO_ENEMY,

    /** Clanmates and allies together, within its radius. */
    TO_CLAN_AND_ALLY
}
