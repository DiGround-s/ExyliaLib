package net.exylia.lib.chat.internal;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.exylia.lib.debug.Debug;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Single Bukkit listener taking the receivers no rule allows off a message.
 *
 * <p>Only the modern event is handled. When a plugin still listens to the
 * legacy one Paper runs it first and then hands its recipients to the modern
 * event's viewers, so the audience trimmed here is the audience delivered
 * either way, and this plugin never turns the legacy path on.
 *
 * <p>It also puts the plugins' chat tags before each line, around the renderer
 * the chat plugin left (see {@link #onRender}).
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

    /**
     * Puts the plugins' tags before the line. {@code MONITOR} because chat plugins set their renderer at
     * any priority up to {@code HIGHEST}: only here is the renderer the final one. It is wrapped, not
     * replaced, so the line the chat plugin built is delivered unchanged after the tags.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRender(AsyncChatEvent event) {
        Component tags = ChatRuntime.tags(event.getPlayer());
        if (tags == null) {
            return;
        }
        ChatRenderer inner = event.renderer();
        event.renderer((source, name, message, viewer) -> tags.append(inner.render(source, name, message, viewer)));
    }

    /** A bypass lasts as long as the player is here; nothing outlives a quit. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ChatRuntime.bypass(event.getPlayer().getUniqueId(), false);
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
