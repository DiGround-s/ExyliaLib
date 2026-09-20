package net.exylia.lib.api.events.custom;

/**
 * Whether a place an arena marks is a box or a single block.
 *
 * @since 1.7.0
 */
public enum MinigameMarkerKind {

    /** Two corners an admin selects, such as a goal or a capture zone. */
    AREA,

    /** One block an admin stands on, such as a flag stand or a start line. */
    POINT
}
