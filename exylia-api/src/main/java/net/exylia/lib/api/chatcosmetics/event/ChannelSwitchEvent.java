package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.ChatChannel;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player choosing the channel their plain messages go to.
 *
 * <p>Cancel to keep them where they are. Fired before anything is written, on
 * the player's thread, so a listener may talk to them.
 *
 * @since 1.0.0
 */
public final class ChannelSwitchEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ChatChannel from;
    private final ChatChannel to;
    private boolean cancelled;

    /**
     * @param player who is switching
     * @param from   where they were
     * @param to     where they are going
     */
    public ChannelSwitchEvent(@NotNull Player player, @NotNull ChatChannel from, @NotNull ChatChannel to) {
        this.player = player;
        this.from = from;
        this.to = to;
    }

    /**
     * Who is switching.
     *
     * @return the player
     */
    public @NotNull Player player() {
        return player;
    }

    /**
     * Where they were.
     *
     * <p>A player who never chose a channel counts as leaving global rather
     * than leaving nothing, so this is always a real channel.
     *
     * @return the channel they are leaving
     */
    public @NotNull ChatChannel from() {
        return from;
    }

    /**
     * Where they are going.
     *
     * @return the channel they are switching to
     */
    public @NotNull ChatChannel to() {
        return to;
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
