package net.exylia.lib.api.tabletop.event;

import net.exylia.lib.api.tabletop.TableGameInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * A player is about to list an offer or send a duel, before their stake is taken.
 *
 * <p>Cancelling it stops the match with nothing charged; the plugin that cancels tells the player why.
 *
 * @since exylia-api 3.0.0
 */
public class TableMatchCreateEvent extends TableEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player host;
    private final List<Player> invitees;
    private final TableGameInfo game;
    private final String currency;
    private final BigDecimal stake;
    private boolean cancelled;

    public TableMatchCreateEvent(@NotNull Player host, @Nullable Player invitee, @NotNull TableGameInfo game,
                               @NotNull String currency, @NotNull BigDecimal stake) {
        this(host, invitee == null ? List.of() : List.of(invitee), game, currency, stake);
    }

    /** @since exylia-api 3.1.0 */
    public TableMatchCreateEvent(@NotNull Player host, @NotNull List<Player> invitees, @NotNull TableGameInfo game,
                               @NotNull String currency, @NotNull BigDecimal stake) {
        this.host = host;
        this.invitees = List.copyOf(invitees);
        this.game = game;
        this.currency = currency;
        this.stake = stake;
    }

    public @NotNull Player getHost() {
        return host;
    }

    /** The player a duel is sent to (the first, when sent to several), or {@code null} for an offer listed in the lobby. */
    public @Nullable Player getInvitee() {
        return invitees.isEmpty() ? null : invitees.getFirst();
    }

    /**
     * Every player a duel is sent to; empty for an offer listed in the lobby.
     *
     * @since exylia-api 3.1.0
     */
    public @NotNull List<Player> getInvitees() {
        return invitees;
    }

    public @NotNull TableGameInfo getGame() {
        return game;
    }

    public @NotNull String getCurrency() {
        return currency;
    }

    public @NotNull BigDecimal getStake() {
        return stake;
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

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
