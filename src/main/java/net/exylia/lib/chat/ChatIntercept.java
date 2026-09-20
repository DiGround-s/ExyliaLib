package net.exylia.lib.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiPredicate;

/**
 * Takes a line out of public chat, on the chat event this server actually uses.
 *
 * <p>Staff chat, a clan channel, a freeze session, an answer to a prompt: every
 * one of them reads what a player typed and cancels the message so the server
 * never says it out loud. Doing that on Paper's {@code AsyncChatEvent} alone is
 * not enough, and this is why:
 *
 * <p>Paper only runs the modern chat event on its own when no plugin listens to
 * the legacy {@link AsyncPlayerChatEvent}. As soon as one does — DiscordSRV,
 * WorldGuard, an older chat plugin — every message goes through the legacy
 * event <em>first</em>, with all of its listeners, and only then reaches
 * {@code AsyncChatEvent} carrying whatever the legacy round decided. A plugin
 * that cancels the modern event cancels after those listeners have already read
 * the message: DiscordSRV reads it at {@code MONITOR} on the legacy event, sees
 * a message nobody cancelled, and posts the staff channel to Discord.
 *
 * <p>So the interception runs where the server's chat really is. When another
 * plugin keeps the legacy event alive, this registers there and its cancel is
 * the first thing every other listener sees, and Paper carries the cancel into
 * the modern event. When nothing uses the legacy event, this registers on
 * {@code AsyncChatEvent} and the legacy path stays unused — registering a
 * legacy listener is exactly what turns it on, and chat signatures, Velocity's
 * signed chat and component formatting are all better off without it.
 *
 * <pre>{@code
 * intercept = ChatIntercept.register(this, EventPriority.LOWEST, true, (player, message) -> {
 *     if (!staffChat.isToggled(player)) return false;
 *     staffChat.send(player, message);
 *     return true;   // taken out of public chat
 * });
 * }</pre>
 *
 * <h2>Threading</h2>
 * The interception is called on the chat thread, off the main thread. Hop with
 * {@link net.exylia.lib.task.Tasks} before touching the world.
 *
 * @since 1.185.0
 */
public final class ChatIntercept implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final EventPriority priority;
    private final boolean ignoreCancelled;
    private final BiPredicate<Player, String> intercept;
    private volatile boolean closed;

    private ChatIntercept(Plugin plugin, EventPriority priority, boolean ignoreCancelled,
                          BiPredicate<Player, String> intercept) {
        this.plugin = plugin;
        this.priority = priority;
        this.ignoreCancelled = ignoreCancelled;
        this.intercept = intercept;
    }

    /**
     * Registers an interception for this plugin.
     *
     * <p>The interception reads the player and the plain text they typed, and
     * returns whether it took the message: {@code true} cancels it, and
     * {@code false} leaves it to the server and to every other plugin.
     *
     * <p>Which event it lands on is decided one tick later, once every plugin
     * has registered its listeners; nobody is online before that tick. Cancel
     * it with {@link #close()} when the feature stops, and remember that
     * disabling the plugin unregisters it anyway.
     *
     * @param plugin          the owning plugin
     * @param priority        when it runs, relative to other chat listeners
     * @param ignoreCancelled whether a message another plugin already cancelled
     *                        is skipped; {@code false} intercepts it anyway
     * @param intercept       reads the message and returns whether it took it
     * @return the registration, so it can be closed
     */
    public static @NotNull ChatIntercept register(@NotNull Plugin plugin, @NotNull EventPriority priority,
                                                  boolean ignoreCancelled,
                                                  @NotNull BiPredicate<Player, String> intercept) {
        ChatIntercept registration = new ChatIntercept(plugin, priority, ignoreCancelled, intercept);
        Tasks.of(plugin).run(registration::install);
        return registration;
    }

    /** Stops intercepting. */
    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void install() {
        if (closed || !plugin.isEnabled()) {
            return;
        }
        if (legacyInUse()) {
            listen(AsyncPlayerChatEvent.class, (listener, event) -> {
                AsyncPlayerChatEvent chat = (AsyncPlayerChatEvent) event;
                if (intercept.test(chat.getPlayer(), chat.getMessage())) {
                    chat.setCancelled(true);
                }
            });
            return;
        }
        listen(AsyncChatEvent.class, (listener, event) -> {
            AsyncChatEvent chat = (AsyncChatEvent) event;
            if (intercept.test(chat.getPlayer(), PLAIN.serialize(chat.message()))) {
                chat.setCancelled(true);
            }
        });
    }

    private void listen(Class<? extends Event> type, EventExecutor executor) {
        Bukkit.getPluginManager().registerEvent(type, this, priority, executor, plugin, ignoreCancelled);
    }

    /**
     * Whether some other plugin listens to the legacy chat event.
     *
     * <p>Interceptions are not counted: one of them listening there is a
     * consequence of the answer, never a reason for it, and counting them would
     * make the first one to install drag every other one onto the legacy event.
     */
    @SuppressWarnings("deprecation")
    private static boolean legacyInUse() {
        for (RegisteredListener registered : AsyncPlayerChatEvent.getHandlerList().getRegisteredListeners()) {
            if (!(registered.getListener() instanceof ChatIntercept)) {
                return true;
            }
        }
        return false;
    }
}
