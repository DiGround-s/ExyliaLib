package net.exylia.lib.api.chatcosmetics.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/**
 * A message that broke the chat rules, about to cost its sender points.
 *
 * <p>The hook for an external punishment system: the rules that tripped and
 * what each was worth are here, and cancelling stops this plugin from booking
 * them so yours can decide instead.
 *
 * <p>Cancelling books nothing and nothing else: the message is still filtered
 * or blocked exactly as the rules said, only the tally is spared. Whether the
 * message survived is not part of this event because it is not part of the
 * decision.
 *
 * <p>Fired on the chat thread. Points are booked for a message that was
 * blocked too, so this can arrive for a line nobody ever read.
 *
 * @since 1.0.0
 */
public final class ChatInfractionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String raw;
    private final int points;
    private final Map<String, Integer> rules;
    private boolean cancelled;

    /**
     * @param player who sent it
     * @param raw    what they typed
     * @param points what the message is about to cost them
     * @param rules  which rules tripped and what each cost
     */
    public ChatInfractionEvent(@NotNull Player player, @NotNull String raw, int points,
                               @NotNull Map<String, Integer> rules) {
        super(true);
        this.player = player;
        this.raw = raw;
        this.points = points;
        this.rules = Map.copyOf(rules);
    }

    /**
     * Who sent the message.
     *
     * @return the sender
     */
    public @NotNull Player player() {
        return player;
    }

    /**
     * What they typed, before the filters rewrote or masked any of it.
     *
     * @return the raw message
     */
    public @NotNull String raw() {
        return raw;
    }

    /**
     * What this message is about to cost them.
     *
     * @return the points, the total of {@link #rules()}
     */
    public int points() {
        return points;
    }

    /**
     * Which rules tripped and what each cost.
     *
     * @return rule name to points
     */
    public @NotNull @Unmodifiable Map<String, Integer> rules() {
        return rules;
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
