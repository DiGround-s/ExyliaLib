package net.exylia.lib.camera;

/**
 * A part of a player's screen that {@link PluginCameras#hideHud} can clear
 * while they watch a cinematic.
 *
 * <p>The tab list is not one: it only shows while its key is held, and a
 * player looking through a camera is not holding it by accident.
 *
 * @since 1.397.0
 */
public enum Hud {
    /** Every boss bar, from any plugin; the exact set and state comes back after. */
    BOSSBAR,
    /** The sidebar; the one showing comes back after. */
    SCOREBOARD,
    /** Chat and system messages: held back, then delivered in order (up to 100). */
    CHAT,
    /** Action bar lines: dropped, they are about the moment. */
    ACTIONBAR,
    /** Titles and subtitles from anything else: cleared and dropped. */
    TITLES
}
