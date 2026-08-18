package net.exylia.lib.input.internal;

/**
 * Identifies the mechanism that asks a player for input.
 *
 * <p>The declaration order is the library's default fallback order. A request
 * may provide a narrower order, but it still names mechanisms rather than
 * implementation classes so optional integrations can be absent without
 * linking failures.
 *
 * @since 1.31.0
 */
public enum TransportKind {

    /**
     * Chosen first when the client supports native dialog packets, because a
     * purpose-built form keeps validation and cancellation visible without
     * occupying chat or a container.
     */
    DIALOG,

    /**
     * Chosen for Bedrock players when the bridge exposes native forms, because
     * a Java inventory or signed-chat prompt is a poor fit for their protocol.
     */
    BEDROCK,

    /**
     * Chosen for searchable choices when an anvil-style text field is
     * available, because filtering a long list prevents paging through many
     * inventory screens.
     */
    ANVIL_SEARCH,

    /**
     * Chosen when a structured inventory can represent the request but no more
     * capable client form is available, because buttons provide discoverable
     * choices without requiring typed commands.
     */
    MENU,

    /**
     * Chosen as the final broadly compatible fallback, because every online
     * Java player can answer in chat even when packet or bridge integrations
     * are unavailable.
     */
    CHAT
}
