package net.exylia.lib.guide.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything one plugin shows one player, driven by one timer on that player.
 *
 * <h2>Why a timer on the player</h2>
 * Guidance reads where the player is and which way they look, and on Folia
 * only the player's own region thread may read that. An entity timer runs
 * there, follows the player across regions, and stops by itself when they
 * leave, so the session never reads a player it does not own and never
 * outlives one. On Paper every entity timer of a plugin shares one server task.
 */
final class ViewerSession {

    /** Two ticks: a tenth of a second is faster than anybody notices a panel catching up. */
    private static final long PERIOD_TICKS = 2L;

    private final Plugin plugin;
    private final Player player;
    private final List<Piece> pieces = new CopyOnWriteArrayList<>();
    private TaskHandle timer;
    private long ticks;

    ViewerSession(Plugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    Plugin plugin() {
        return plugin;
    }

    Player player() {
        return player;
    }

    TaskScheduler tasks() {
        return Tasks.of(plugin);
    }

    synchronized void add(Piece piece) {
        pieces.add(piece);
        if (timer == null || timer.isCancelled()) {
            timer = tasks().runAtEntityTimer(player, 1L, PERIOD_TICKS, this::tick);
        }
    }

    void detach(Piece piece) {
        pieces.remove(piece);
    }

    private void tick() {
        if (pieces.isEmpty()) {
            // Nothing left to draw: the timer goes until something is added.
            synchronized (this) {
                if (pieces.isEmpty() && timer != null) {
                    timer.cancel();
                    timer = null;
                }
            }
            return;
        }
        ticks += PERIOD_TICKS;
        for (Piece piece : pieces) {
            try {
                piece.tick(player, ticks);
            } catch (Throwable failed) {
                // One broken piece must not take the player's other guidance
                // with it; it goes, and says why once.
                plugin.getLogger().warning("A guide piece for " + player.getName()
                        + " could not be drawn and was removed: " + failed);
                piece.erase(true);
                pieces.remove(piece);
            }
        }
    }

    /** The client dropped everything; every piece draws itself again next tick. */
    void forgetClient() {
        for (Piece piece : pieces) {
            piece.lost();
        }
    }

    /** Ends the session: erases every piece (when the client is still there) and stops the timer. */
    synchronized void close(boolean send) {
        for (Piece piece : pieces) {
            piece.erase(send);
        }
        pieces.clear();
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    boolean isEmpty() {
        return pieces.isEmpty();
    }
}
