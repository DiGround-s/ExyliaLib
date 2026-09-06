package net.exylia.lib.api.chatcosmetics.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A whisper about to reach somebody on this server.
 *
 * <p>Fired after the receiver's own settings were honoured — closed messages
 * and ignores have already turned the whisper away by the time you see it — so
 * this is about your rules, not theirs.
 *
 * <p>Cancelling delivers nothing and tells the sender nothing; explain it
 * yourself if they need to know. The text is read only: a whisper is between
 * two people and rewriting it under them would be worse than refusing it.
 *
 * <p>Fired on whichever thread the whisper arrived on, which for a chat-driven
 * one is not the main thread.
 *
 * @since 1.0.0
 */
public final class PrivateMessageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final Player receiver;
    private final String text;
    private boolean cancelled;

    /**
     * @param sender   who is whispering
     * @param receiver who is being whispered to
     * @param text     what was typed
     */
    public PrivateMessageEvent(@NotNull Player sender, @NotNull Player receiver, @NotNull String text) {
        super(!Bukkit.isPrimaryThread());
        this.sender = sender;
        this.receiver = receiver;
        this.text = text;
    }

    /**
     * Who is whispering.
     *
     * @return the sender
     */
    public @NotNull Player sender() {
        return sender;
    }

    /**
     * Who is being whispered to.
     *
     * @return the receiver
     */
    public @NotNull Player receiver() {
        return receiver;
    }

    /**
     * As typed, before cosmetics.
     *
     * @return the message text
     */
    public @NotNull String text() {
        return text;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return the handler list Bukkit registers against
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
