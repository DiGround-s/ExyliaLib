package net.exylia.lib.objectives.internal;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.exylia.lib.block.PlacedBlocks;
import net.exylia.lib.npc.PlacedNpcClickEvent;
import net.exylia.lib.objectives.ObjectiveProgress;
import net.exylia.lib.objectives.ObjectiveType;
import net.exylia.lib.region.PlayerRegionChangeEvent;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.util.SpawnOrigin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns what players do into objective progress. Every handler first asks
 * whether anybody, then whether somebody wants this player, before it reads
 * anything else, so a type nobody tracks costs one list check per event.
 */
@ApiStatus.Internal
public final class ObjectiveListener implements Listener {

    /** Where each player already had a placement counted this session, so placing and breaking one block pays once. */
    private final Map<UUID, Set<String>> placedAt = new ConcurrentHashMap<>();

    ObjectiveListener() {
    }

    private static boolean waits(ObjectiveType type, Player player) {
        return ObjectiveRuntime.any(type) && ObjectiveRuntime.wanted(type, player);
    }

    private static ObjectiveProgress progress(Player player, ObjectiveType type, String thing, Location at, int amount) {
        return new ObjectiveProgress(player, type, thing, at, amount, false, null, null, null);
    }

    private static void thing(Player player, ObjectiveType type, String thing, Location at, int amount) {
        if (!waits(type, player)) return;
        ObjectiveRuntime.deliver(progress(player, type, thing, at, amount));
    }

    private static Location centre(Block block) {
        return block.getLocation().add(0.5, 0.5, 0.5);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        placedAt.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ blocks

    /** HIGHEST, not MONITOR: the library forgets a placed block at MONITOR, before a handler there could ask. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!waits(ObjectiveType.BREAK_BLOCK, player)) return;
        Block block = event.getBlock();
        ObjectiveRuntime.deliver(new ObjectiveProgress(player, ObjectiveType.BREAK_BLOCK, block.getType().name(),
                centre(block), 1, PlacedBlocks.placedByPlayer(block), null, null, null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!waits(ObjectiveType.PLACE_BLOCK, player)) return;
        Block block = event.getBlockPlaced();
        // ponytail: a spot counts once per session for every subscriber; key it per subscriber if one needs repeats.
        Set<String> spots = placedAt.computeIfAbsent(player.getUniqueId(), id -> ConcurrentHashMap.newKeySet());
        if (!spots.add(ObjectiveRuntime.spot(block))) return;
        ObjectiveRuntime.deliver(progress(player, ObjectiveType.PLACE_BLOCK, block.getType().name(), centre(block), 1));
    }

    /** Not ignoring cancelled: protection plugins cancel interactions with buttons and doors players may use. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || event.useInteractedBlock() == Event.Result.DENY) return;
        thing(event.getPlayer(), ObjectiveType.INTERACT_BLOCK, block.getType().name(), centre(block), 1);
    }

    // ------------------------------------------------------------------ items

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !waits(ObjectiveType.CRAFT, player)) return;
        ItemStack result = event.getRecipe().getResult();
        // ponytail: a shift-click craft of a whole stack counts as one craft; count the made items if that matters.
        ObjectiveRuntime.deliver(progress(player, ObjectiveType.CRAFT, result.getType().name(), player.getLocation(), result.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSmelt(FurnaceExtractEvent event) {
        thing(event.getPlayer(), ObjectiveType.SMELT, event.getItemType().name(), event.getBlock().getLocation(), event.getItemAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        thing(event.getEnchanter(), ObjectiveType.ENCHANT, event.getItem().getType().name(), event.getEnchantBlock().getLocation(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        ItemStack stack = caught.getItemStack();
        thing(event.getPlayer(), ObjectiveType.FISH, stack.getType().name(), event.getPlayer().getLocation(), stack.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !waits(ObjectiveType.PICKUP_ITEM, player)) return;
        // Dropped by a player (this one or a friend): picking it up again is not progress.
        if (event.getItem().getThrower() != null) return;
        ItemStack stack = event.getItem().getItemStack();
        ObjectiveRuntime.deliver(progress(player, ObjectiveType.PICKUP_ITEM, stack.getType().name(), player.getLocation(), stack.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        thing(event.getPlayer(), ObjectiveType.CONSUME, event.getItem().getType().name(), event.getPlayer().getLocation(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEquip(PlayerArmorChangeEvent event) {
        ItemStack now = event.getNewItem();
        if (now == null || now.getType().isAir()) return;
        thing(event.getPlayer(), ObjectiveType.EQUIP, now.getType().name(), event.getPlayer().getLocation(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(PlayerLevelChangeEvent event) {
        int gained = event.getNewLevel() - event.getOldLevel();
        if (gained <= 0) return;
        thing(event.getPlayer(), ObjectiveType.LEVEL, Integer.toString(event.getNewLevel()), event.getPlayer().getLocation(), gained);
    }

    // ------------------------------------------------------------------ combat

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Entity dead = event.getEntity();
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer == dead) return;
        ObjectiveType type = dead instanceof Player ? ObjectiveType.KILL_PLAYER : ObjectiveType.KILL_MOB;
        if (!ObjectiveRuntime.any(type)) return;
        Location at = dead.getLocation();
        String name = dead.getType().name();
        @Nullable SpawnOrigin origin = type == ObjectiveType.KILL_MOB ? SpawnOrigin.of(dead) : null;
        @Nullable UUID victim = type == ObjectiveType.KILL_PLAYER ? dead.getUniqueId() : null;
        // The killer may stand in another region on Folia: ask and count on their own thread.
        ObjectiveRuntime.deliverOn(killer, () -> new ObjectiveProgress(killer, type, name, at, 1, false, origin, victim, null));
    }

    // ------------------------------------------------------------------ places and NPCs

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegions(PlayerRegionChangeEvent event) {
        Player player = event.getPlayer();
        if (event.entered().isEmpty() || !waits(ObjectiveType.REACH, player)) return;
        for (RegionSnapshot region : event.entered()) {
            ObjectiveRuntime.deliver(progress(player, ObjectiveType.REACH, region.id().toString(), player.getLocation(), 1));
        }
    }

    /** Any placed NPC, whichever plugin placed it. Fired on the clicking player's thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcClick(PlacedNpcClickEvent event) {
        thing(event.getPlayer(), ObjectiveType.INTERACT_NPC, event.npcId(), event.getPlayer().getLocation(), 1);
    }

    // ------------------------------------------------------------------ commands and chat

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!waits(ObjectiveType.RUN_COMMAND, player)) return;
        String typed = event.getMessage();
        // After the command ran, so a step that opens a menu does not race the command's own screen.
        ObjectiveRuntime.later(player, () -> progress(player, ObjectiveType.RUN_COMMAND, typed, player.getLocation(), 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!waits(ObjectiveType.CHAT, player)) return;
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        ObjectiveRuntime.later(player, () -> progress(player, ObjectiveType.CHAT, message, player.getLocation(), 1));
    }
}
