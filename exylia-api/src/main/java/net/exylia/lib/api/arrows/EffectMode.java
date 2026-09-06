package net.exylia.lib.api.arrows;

/**
 * Where the effect a shot plays comes from.
 *
 * <p>The two sources answer different kinds of server. An effect bound to a bow
 * travels with it: it can be traded, lost on death and sold as an item, which
 * is what a survival or economy server wants. An effect chosen in the menu
 * belongs to the player and plays on whatever they fire, which is what a
 * practice or PvP server wants.
 *
 * <p>Worth asking before writing anything: half of {@link ArrowsService} does
 * nothing on a server whose mode does not use that half, and reports so rather
 * than failing.
 *
 * @since 1.0.0
 */
public enum EffectMode {

    /** Only what the player picked in the menu; a bow carries nothing. */
    PLAYER,

    /** Only what is bound to the bow; the menu chooses nothing. */
    BOW,

    /** The bow's own effect, and the player's choice for a bow carrying none. */
    BOTH;

    /**
     * Whether an effect bound to a bow is read, and whether one can be bound.
     *
     * @return {@code true} when bound effects mean something on this server
     */
    public boolean usesBows() {
        return this != PLAYER;
    }

    /**
     * Whether a player's chosen effect is read, and worth storing.
     *
     * @return {@code true} when the menu's choice means something on this server
     */
    public boolean usesMenu() {
        return this != BOW;
    }
}
