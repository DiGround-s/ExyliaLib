package net.exylia.lib.scoreboard.internal;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.scoreboard.ScoreboardManager;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands TAB's sidebar down while a board of ours is up.
 *
 * <p>TAB is the sidebar most servers already run, and it does not know about
 * anybody else's: it claims the slot again on its own schedule, and a board
 * shown over it is lost the next time it does. Asking it to stop showing its
 * own is the only way two sidebars share a player without fighting — and the
 * only way TAB's board comes straight back when ours ends, instead of when TAB
 * next happens to re-send it.
 *
 * <p>This is the one class that names TAB's types. It is loaded only after
 * {@link BoardManager} has seen the plugin enabled, so a server without TAB
 * never resolves them.
 */
final class TabHook {

    /** Players whose TAB sidebar we turned off, so only those get it back. */
    private static final Set<UUID> STOOD_DOWN = ConcurrentHashMap.newKeySet();
    private static volatile boolean listening;

    private TabHook() {
    }

    /** Turns TAB's sidebar off for a player, remembering that it was on. */
    static void standDown(Player player) {
        ScoreboardManager manager = manager();
        TabPlayer tabPlayer = tabPlayer(player);
        if (manager == null || tabPlayer == null || !manager.hasScoreboardVisible(tabPlayer)) {
            return;
        }
        listen();
        STOOD_DOWN.add(player.getUniqueId());
        manager.setScoreboardVisible(tabPlayer, false, false);
    }

    /** Gives TAB's sidebar back, if it was ours to take away. */
    static void restore(Player player) {
        if (!STOOD_DOWN.remove(player.getUniqueId())) {
            return;
        }
        ScoreboardManager manager = manager();
        TabPlayer tabPlayer = tabPlayer(player);
        if (manager != null && tabPlayer != null) {
            manager.setScoreboardVisible(tabPlayer, true, false);
        }
    }

    /**
     * Gives TAB's sidebar back to a player who is leaving.
     *
     * <p>TAB remembers a hidden sidebar across sessions when the server runs
     * {@code remember-toggle-choice}, so a player who quits while it is down
     * would never see it again. When TAB has already dropped the player, the
     * mark is kept and the sidebar comes back the next time TAB loads them.
     */
    static void left(Player player) {
        if (!STOOD_DOWN.contains(player.getUniqueId())) {
            return;
        }
        ScoreboardManager manager = manager();
        TabPlayer tabPlayer = tabPlayer(player);
        if (manager != null && tabPlayer != null) {
            STOOD_DOWN.remove(player.getUniqueId());
            manager.setScoreboardVisible(tabPlayer, true, false);
        }
    }

    /**
     * Gives the sidebar back to a player TAB loads again after leaving while it
     * was down, unless a board of ours is up again by then.
     *
     * <p>ponytail: the marks live in memory, so a server that stops mid-event
     * still leaves those players' sidebars off; persist them if that shows up.
     */
    private static void listen() {
        if (listening) {
            return;
        }
        TabAPI api = TabAPI.getInstance();
        EventBus bus = api == null ? null : api.getEventBus();
        if (bus == null) {
            return;
        }
        listening = true;
        bus.register(PlayerLoadEvent.class, event -> {
            TabPlayer tabPlayer = event.getPlayer();
            UUID id = tabPlayer.getUniqueId();
            if (BoardManager.hasBoard(id) || !STOOD_DOWN.remove(id)) {
                return;
            }
            ScoreboardManager manager = manager();
            if (manager != null) {
                manager.setScoreboardVisible(tabPlayer, true, false);
            }
        });
    }

    private static ScoreboardManager manager() {
        TabAPI api = TabAPI.getInstance();
        return api == null ? null : api.getScoreboardManager();
    }

    private static TabPlayer tabPlayer(Player player) {
        TabAPI api = TabAPI.getInstance();
        // Null while TAB is still loading the player, and for a player who
        // left; both mean there is no TAB sidebar to argue with.
        return api == null ? null : api.getPlayer(player.getUniqueId());
    }
}
