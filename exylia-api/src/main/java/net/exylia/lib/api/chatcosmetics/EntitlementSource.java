package net.exylia.lib.api.chatcosmetics;

/**
 * Where a grant came from.
 *
 * <p>Stored by name, and a name this version does not know reads back as
 * {@link #EXTERNAL} rather than failing — so a server running a newer plugin
 * than your integration still answers every question you ask it.
 *
 * @since 1.0.0
 */
public enum EntitlementSource {

    /** Not a stored grant at all: the permission node is checked live. */
    PERMISSION,

    /** Given by hand, through the plugin's own admin command. */
    ADMIN,

    /** Bought. */
    PURCHASE,

    /** Handed out for doing something. */
    REWARD,

    /** Won or given during an event. */
    EVENT,

    /** Earned by finishing something. */
    ACHIEVEMENT,

    /** Another plugin; its {@code sourceRef} says which. */
    EXTERNAL
}
