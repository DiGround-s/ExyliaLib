package net.exylia.lib.discord;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The answer to {@link PluginWebhooks#verify} and {@link PluginWebhooks#ask}:
 * whether the webhook exists, and what it is called.
 *
 * <pre>{@code
 * webhooks.verify(target).thenAccept(check -> {
 *     if (check.connected()) Text.of("{success}Connected to %name%").with("%name%", check.name()).send(player);
 * });
 * }</pre>
 *
 * @param status    what Discord answered
 * @param target    the webhook asked about; {@code null} only when the player cancelled
 * @param name      the webhook's name, when connected
 * @param channelId the channel it posts to, when connected
 * @param guildId   the server it belongs to, when connected and Discord says
 * @since 1.245.0
 */
public record WebhookCheck(@NotNull Status status, @Nullable WebhookTarget target,
                           @Nullable String name, @Nullable String channelId,
                           @Nullable String guildId) {

    /** Returns whether the webhook exists and the token is right. */
    public boolean connected() {
        return status == Status.CONNECTED;
    }

    /**
     * Creates an answer that carries no webhook details.
     *
     * @param status what happened
     * @param target the webhook asked about, if any
     * @return the answer
     */
    public static @NotNull WebhookCheck of(@NotNull Status status, @Nullable WebhookTarget target) {
        return new WebhookCheck(status, target, null, null, null);
    }

    /**
     * What a check found.
     *
     * @since 1.245.0
     */
    public enum Status {
        /** It exists; {@link #name()} and {@link #channelId()} are set. */
        CONNECTED,
        /** No such webhook: it was deleted, or never existed. */
        NOT_FOUND,
        /** The webhook exists but the token is wrong. */
        UNAUTHORIZED,
        /** Discord is rate-limiting this server; try again in a moment. */
        RATE_LIMITED,
        /** Discord could not be reached, or answered something unexpected. */
        UNREACHABLE,
        /** The player cancelled, timed out or left before answering. */
        CANCELLED
    }
}
