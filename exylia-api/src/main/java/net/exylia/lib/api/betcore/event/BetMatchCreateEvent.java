package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetGame;
import net.exylia.lib.api.betcore.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * Somebody is offering a bet.
 *
 * <p>Fired before anything is charged and before the match exists, so a plugin
 * may refuse it outright or change what it costs. The stake is writable for the
 * discount case: a rank that bets at half price is a plugin listening here, not
 * a config key in this one.
 *
 * @since 1.0.0
 */
public class BetMatchCreateEvent extends BetEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player host;
    private final BetGame<?> game;
    private final GameMode mode;
    private final String currency;
    private final int rounds;

    private BigDecimal wager;
    private boolean cancelled;

    public BetMatchCreateEvent(@NotNull Player host, @NotNull BetGame<?> game,
                               @NotNull GameMode mode, @NotNull String currency,
                               @NotNull BigDecimal wager, int rounds) {
        this.host = host;
        this.game = game;
        this.mode = mode;
        this.currency = currency;
        this.wager = wager;
        this.rounds = rounds;
    }

    public @NotNull Player getHost() {
        return host;
    }

    public @NotNull BetGame<?> getGame() {
        return game;
    }

    public @NotNull GameMode getMode() {
        return mode;
    }

    public @NotNull String getCurrency() {
        return currency;
    }

    public @NotNull BigDecimal getWager() {
        return wager;
    }

    /** Changes what each player has to put up. */
    public void setWager(@NotNull BigDecimal wager) {
        this.wager = wager;
    }

    public int getRounds() {
        return rounds;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
