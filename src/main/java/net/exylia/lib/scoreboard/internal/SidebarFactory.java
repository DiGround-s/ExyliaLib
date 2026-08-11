package net.exylia.lib.scoreboard.internal;

import org.bukkit.entity.Player;

/**
 * Creates sidebars for players.
 *
 * <p>The real one is built by {@link SidebarLibrary} from the shaded
 * scoreboard-library; tests hand in a fake, which is what lets the engine's
 * behaviour be tested without a server.
 */
@FunctionalInterface
public interface SidebarFactory {

    /**
     * Creates a sidebar for a player.
     *
     * @param player   the viewer
     * @param maxLines how many lines it can hold
     * @return the handle, never {@code null}
     */
    SidebarHandle create(Player player, int maxLines);
}
