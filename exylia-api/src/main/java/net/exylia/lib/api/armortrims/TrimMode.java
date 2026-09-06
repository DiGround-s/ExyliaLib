package net.exylia.lib.api.armortrims;

/**
 * Where the trim a piece of armor is drawn with comes from.
 *
 * <p>The two sources answer different kinds of server. A trim written onto the
 * armor travels with it: it can be traded, lost on death and sold as an item,
 * which is what a survival or economy server wants. A trim chosen in the menu
 * belongs to the player instead, and lands on whatever they equip, which is
 * what a practice or PvP server wants.
 *
 * <p>Worth asking before writing anything: half of {@link ArmorTrimService}
 * does nothing on a server whose mode does not use that half, and reports so
 * rather than failing.
 *
 * @since 1.0.0
 */
public enum TrimMode {

    /** Only what a trim item wrote onto the armor. */
    ITEM,

    /** Only what the wearer chose in the menu; trim items do nothing. */
    MENU,

    /** The armor's own trim, and the menu's choice for armor that carries none. */
    BOTH;

    /**
     * Whether a trim item can be applied, and whether armor's own trim is read.
     *
     * @return {@code true} when trim items mean something on this server
     */
    public boolean usesItems() {
        return this != MENU;
    }

    /**
     * Whether a player's chosen trim is read, and worth storing.
     *
     * @return {@code true} when the menu's choice means something on this server
     */
    public boolean usesMenu() {
        return this != ITEM;
    }
}
