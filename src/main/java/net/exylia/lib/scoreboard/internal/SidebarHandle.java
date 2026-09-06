package net.exylia.lib.scoreboard.internal;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The board as the client sees it, without exposing the shaded library.
 *
 * <p>Everything the module needs from a sidebar is here, so the scoreboard
 * engine and its tests never touch the relocated scoreboard-library types: the
 * only class that does is {@link MegavexSidebar}.
 */
public interface SidebarHandle {

    /** The most lines a client sidebar can hold. */
    int MAX_LINES = 15;

    /** Makes the viewer see the sidebar. */
    void show();

    /** Makes the viewer stop seeing it, keeping its contents. */
    void hide();

    /**
     * Takes the client's sidebar slot back without re-sending the board.
     *
     * <p>Another plugin — or another scoreboard library on the same server —
     * can claim the slot at any time, and nothing tells us when it happens.
     * The fallback re-sends everything; a server with PacketEvents sends one
     * packet and the player sees nothing blink.
     */
    default void reclaim() {
        hide();
        show();
    }

    /** Releases the sidebar for good. */
    void close();

    /** Returns whether {@link #close()} already ran. */
    boolean closed();

    /** Sets the title. */
    void title(Component title);

    /**
     * Sets a line, or clears it when {@code line} is {@code null}.
     *
     * @param index the line position, 0 at the top
     * @param line  the content, or {@code null} to remove the line
     */
    void line(int index, @Nullable Component line);
}
