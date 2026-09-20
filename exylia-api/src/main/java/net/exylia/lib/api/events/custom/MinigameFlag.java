package net.exylia.lib.api.events.custom;

/**
 * What a minigame allows inside its arena while it is being played.
 *
 * <p>Set through {@link MinigameArena#flag(MinigameFlag, boolean)}, usually
 * once in {@link MinigameHandler#onGameStart(MinigameArena)}. ExyliaEvents
 * enforces them for every player the run holds, so a handler never has to
 * listen for the block break or the hit itself.
 *
 * <p>Everything a player can ordinarily do starts allowed; every restriction
 * starts off.
 *
 * @since 1.7.0
 */
public enum MinigameFlag {

    /** Whether players may hurt each other. */
    PVP,

    /** Whether a block may be placed. */
    BUILD,

    /** Whether a block may be broken. */
    BREAK,

    /** Whether a block may be right-clicked. */
    INTERACT,

    /** Whether an item may be dropped. */
    ITEM_DROP,

    /** Whether an item may be picked up. */
    ITEM_PICKUP,

    /** Whether falling hurts. */
    FALL_DAMAGE,

    /** Whether only blocks a player placed themselves may be broken. */
    PLAYER_BUILD_ONLY,

    /** Whether only the materials given to {@link MinigameArena#breakable} may be broken. */
    BREAKABLE_BLOCKS_ONLY,

    /** Whether only the materials given to {@link MinigameArena#placeable} may be placed. */
    ALLOWED_BLOCKS_ONLY,

    /** Whether a placed block disappears again on its own. */
    TEMPORARY_BLOCKS,

    /** Whether a block that disappeared is handed back to whoever placed it. */
    RE_GIVE_BLOCKS
}
