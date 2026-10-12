package net.exylia.lib.api.market.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A deal between two players finished: a market listing bought, an auction
 * won, a buy order filled, or a trade swapped.
 *
 * <p>Fired once the money and the items are settled, so a listener rewards or
 * rates what really happened: a reputation plugin opens a rating, or gives
 * the seller back part of the {@link #getTax() tax}. Not cancellable.
 *
 * <p>A trade has no buyer and no seller: it is fired once per side, with that
 * side as {@link #getBuyer()} and the other one as {@link #getSeller()}, a
 * {@link #getPrice() price} of what the trade was worth in the default currency
 * and no tax.
 *
 * <p>Usually fired from a database callback, off the main thread; a listener
 * must not touch the world or a player's inventory without scheduling first.
 *
 * @since 1.11.0
 */
public class MarketDealEvent extends Event {

    /** A market listing bought. */
    public static final String MARKET = "market";
    /** An auction won. */
    public static final String AUCTIONS = "auctions";
    /** A buy order filled: the buyer placed the order, the seller filled it. */
    public static final String ORDERS = "orders";
    /** A face-to-face trade, fired once per side. */
    public static final String TRADE = "trade";

    private static final HandlerList HANDLERS = new HandlerList();

    private final String source;
    private final UUID buyer;
    private final String buyerName;
    private final UUID seller;
    private final String sellerName;
    private final String currency;
    private final BigDecimal price;
    private final BigDecimal tax;

    /**
     * Creates the event.
     *
     * @param source     {@link #MARKET}, {@link #AUCTIONS}, {@link #ORDERS} or {@link #TRADE}
     * @param buyer      who paid
     * @param buyerName  their name, {@code null} when not known
     * @param seller     who was paid
     * @param sellerName their name, {@code null} when not known
     * @param currency   the currency id it was priced in, blank for the default
     * @param price      what the buyer paid, before tax
     * @param tax        what the seller paid in sales tax, zero for none
     */
    public MarketDealEvent(@NotNull String source, @NotNull UUID buyer, @Nullable String buyerName,
                           @NotNull UUID seller, @Nullable String sellerName, @NotNull String currency,
                           @NotNull BigDecimal price, @NotNull BigDecimal tax) {
        super(!Bukkit.isPrimaryThread());
        this.source = source;
        this.buyer = buyer;
        this.buyerName = buyerName;
        this.seller = seller;
        this.sellerName = sellerName;
        this.currency = currency;
        this.price = price;
        this.tax = tax;
    }

    /** {@link #MARKET}, {@link #AUCTIONS}, {@link #ORDERS} or {@link #TRADE}. */
    public @NotNull String getSource() {
        return source;
    }

    /** Who paid; for a trade, the side this event is about. */
    public @NotNull UUID getBuyer() {
        return buyer;
    }

    /** The buyer's name, {@code null} when not known. */
    public @Nullable String getBuyerName() {
        return buyerName;
    }

    /** Who was paid; for a trade, the other side. */
    public @NotNull UUID getSeller() {
        return seller;
    }

    /** The seller's name, {@code null} when not known. */
    public @Nullable String getSellerName() {
        return sellerName;
    }

    /** The currency id the deal was priced in, blank for the default currency. */
    public @NotNull String getCurrency() {
        return currency;
    }

    /** What the buyer paid, before tax; for a trade, what it was worth in the default currency. */
    public @NotNull BigDecimal getPrice() {
        return price;
    }

    /** The sales tax the seller paid out of the price, zero when there was none. */
    public @NotNull BigDecimal getTax() {
        return tax;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
