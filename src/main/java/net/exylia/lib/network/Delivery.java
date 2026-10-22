package net.exylia.lib.network;

/**
 * What became of a {@link PluginNetwork#send send}.
 *
 * <p>Pub/sub has no receipt: {@link #SENT} means the message left, not that a
 * handler read it. A server that is restarting misses it.
 *
 * @since 1.347.0
 */
public enum Delivery {
    /** Published to the network, or delivered here when the target is this server. */
    SENT,
    /** The target is another server and nothing reaches it: no Redis, or no proxy bridge to find a player. */
    NO_NETWORK,
    /** The player is on no server of the network. */
    NO_PLAYER
}
