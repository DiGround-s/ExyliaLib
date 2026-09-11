package net.exylia.lib.api.survival;

/**
 * What became of a block handed to a mine through
 * {@link SurvivalService#breakMineBlock(org.bukkit.entity.Player, org.bukkit.block.Block)}.
 *
 * <p>Only {@link #UNCLAIMED} leaves the block to the caller. Every other value
 * means a mine owns it, and the caller must not break it any other way: a mine
 * gates, pays out and regenerates its own blocks, so breaking one behind its
 * back hands out vanilla drops and leaves a hole the mine never refills.
 *
 * @since 1.2.0
 */
public enum MineBreakResult {

    /** The mine broke it, with the drops, loot and regeneration a player's own swing gets. */
    BROKEN,

    /**
     * No mine owns it: it is outside every mine, inside one that does not manage
     * that material and leaves it free to break, or the mines module is off. The
     * caller breaks it its own way, under its own protection checks.
     */
    UNCLAIMED,

    /** The player does not hold the permission the mine names. */
    NO_PERMISSION,

    /** The mine does not manage that material and forbids breaking anything else inside it. */
    RESTRICTED,

    /** A handler cancelled the {@link net.exylia.lib.api.survival.event.MineBlockBreakEvent}. */
    CANCELLED
}
