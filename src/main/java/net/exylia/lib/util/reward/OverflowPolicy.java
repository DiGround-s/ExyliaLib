package net.exylia.lib.util.reward;

/**
 * What happens to an item a player has no room for.
 *
 * <p>ExyliaCommons discarded the leftovers {@code Inventory.addItem} hands back,
 * so an item that did not fit was destroyed with no message, no log and no
 * failure. A player who won a full-inventory reward simply never received it and
 * had no way to know.
 *
 * <p>No policy here reproduces that. Two of them keep the item and the third
 * refuses it loudly; a plugin that asked to keep it does not lose it because a
 * database was unreachable.
 *
 * @since 1.34.0
 */
public enum OverflowPolicy {

    /**
     * Drops what does not fit at the player's feet.
     *
     * <p>The default, because a player standing on their reward can pick it up
     * and one who lost it silently cannot.
     */
    DROP,

    /**
     * Keeps what does not fit until there is room.
     *
     * <p>The reward is stored the same way an offline player's is, and handed
     * over on their next join. Costs a database row; worth it for something rare.
     *
     * <p>Needs a {@link PendingRewards} store. Without one, or if that store
     * refuses the write, the item is dropped and the trouble reported: asking to
     * queue is asking not to lose it, and an unreachable database does not change
     * what was asked for.
     *
     * @see PluginRewards#giveLater
     */
    QUEUE,

    /**
     * Gives up and reports it.
     *
     * <p>The result is {@link RewardOutcome#NO_ROOM}, so the caller can tell the
     * player to make space and try again. Nothing is dropped and nothing is
     * stored.
     */
    FAIL
}
