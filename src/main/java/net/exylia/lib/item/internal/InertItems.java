package net.exylia.lib.item.internal;

import net.exylia.lib.item.ItemValues;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Items that look like something usable and are not.
 *
 * <p>A plugin's own items are drawn with whatever material suits them, and the
 * material is what the server acts on. A token whose icon is an ender pearl is
 * a thrown ender pearl; one drawn as a golden apple is eaten; one drawn as a
 * block is placed. Every one of those consumes the item, and the player is left
 * having spent something they were meant to keep.
 *
 * <p>The guard runs at {@code HIGHEST} rather than {@code LOWEST}: its whole
 * job is to make sure the server itself does not act, and the server acts after
 * every listener. Refusing early would let anything listening later put the
 * decision back.
 *
 * <p>What it does not touch is the item as an object. It can still be picked
 * up, dropped, traded, put in a chest and clicked in a menu — it simply does
 * nothing when used, which is what a token is.
 */
public final class InertItems implements Listener {

    private static final Map<String, InertItems> GUARDS = new ConcurrentHashMap<>();

    private final ItemValues values;
    private volatile Set<String> keys;

    private InertItems(ItemValues values, Set<String> keys) {
        this.values = values;
        this.keys = keys;
    }

    /**
     * Marks a plugin's items as inert.
     *
     * <p>Idempotent: asking twice replaces the keys rather than registering a
     * second listener, so a plugin that reloads in place does not end up with
     * one guard per reload.
     */
    public static void mark(@NotNull Plugin plugin, @NotNull ItemValues values,
                            @NotNull Set<String> keys) {
        GUARDS.compute(plugin.getName(), (name, existing) -> {
            if (existing != null) {
                existing.keys = Set.copyOf(keys);
                return existing;
            }
            InertItems guard = new InertItems(values, Set.copyOf(keys));
            plugin.getServer().getPluginManager().registerEvents(guard, plugin);
            return guard;
        });
    }

    /** Stops guarding a plugin's items, when the plugin goes away. */
    public static void release(@NotNull String pluginName) {
        InertItems guard = GUARDS.remove(pluginName);
        if (guard != null) {
            HandlerList.unregisterAll(guard);
        }
    }

    /** What a click should do to an inert item in hand. */
    public enum Use {

        /** Nothing this click could do would use the item. */
        NOTHING,

        /** Refuse the item, but let the click reach what it landed on. */
        DENY_ITEM,

        /** Refuse the click outright. */
        CANCEL
    }

    /**
     * What to do with a click, by where it landed.
     *
     * <p>A click on a block denies only the item: a player holding a token must
     * still be able to open the chest they clicked, and cancelling the whole
     * event would take that away for no reason. A click on air has no block to
     * protect, so it is refused whole.
     */
    public static @NotNull Use useOf(@NotNull Action action) {
        return switch (action) {
            case RIGHT_CLICK_BLOCK -> Use.DENY_ITEM;
            case RIGHT_CLICK_AIR -> Use.CANCEL;
            // Left clicks break blocks and hit entities. Neither spends the
            // item, and refusing them would stop a player defending themselves
            // because of what is in their hand.
            default -> Use.NOTHING;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!isInert(event.getItem())) {
            return;
        }
        switch (useOf(event.getAction())) {
            case DENY_ITEM -> event.setUseItemInHand(Event.Result.DENY);
            case CANCEL -> event.setCancelled(true);
            case NOTHING -> {
            }
        }
    }

    /**
     * Using an item on an entity: an item frame, a lead, a name tag.
     *
     * <p>Also catches {@code PlayerInteractAtEntityEvent}, which is this event
     * with a hit position and shares its handler list.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (isInert(held)) {
            event.setCancelled(true);
        }
    }

    /** Eating and drinking, for the paths that do not begin at a click. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isInert(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** The same for placing, which a denied click should already have stopped. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isInert(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    private boolean isInert(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        for (String key : keys) {
            if (values.has(item, key)) {
                return true;
            }
        }
        return false;
    }
}
