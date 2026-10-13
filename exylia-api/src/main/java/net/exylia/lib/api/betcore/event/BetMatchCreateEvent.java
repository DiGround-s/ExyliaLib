package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetGameInfo;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * A player is about to list an offer or send a duel, before their stake is taken.
 *
 * <p>Cancelling it stops the bet with nothing charged; the plugin that cancels tells the player why.
 *
 * @since exylia-api 2.0.0
 */
public class BetMatchCreateEvent extends BetEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player host;
    private final Player invitee;
    private final BetGameInfo game;
    private final String currency;
    private final BigDecimal stake;
    private boolean cancelled;

    public BetMatchCreateEvent(@NotNull Player host, @Nullable Player invitee, @NotNull BetGameInfo game,
                               @NotNull String currency, @NotNull BigDecimal stake) {
        this.host = host;
        this.invitee = invitee;
        this.game = game;
        this.currency = currency;
        this.stake = stake;
    }

    public @NotNull Player getHost() {
        return host;
    }

    /** The player a duel is sent to, or {@code null} for an offer listed in the lobby. */
    public @Nullable Player getInvitee() {
        return invitee;
    }

    public @NotNull BetGameInfo getGame() {
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
