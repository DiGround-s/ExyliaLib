package net.exylia.lib.api.jobs.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Units of a work order were delivered and paid. Fired after the delivery
 * is stored and the deliverer paid; informative only. Called on the
 * deliverer's thread.
 *
 * @since 3.6.0
 */
public class WorkOrderFilledEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String orderId;
    private final UUID buyer;
    private final Player deliverer;
    private final String jobId;
    private final String itemId;
    private final int units;
    private final BigDecimal paid;

    /**
     * Creates the event.
     *
     * @param orderId the order
     * @param buyer who placed the order
     * @param deliverer who delivered
     * @param jobId the job the order belongs to
     * @param itemId the item delivered, a material or custom id
     * @param units how many units this delivery filled
     * @param paid what the deliverer was paid for them
     */
    public WorkOrderFilledEvent(@NotNull String orderId, @NotNull UUID buyer, @NotNull Player deliverer, @NotNull String jobId, @NotNull String itemId, int units, @NotNull BigDecimal paid) {
        super(!Bukkit.isPrimaryThread());
        this.orderId = orderId;
        this.buyer = buyer;
        this.deliverer = deliverer;
        this.jobId = jobId;
        this.itemId = itemId;
        this.units = units;
        this.paid = paid;
    }


    /**
     * The order.
     *
     * @return the value
     */
    @NotNull
    public String getOrderId() {
        return orderId;
    }

    /**
     * Who placed the order.
     *
     * @return the value
     */
    @NotNull
    public UUID getBuyer() {
        return buyer;
    }

    /**
     * Who delivered.
     *
     * @return the value
     */
    @NotNull
    public Player getDeliverer() {
        return deliverer;
    }

    /**
     * The job the order belongs to.
     *
     * @return the value
     */
    @NotNull
    public String getJobId() {
        return jobId;
    }

    /**
     * The item delivered, a material or custom id.
     *
     * @return the value
     */
    @NotNull
    public String getItemId() {
        return itemId;
    }

    /**
     * How many units this delivery filled.
     *
     * @return the value
     */
    public int getUnits() {
        return units;
    }

    /**
     * What the deliverer was paid for them.
     *
     * @return the value
     */
    @NotNull
    public BigDecimal getPaid() {
        return paid;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
