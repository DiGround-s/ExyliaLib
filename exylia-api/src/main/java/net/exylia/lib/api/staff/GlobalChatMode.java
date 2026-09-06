package net.exylia.lib.api.staff;

/**
 * How far a staff member's chat reaches.
 *
 * <p>Each step includes the one before it, so a cycle only ever adds reach.
 * The reason a chat plugin cares is {@link #bypasses()}: a staff member above
 * {@link #OFF} reads the chats a match, an arena or an event isolates, so a
 * plugin that hides lines from other players has to let these through.
 *
 * @since 1.0.0
 */
public enum GlobalChatMode {

    /** Only the chat around them, isolation included. */
    OFF,

    /** Every chat on this server, whatever isolated it. */
    GLOBAL,

    /** That, plus every chat line of every other server on the network. */
    NETWORK;

    /**
     * Whether this mode reads past another plugin's chat isolation.
     *
     * @return {@code true} for anything but {@link #OFF}
     */
    public boolean bypasses() {
        return this != OFF;
    }
}
