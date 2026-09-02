package net.exylia.lib.chat.internal;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.exylia.lib.debug.Debug;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

/**
 * Single Bukkit listener taking the receivers no rule allows off a message.
 *
 * <p>Both chat events are handled because a server's chat plugin decides which
 * one carries the message: a renderer-based plugin leaves the modern event's
 * viewers to the server, while an older one edits the legacy recipients. The
 * two describe the same set on Paper, so filtering both filters once.
 *
 * <p>{@code HIGHEST} lets the chat plugin build its message first and still
 * runs before delivery. Cancelled events are skipped: a message nobody
 * receives has no audience to trim.
 */
public final class ChatListener implements Listener {

    /** Whoever hands us an audience we cannot edit is reported once. */
    private static volatile boolean warned;

    private final Plugin lib;

    public ChatListener(Plugin lib) {
        this.lib = lib;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (ChatRuntime.idle()) {
            return;
        }
        Player speaker = event.getPlayer();
        // Non-player audiences stay: the console line is the server's log of
        // what was said, not somebody's copy of it.
        remove(() -> event.viewers().removeIf(viewer ->
                viewer instanceof Player listener && !ChatRuntime.canHear(listener, speaker)));
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        if (ChatRuntime.idle()) {
            return;
        }
        Player speaker = event.getPlayer();
        remove(() -> event.getRecipients().removeIf(listener ->
                !ChatRuntime.canHear(listener, speaker)));
    }

    /**
     * Runs a removal, and says so once if the audience refuses it.
     *
     * <p>A plugin is allowed to hand out an unmodifiable audience. When one
     * does, the message goes out to everybody: failing open keeps chat working
     * and the warning says why isolation is not.
     */
    private void remove(Runnable removal) {
        try {
            removal.run();
        } catch (UnsupportedOperationException error) {
            if (!warned) {
                warned = true;
                Debug.of(lib).warn("Another plugin owns this server's chat audience:"
                        + " chat rules cannot be applied.");
            }
        }
    }
}
