package net.exylia.lib.discord;

/**
 * What became of one message handed to {@link PluginWebhooks#send}.
 *
 * <pre>{@code
 * webhooks.send(target, template, values).thenAccept(result -> {
 *     if (result == WebhookResult.INVALIDATED) clan.webhook(null);
 * });
 * }</pre>
 *
 * @since 1.245.0
 */
public enum WebhookResult {
    /** Discord accepted it. */
    SENT,
    /**
     * Never sent: a queue was full, the plugin was disabled or the server
     * stopped first.
     */
    DROPPED,
    /**
     * The webhook no longer exists or its token is wrong (Discord answered 404
     * or 401). Nothing more is sent to it; see {@link PluginWebhooks#onInvalidated}.
     */
    INVALIDATED,
    /** Discord refused it, or it could not be reached after three attempts. */
    FAILED,
    /** Discord kept rate-limiting it after three attempts. */
    RATE_LIMITED,
    /** The template is disabled, or has nothing to send. */
    SKIPPED
}
