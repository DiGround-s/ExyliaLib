package net.exylia.lib.npc;

/**
 * Where a placed NPC turns its head.
 *
 * @since 1.291.0
 */
public enum NpcLook {

    /** Always the way its location faces. */
    FIXED,

    /** At the nearest viewer inside the look range; everybody sees the same head. */
    NEAREST,

    /**
     * At each viewer inside the look range, from that viewer's own point of view.
     *
     * <p>Every player near it sees it looking at them. Nothing on the server turns; each
     * client is told its own rotation.
     */
    EACH_VIEWER
}
