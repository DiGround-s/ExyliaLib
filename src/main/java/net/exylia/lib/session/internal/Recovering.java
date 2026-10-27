package net.exylia.lib.session.internal;

import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players whose stays are still being recovered after a join, and the listener
 * that keeps their hands off everything until it is done.
 *
 * <p>Between the join and the restore the player holds whatever the player file
 * had: a rolled-back inventory, or the kit of the arena they crashed out of.
 * Anything they sold, stored or dropped from it in that window would come back
 * with the restore. So until every pending recovery ends, commands (bar the
 * ones each recovering plugin allows), inventory clicks, drags, opening
 * containers, drops, pickups and interactions are refused.
 *
 * <p>Registered once by the library. {@code PluginStays.recover} begins and
 * ends a hold; nothing else should.
 */
public final class Recovering implements Listener {

    /** One pending recovery: which plugin, and which command labels it lets through. */
    private record Hold(String plugin, Set<String> allowed) {
    }

    private static final Map<UUID, List<Hold>> HOLDS = new ConcurrentHashMap<>();

    /**
     * Marks a recovery as started.
     *
     * @param player  the player being recovered
     * @param plugin  the recovering plugin's name
     * @param allowed lower-case command labels still allowed, without the slash
     * @return the hold, handed back to {@link #end}
     */
    public static @NotNull Object begin(@NotNull UUID player, @NotNull String plugin, @NotNull Set<String> allowed) {
        Hold hold = new Hold(plugin, Set.copyOf(allowed));
        HOLDS.compute(player, (id, holds) -> {
            List<Hold> next = holds == null ? new ArrayList<>() : new ArrayList<>(holds);
            next.add(hold);
            return List.copyOf(next);
        });
        return hold;
    }

    /** Marks a recovery as finished, whether it worked or not. */
    public static void end(@NotNull UUID player, @NotNull Object hold) {
        HOLDS.computeIfPresent(player, (id, holds) -> {
            List<Hold> next = new ArrayList<>(holds);
            next.remove(hold);
            return next.isEmpty() ? null : List.copyOf(next);
        });
    }

    /** Whether any plugin is still recovering the player. */
    public static boolean any(@NotNull UUID player) {
        return HOLDS.containsKey(player);
    }

    /** Whether this plugin is still recovering the player. */
    public static boolean by(@NotNull UUID player, @NotNull String plugin) {
        List<Hold> holds = HOLDS.get(player);
        return holds != null && holds.stream().anyMatch(hold -> hold.plugin().equals(plugin));
    }

    /**
     * Whether a command may run while the player is being recovered: every
     * recovering plugin has to allow it.
     *
     * @param player the player
     * @param line   the command line as typed, with or without the slash
     * @return whether it may run
     */
    public static boolean allows(@NotNull UUID player, @NotNull String line) {
        List<Hold> holds = HOLDS.get(player);
        if (holds == null) {
            return true;
        }
        String label = label(line);
        return holds.stream().allMatch(hold -> hold.allowed().contains(label));
    }

    /** A command line's label, lower case, without the slash or a {@code plugin:} namespace. */
    static String label(String line) {
        return net.exylia.lib.util.CommandLabels.label(line);
    }

    /** Drops every hold a plugin left behind; a disabled plugin finishes no recovery. */
    public static void forgetPlugin(@NotNull String plugin) {
        for (UUID player : List.copyOf(HOLDS.keySet())) {
            HOLDS.computeIfPresent(player, (id, holds) -> {
                List<Hold> next = holds.stream().filter(hold -> !hold.plugin().equals(plugin)).toList();
                return next.isEmpty() ? null : next;
            });
        }
    }

    /** Test seam. */
    public static void clearForTests() {
        HOLDS.clear();
    }

    // -------------------------------------------------------------- listener

    private static void refuse(Player player, Cancellable event) {
        if (any(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!allows(player.getUniqueId(), event.getMessage())) {
            event.setCancelled(true);
            Text.of(Phrases.tr("{error}Your belongings are still being restored. Try again in a moment."))
                    .send(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            refuse(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            refuse(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refuse(player, event);
        }
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
    public void onInteract(PlayerInteractEvent event) {
        refuse(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        refuse(event.getPlayer(), event);
    }
}
