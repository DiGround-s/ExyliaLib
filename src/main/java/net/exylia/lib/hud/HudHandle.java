package net.exylia.lib.hud;

/**
 * One element on a player's HUD. Never {@code null}: without PacketEvents, or for an offline player, it works and
 * draws nothing.
 *
 * @since 1.566.0
 */
public interface HudHandle {

    /** Reads the element's text and condition again on the next draw, instead of waiting for its period. Any thread. */
    void refresh();

    /** Takes it off the screen, sliding out. Any thread; a second call does nothing. */
    void remove();

    /** Whether it is still on the player's HUD (shown, or waiting on its condition). */
    boolean isShowing();
}
