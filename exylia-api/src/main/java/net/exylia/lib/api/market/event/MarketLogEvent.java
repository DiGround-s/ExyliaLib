package net.exylia.lib.api.market.event;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Something ExyliaMarket wants on a staff audit log: a sale, a bid, an order
 * filled or cancelled, a listing removed by staff, a trade swapped.
 *
 * <p>ExyliaMarket keeps no audit log of its own; a plugin that has one (such as
 * ExyliaSurvivalCore's {@code /survivalcorelogs}) records these. Not
 * cancellable: what it reports already happened.
 *
 * <p>Usually fired from a database callback, off the main thread. The items
 * are copies taken when the event was made, safe to keep.
 *
 * @since 1.11.0
 * @deprecated a plugin now records its audit trail itself into the staff log
 *             every plugin shares, {@code net.exylia.lib.log.Logs} in ExyliaLib
 *             1.296.0 and later, browsed with {@code /exylialib logs}; nothing
 *             needs to listen for this event any more
 */
@Deprecated(since = "2.1.0")
public class MarketLogEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String category;
    private final String action;
    private final UUID actorId;
    private final String actorName;
    private final UUID targetId;
    private final String targetName;
    private final String detail;
    private final Location location;
    private final Map<String, List<ItemStack>> items;

    /**
     * Creates the event.
     *
     * @param category   {@code market}, {@code auctions}, {@code orders} or {@code trade}
     * @param action     what happened, such as {@code market-buy} or {@code order-fill}
     * @param actorId    who did it, {@code null} for the console
     * @param actorName  their name, {@code CONSOLE} for the console
     * @param targetId   who it was done to, {@code null} for nobody
     * @param targetName their name, {@code null} for nobody
     * @param detail     one line a reader understands, with amounts and items written out
     * @param location   where the actor stood, {@code null} when not known
     * @param items      the items involved, grouped under a label such as {@code Bought}, in order;
     *                   {@code null} for none
     */
    public MarketLogEvent(@NotNull String category, @NotNull String action, @Nullable UUID actorId,
                          @NotNull String actorName, @Nullable UUID targetId, @Nullable String targetName,
                          @NotNull String detail, @Nullable Location location,
                          @Nullable Map<String, List<ItemStack>> items) {
        super(!Bukkit.isPrimaryThread());
        this.category = category;
        this.action = action;
        this.actorId = actorId;
        this.actorName = actorName;
        this.targetId = targetId;
        this.targetName = targetName;
        this.detail = detail;
        this.location = location == null ? null : location.clone();
        Map<String, List<ItemStack>> copy = new LinkedHashMap<>();
        if (items != null) {
            items.forEach((label, stacks) -> {
                List<ItemStack> cloned = new ArrayList<>();
                if (stacks != null) {
                    for (ItemStack stack : stacks) {
                        if (stack != null && !stack.getType().isAir()) cloned.add(stack.clone());
                    }
                }
                copy.put(label, List.copyOf(cloned));
            });
        }
        this.items = Collections.unmodifiableMap(copy);
    }

    /** {@code market}, {@code auctions}, {@code orders} or {@code trade}. */
    public @NotNull String getCategory() {
        return category;
    }

    /** What happened, such as {@code market-buy}. */
    public @NotNull String getAction() {
        return action;
    }

    /** Who did it, {@code null} for the console. */
    public @Nullable UUID getActorId() {
        return actorId;
    }

    /** Their name, {@code CONSOLE} for the console. */
    public @NotNull String getActorName() {
        return actorName;
    }

    /** Who it was done to, {@code null} for nobody. */
    public @Nullable UUID getTargetId() {
        return targetId;
    }

    /** Their name, {@code null} for nobody. */
    public @Nullable String getTargetName() {
        return targetName;
    }

    /** The line a reader understands. */
    public @NotNull String getDetail() {
        return detail;
    }

    /** Where the actor stood, a copy; {@code null} when not known. */
    public @Nullable Location getLocation() {
        return location == null ? null : location.clone();
    }

    /** The items involved by label, in order; unmodifiable, empty for none. */
    public @NotNull Map<String, List<ItemStack>> getItems() {
        return items;
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
