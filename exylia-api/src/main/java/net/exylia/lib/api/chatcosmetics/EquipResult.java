package net.exylia.lib.api.chatcosmetics;

/**
 * What happened when a player tried to wear something.
 *
 * <p>Returned rather than thrown: none of these is a programming error, they
 * are the ordinary answers a menu turns into a message.
 *
 * @since 1.0.0
 */
public enum EquipResult {

    /** It is on. */
    EQUIPPED,

    /** A set member that was worn is worn no longer, because equipping toggles. */
    UNEQUIPPED,

    /** The player does not own it. */
    NOT_OWNED,

    /** No cosmetic goes by that key. */
    UNKNOWN,

    /** A listener cancelled it. */
    CANCELLED,

    /** The player's row is not in memory yet; try again in a moment. */
    NOT_LOADED;

    /**
     * Whether the player is wearing something different than before.
     *
     * @return {@code true} for {@link #EQUIPPED} and {@link #UNEQUIPPED}
     */
    public boolean changed() {
        return this == EQUIPPED || this == UNEQUIPPED;
    }
}
