package net.exylia.lib.session.internal;

import net.exylia.lib.input.internal.InsertWindow;
import net.exylia.lib.internal.Holders;
import net.exylia.lib.ui.Menus;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Players moving between modes, and the listener that freezes their items until
 * they have arrived. The public side is {@link net.exylia.lib.session.Transits}.
 *
 * <p>Every change goes through {@code ConcurrentHashMap.compute} on the
 * player's entry, so Folia's region threads, the teleport's completion thread
 * and a database thread finishing a restore never see half a change. A
 * completion only touches the entry it was counted in: one that lapsed and was
 * replaced keeps its own count.
 */
public final class TransitHolds implements Listener {

    static final long GRACE = TimeUnit.SECONDS.toNanos(1);
    static final long CAP = TimeUnit.SECONDS.toNanos(10);

    /** One transit. Fields only change inside {@code compute} on its entry. */
    private static final class Hold {
        final long started;
        int open;
        int moving;
        long until;

        Hold(long now) {
            this.started = now;
            this.until = now;
        }

        boolean live(long now) {
            return now - started < CAP && (open > 0 || moving > 0 || now - until < 0);
        }
    }

    private static final Map<UUID, Hold> HOLDS = new ConcurrentHashMap<>();

    private static Hold current(Hold hold, long now) {
        return hold != null && hold.live(now) ? hold : new Hold(now);
    }

    /** Starts or stretches a transit by one second. */
    public static void begin(@NotNull UUID player) {
        long now = System.nanoTime();
        HOLDS.compute(player, (id, hold) -> {
            Hold next = current(hold, now);
            next.until = Math.max(next.until, now + GRACE);
            return next;
        });
    }

    /**
     * Holds a player until {@code done} completes, then one second more when
     * {@code grace} answers true for its result. For work whose end is known: a
     * snapshot restore.
     */
    public static <T> void during(@NotNull UUID player, @NotNull CompletableFuture<T> done,
                                  @NotNull java.util.function.Predicate<T> grace) {
        long now = System.nanoTime();
        Hold[] counted = new Hold[1];
        HOLDS.compute(player, (id, hold) -> {
            Hold next = current(hold, now);
            next.open++;
            counted[0] = next;
            return next;
        });
        done.whenComplete((result, failure) -> HOLDS.computeIfPresent(player, (id, hold) -> {
            if (hold != counted[0]) return hold;
            hold.open = Math.max(0, hold.open - 1);
            if (failure == null && grace.test(result)) {
                hold.until = Math.max(hold.until, System.nanoTime() + GRACE);
            }
            return hold.live(System.nanoTime()) ? hold : null;
        }));
    }

    /**
     * Counts a teleport that starts while the player is in transit: the hold
     * lasts until one second after it lands. A teleport outside a transit is
     * left alone.
     */
    public static void track(@NotNull UUID player, @NotNull CompletableFuture<?> landed) {
        long now = System.nanoTime();
        Hold[] counted = new Hold[1];
        HOLDS.computeIfPresent(player, (id, hold) -> {
            if (!hold.live(now)) return null;
            hold.moving++;
            counted[0] = hold;
            return hold;
        });
        if (counted[0] == null) return;
        landed.whenComplete((result, failure) -> HOLDS.computeIfPresent(player, (id, hold) -> {
            if (hold != counted[0]) return hold;
            hold.moving = Math.max(0, hold.moving - 1);
            hold.until = Math.max(hold.until, System.nanoTime() + GRACE);
            return hold;
        }));
    }

    /** Whether a player is in transit; a lapsed entry is dropped on the way. */
    public static boolean isHeld(@NotNull UUID player) {
        if (!HOLDS.containsKey(player)) return false;
        long now = System.nanoTime();
        return HOLDS.computeIfPresent(player, (id, hold) -> hold.live(now) ? hold : null) != null;
    }

    /** Test seam. */
    public static void clearForTests() {
        HOLDS.clear();
    }

    // -------------------------------------------------------------- listener

    private static void refuse(Player player, Cancellable event) {
        if (isHeld(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Library menus, prompts and search results show items without moving any,
     * and their clicks must keep working. Item editors, the insert window and
     * trades do move items, so they wait like everything else.
     */
    private static boolean inMenu(InventoryEvent event) {
        if (Menus.isMenu(event.getView().getTopInventory())) return true;
        InventoryHolder holder = Holders.of(event.getView().getTopInventory());
        return holder != null && !(holder instanceof InsertWindow)
                && holder.getClass().getName().startsWith("net.exylia.lib.input.internal.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            refuse(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !inMenu(event)) {
            refuse(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && !inMenu(event)) {
            refuse(player, event);
        }
    }

    /**
     * Using the held item (a block, a bucket, a pearl) is refused; pressing a
     * button or opening a door with it is not.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getItem() == null) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (isHeld(event.getPlayer().getUniqueId())) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        HOLDS.remove(event.getPlayer().getUniqueId());
    }
}
