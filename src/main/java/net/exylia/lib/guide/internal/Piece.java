package net.exylia.lib.guide.internal;

import net.exylia.lib.guide.GuideHandle;
import org.bukkit.entity.Player;

/**
 * One piece of guidance, driven by its player's session.
 *
 * <p>Every packet a piece sends goes out from {@link #tick} or {@link #erase},
 * both under the piece's own lock, so a {@code remove()} from another thread
 * can never land between a tick deciding to spawn something and spawning it.
 * Handle methods only change fields; the next tick sends what changed.
 */
abstract class Piece implements GuideHandle {

    final ViewerSession session;
    private volatile boolean removed;

    Piece(ViewerSession session) {
        this.session = session;
        // No session: the server cannot draw this, so the handle is inert.
        this.removed = session == null;
    }

    /** Brings the client up to date. On the player's own thread, every couple of ticks. */
    abstract void draw(Player player, long tick);

    /** Takes everything this piece put on the client off it. */
    abstract void clear(Player player);

    /**
     * The client threw everything away by itself (a world change, a respawn):
     * the next tick draws it all again.
     */
    abstract void forget();

    /**
     * Destroys what the client may still hold (harmless if it already threw
     * it away) and starts again from nothing on the next tick.
     */
    final synchronized void lost() {
        if (removed) {
            return;
        }
        try {
            clear(session.player());
        } catch (Throwable ignored) {
            // Whatever could not be sent is gone from the client anyway.
        }
        forget();
    }

    final synchronized void tick(Player player, long tick) {
        if (!removed) {
            draw(player, tick);
        }
    }

    /** Erases it from the client, once. Called by the session on release. */
    final synchronized void erase(boolean send) {
        if (send && session != null) {
            try {
                clear(session.player());
            } catch (Throwable ignored) {
                // A player who left between the decision and the send.
            }
        }
        removed = true;
    }

    @Override
    public final void remove() {
        if (removed) {
            return;
        }
        erase(true);
        session.detach(this);
    }

    @Override
    public boolean isShowing() {
        return !removed && session.player().isOnline();
    }

    boolean removed() {
        return removed;
    }
}
