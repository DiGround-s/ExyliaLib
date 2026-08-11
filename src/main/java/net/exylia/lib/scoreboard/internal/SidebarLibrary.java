package net.exylia.lib.scoreboard.internal;

import net.megavex.scoreboardlibrary.api.ScoreboardLibrary;
import net.megavex.scoreboardlibrary.api.exception.NoPacketAdapterAvailableException;
import net.megavex.scoreboardlibrary.api.noop.NoopScoreboardLibrary;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Loads the shaded scoreboard-library and hands out sidebars.
 *
 * <p>Along with {@link MegavexSidebar} this is where the relocated
 * scoreboard-library lives: no other class in the module names its types.
 *
 * <p>A server version without a packet adapter is not an error worth killing
 * scoreboards over: the library is swapped for its no-op implementation, every
 * call keeps working, and the boards are simply invisible. That is reported
 * once at startup.
 */
public final class SidebarLibrary {

    private static ScoreboardLibrary library;
    private static boolean supported;

    private SidebarLibrary() {
    }

    /**
     * Loads the library and returns the factory that builds sidebars from it.
     *
     * <p>Called by ExyliaLib at startup. Consumers do not need this.
     *
     * @param plugin the plugin that owns the library instance
     * @param logger where a missing packet adapter is reported
     * @return the factory to hand to {@link BoardManager#init}
     */
    public static synchronized SidebarFactory load(Plugin plugin, Logger logger) {
        if (library == null) {
            ScoreboardLibrary loaded;
            boolean ok;
            try {
                loaded = ScoreboardLibrary.loadScoreboardLibrary(plugin);
                ok = true;
            } catch (NoPacketAdapterAvailableException e) {
                loaded = new NoopScoreboardLibrary();
                ok = false;
                logger.warning("No scoreboard packet adapter for this server version;"
                        + " scoreboards will not be visible.");
            } catch (Throwable t) {
                loaded = new NoopScoreboardLibrary();
                ok = false;
                logger.warning("Could not load the scoreboard library (" + t.getMessage()
                        + "); scoreboards will not be visible.");
            }
            library = loaded;
            supported = ok;
        }
        ScoreboardLibrary current = library;
        return (player, maxLines) -> new MegavexSidebar(
                current.createSidebar(Math.max(1, Math.min(maxLines, SidebarHandle.MAX_LINES)),
                        null, "exylia-" + UUID.randomUUID().toString().substring(0, 8)),
                player);
    }

    /**
     * Returns whether the server version has a packet adapter.
     *
     * <p>When {@code false} boards still work from the plugin's point of view;
     * players just do not see them.
     *
     * @return {@code true} when scoreboards actually reach the client
     */
    public static boolean isSupported() {
        return supported;
    }

    /** Closes the library. Called by ExyliaLib on shutdown. */
    public static synchronized void close() {
        if (library != null && !library.closed()) {
            library.close();
        }
        library = null;
        supported = false;
    }
}
