package net.exylia.lib.api.practice;

/**
 * What a player is currently doing, as the practice plugin sees it.
 *
 * <p>The one thing every other mode on the server needs to know before it takes
 * a player anywhere. Only {@link #AVAILABLE} means the player is free; every
 * other value is something that would be broken by moving them.
 *
 * @since 1.0.0
 */
public enum PracticeState {

    /** Standing in the lobby, in nothing. Free to be taken. */
    AVAILABLE,
    /** In a party that has not queued for anything yet. */
    IN_PARTY,
    /** Waiting for a match. */
    IN_QUEUE,
    /** A match is being built around them. */
    LOADING_MATCH,
    /** Fighting. */
    IN_GAME,
    /** Watching a match. */
    SPECTATING,
    /** Editing a kit loadout. */
    EDITING_KIT,
    /** Held by another plugin, which said so by claiming them. */
    EXTERNAL,
    /** Inside an open PvP zone in the lobby world. */
    IN_PVP_ZONE
}
