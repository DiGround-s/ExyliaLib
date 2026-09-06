package net.exylia.lib.api.totemtrainer;

/**
 * What this plugin can be doing with a player.
 *
 * <p>Both take the player's inventory and location, so a player in either is a
 * player another plugin should not be teleporting, equipping or opening menus
 * for. That is the question this enum exists to answer, and why there is no
 * constant for "nothing": absence is the empty
 * {@link java.util.Optional} from {@link TotemTrainerService#activityOf}.
 *
 * @since 1.0.0
 */
public enum PlayerActivity {

    /** A solo session against the plugin's own totem engine. */
    TRAINING,

    /** A duel against another player in an arena. */
    MATCH
}
