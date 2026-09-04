package net.exylia.lib.proxy;

import org.jetbrains.annotations.NotNull;

/**
 * What the proxy answered to one request.
 *
 * <p>Only the first four statuses ever travel on the wire; the rest are what
 * this server concluded on its own when no answer came, and they say why.
 *
 * @param status what happened
 * @param detail what the proxy, or this server, had to say about it
 * @since 1.101.0
 */
public record ProxyReply(@NotNull Status status, @NotNull String detail) {

    /** How a request ended. */
    public enum Status {

        /** The module did what was asked. */
        OK,

        /** The module understood the request and refused it, with a reason. */
        REJECTED,

        /** The module threw. */
        FAILED,

        /** The proxy has no module by that name; it is older, or the name is wrong. */
        UNKNOWN_MODULE,

        /** There is no bridge to talk to on this server. */
        NO_BRIDGE,

        /** The proxy did not answer in time. */
        TIMEOUT,

        /** The player carrying the request left before it was answered. */
        NO_PLAYER
    }

    /** Returns whether the module did what was asked. */
    public boolean isOk() {
        return status == Status.OK;
    }

    /** Returns whether the request reached the proxy at all. */
    public boolean reachedProxy() {
        return status.ordinal() <= Status.UNKNOWN_MODULE.ordinal();
    }

    /** A reply for a wire status byte; anything unknown reads as {@link Status#FAILED}. */
    public static @NotNull ProxyReply ofWire(int code, @NotNull String detail) {
        Status status = code >= 0 && code < 4 ? Status.values()[code] : Status.FAILED;
        return new ProxyReply(status, detail);
    }
}
