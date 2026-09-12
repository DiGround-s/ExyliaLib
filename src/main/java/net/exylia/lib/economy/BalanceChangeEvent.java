package net.exylia.lib.economy;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Somebody's balance changed through the library.
 *
 * <p>Fired after the change, for every currency — stored, Vault, PlayerPoints
 * or one a plugin registered — so a scoreboard, a quest or a log can react
 * without knowing which economy is underneath. Not cancellable: by the time
 * this fires the money has moved, and an event that could undo a purchase
 * after the item was handed over would be the dupe every shop fears.
 *
 * <p>Fired on whichever thread the operation ran on, and says so through
 * {@link #isAsynchronous()}; a listener that touches the world hops to the
 * player's thread first.
 *
 * @since 1.150.0
 */
public final class BalanceChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String currency;
    private final BigDecimal before;
    private final BigDecimal after;
    private final Transaction transaction;

    public BalanceChangeEvent(@NotNull UUID player, @NotNull String currency,
                              @NotNull BigDecimal before, @NotNull BigDecimal after,
                              @NotNull Transaction transaction) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.currency = currency;
        this.before = before;
        this.after = after;
        this.transaction = transaction;
    }

    public @NotNull UUID player() {
        return player;
    }

    public @NotNull String currency() {
        return currency;
    }

    public @NotNull BigDecimal before() {
        return before;
    }

    public @NotNull BigDecimal after() {
        return after;
    }

    /** What moved: positive for money in, negative for money out. */
    public @NotNull BigDecimal delta() {
        return after.subtract(before);
    }

    public @NotNull Transaction transaction() {
        return transaction;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
