package net.exylia.lib.lobby;

/**
 * Why a {@link Table} ended, as {@link LobbyHooks#ended} is told: {@link #FINISHED} is the only one
 * where somebody won, so every other end gives back whatever the seats were admitted with.
 *
 * @since 1.356.0
 */
public enum TableEnd {

    /** The plugin finished it with {@link Lobby#finish}: it was played out. */
    FINISHED,
    /** Called off: by its host while open, by the plugin with {@link Lobby#cancel(Table)}, or because a player left before it started. */
    CANCELLED,
    /** Nobody took the open table, or the invitation, in time. */
    EXPIRED,
    /** The invited player said no. */
    DECLINED
}
