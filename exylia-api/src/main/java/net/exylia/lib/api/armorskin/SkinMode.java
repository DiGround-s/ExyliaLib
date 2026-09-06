package net.exylia.lib.api.armorskin;

/**
 * Where the skin a piece of armor is drawn with comes from.
 *
 * <p>The two sources answer different kinds of server. A skin written onto the
 * armor travels with it: it can be traded, lost on death and sold as an item,
 * which is what a survival or economy server wants. A skin chosen in the
 * wardrobe belongs to the player instead, and lands on whatever they equip,
 * which is what a practice or PvP server wants.
 *
 * <p>Worth asking before writing anything: half of {@link ArmorSkinService}
 * does nothing on a server whose mode does not use that half, and reports so
 * rather than failing.
 *
 * @since 1.0.0
 */
public enum SkinMode {

    /** Only what a skin item wrote onto the armor. */
    ITEM,

    /** Only what the wearer chose in their wardrobe; skin items do nothing. */
    WARDROBE,

    /** The armor's own skin, and the wardrobe for armor that carries none. */
    BOTH;

    /**
     * Whether a skin item can be applied, and whether armor's own skin is read.
     *
     * @return {@code true} when skin items mean something on this server
     */
    public boolean usesItems() {
        return this != WARDROBE;
    }

    /**
     * Whether a player's wardrobe choice is read, and worth storing.
     *
     * @return {@code true} when the wardrobe means something on this server
     */
    public boolean usesWardrobe() {
        return this != ITEM;
    }
}
