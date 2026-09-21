package net.exylia.lib.util.crate;

/**
 * What a crate hands over when it lands on a reward.
 *
 * <p>Two different economies. {@link #UNLOCK} keeps the reward on the player's
 * account, where it cannot be traded or lost; {@link #ITEM} keeps it in their
 * inventory as the plugin's token item, where it can be sold and given away.
 * Nothing is unlocked under {@code ITEM}, so no opening is ever a duplicate
 * and every key pays out.
 *
 * <p>A plugin whose catalogue has no token item ({@link CrateCatalogue#token}
 * answers {@code null}) treats {@code ITEM} and {@code BOTH} as {@code UNLOCK}:
 * a crate that handed over nothing would be a key spent on air.
 *
 * @since 1.189.0
 */
public enum CrateReward {

    /** Unlocked on the account. Cannot be traded. */
    UNLOCK,
    /** Only the token item. Nothing is unlocked. */
    ITEM,
    /** Unlocked on the account and handed over as a token as well. */
    BOTH;

    /** Whether the reward is written to the player's account. */
    public boolean unlocks() {
        return this != ITEM;
    }

    /** Whether the reward's token item is handed over. */
    public boolean givesItem() {
        return this != UNLOCK;
    }
}
