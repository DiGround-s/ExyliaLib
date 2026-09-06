package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.ChatChannel;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;

/**
 * A chat message that passed every filter and is about to be delivered.
 *
 * <p>The last look before the viewers get it, which makes it the place to log
 * chat, to mirror it somewhere, or to take a reader out. It fires after the
 * filters and after the sender's cosmetics were applied, so what you see here
 * is what will be said, not what was typed — {@link #raw()} is still there for
 * the difference.
 *
 * <h2>What you may change</h2>
 * {@link #viewers()} is the live set: remove a player and they do not see the
 * message. Nobody may be added, because a viewer that was never in the set was
 * excluded for a reason — a channel permission, an ignore, a world.
 *
 * <p>{@link #line(Component)} replaces the whole rendered line for everybody.
 * Use it to reformat, not to smuggle text past the filter that already ran.
 * Cancelling drops the message for everybody, including the sender.
 *
 * <p>Fired on the chat thread, which is not the main thread: read what you are
 * handed and schedule anything that touches the world.
 *
 * @since 1.0.0
 */
public final class ChatMessageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final String raw;
    private final String text;
    private final ChatChannel channel;
    private final Set<Player> viewers;
    private @Nullable Component line;
    private boolean cancelled;

    /**
     * @param sender  who is talking
     * @param raw     what they typed
     * @param text    what the filters left of it
     * @param channel where it is going
     * @param viewers the live set of who will see it
     * @param line    the rendered line, or {@code null} when the format is
     *                built per reader
     */
    public ChatMessageEvent(@NotNull Player sender, @NotNull String raw, @NotNull String text,
                            @NotNull ChatChannel channel, @NotNull Set<Player> viewers,
                            @Nullable Component line) {
        super(true);
        this.sender = sender;
        this.raw = raw;
        this.text = text;
        this.channel = channel;
        this.viewers = viewers;
        this.line = line;
    }

    /**
     * Who is talking.
     *
     * @return the sender
     */
    public @NotNull Player sender() {
        return sender;
    }

    /**
     * What was typed, before anything touched it.
     *
     * @return the raw message
     */
    public @NotNull String raw() {
        return raw;
    }

    /**
     * What is left after the filters rewrote it — the text the cosmetics were
     * applied to.
     *
     * @return the filtered message
     */
    public @NotNull String text() {
        return text;
    }

    /**
     * Where the message is going.
     *
     * @return the channel
     */
    public @NotNull ChatChannel channel() {
        return channel;
    }

    /**
     * Who will see the message.
     *
     * <p>The live set: removing a player here takes them out of the delivery.
     *
     * @return the viewers, mutable
     */
    public @NotNull Set<Player> viewers() {
        return viewers;
    }

    /**
     * The whole rendered line.
     *
     * <p>Empty when the chosen format depends on the reader and so is built
     * once per viewer; there is no single line to show you in that case.
     *
     * @return the line, or empty when it is rendered per reader
     */
    public @NotNull Optional<Component> line() {
        return Optional.ofNullable(line);
    }

    /**
     * Replaces the whole line, for everybody.
     *
     * <p>Setting one on a message whose format was per reader collapses it to
     * this single line, which is the point: you are taking the rendering over.
     *
     * @param line the line to deliver
     */
    public void line(@NotNull Component line) {
        this.line = line;
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
