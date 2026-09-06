package net.exylia.lib.api.chatcosmetics;

import org.jetbrains.annotations.NotNull;

/**
 * Where a message goes.
 *
 * <p>Read from the chat's config file and replaced whole on a reload, so this
 * is a snapshot: keep the {@link #id()} rather than the record if you need to
 * look the channel up again.
 *
 * @param id          the channel id, what every method here takes
 * @param type        who reads it
 * @param name        what players see it called
 * @param permission  needed to talk and to read; empty means everybody
 * @param prefix      a character typed in front of a message to send it here
 *                    from any other channel; empty for none
 * @param radius      blocks, for {@link ChannelType#LOCAL}; other types ignore it
 * @param crossServer whether the message travels to the other servers
 * @since 1.0.0
 */
public record ChatChannel(
        @NotNull String id,
        @NotNull ChannelType type,
        @NotNull String name,
        @NotNull String permission,
        @NotNull String prefix,
        double radius,
        boolean crossServer) {

    /**
     * Whether anybody may use this channel.
     *
     * @return {@code true} when it needs no permission
     */
    public boolean open() {
        return permission.isEmpty();
    }
}
