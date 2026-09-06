package net.exylia.lib.api.chatcosmetics;

/**
 * Who a channel's messages reach.
 *
 * @since 1.0.0
 */
public enum ChannelType {

    /** Everybody online. */
    GLOBAL,

    /** Everybody in the sender's world within the channel's radius. */
    LOCAL,

    /** Everybody holding the channel's permission. */
    CUSTOM
}
