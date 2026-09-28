package net.exylia.lib.region.internal;

import net.exylia.lib.region.internal.RegionEnforcement.Check;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Applies the common policies of the regions whose owner asked for it.
 *
 * <h2>Why HIGH with ignoreCancelled</h2>
 * A protection refuses at {@code HIGH} or earlier, so the plugin that owns a region can
 * still overrule it at {@code HIGHEST} — a duel room re-allowing PvP — and a refusal
 * already made by somebody else is not looked at twice.
 *
 * <h2>What is not here</h2>
 * {@code entry}, {@code exit} and {@code region_members_only} have one consumer and no
 * member concept in the library; explosions have no player to put through an audience;
 * temporary and re-given blocks belong to {@link PlacedBlockRuntime}.
 *
 * <p>Every handler returns on {@link RegionEnforcement#active()} first. Handlers run on
 * the thread that owns the event, as Folia requires, and schedule nothing.
 */
public final class EnforcementListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!RegionEnforcement.active()) return;
        Block block = event.getBlock();
        if (denies(event.getPlayer(), block, Check.BREAK, block.getType())) event.setCancelled(true);
    }

    /** Scooping a fluid takes it out of the region, so it is a break of that fluid. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!RegionEnforcement.active()) return;
        Block block = event.getBlock();
        if (denies(event.getPlayer(), block, Check.BREAK, block.getType())) event.setCancelled(true);
    }

    /** Also every block of a multi-place: {@code BlockMultiPlaceEvent} shares this handler list. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!RegionEnforcement.active()) return;
        Block block = event.getBlockPlaced();
        if (denies(event.getPlayer(), block, Check.BUILD, block.getType())) event.setCancelled(true);
    }

    /**
     * Pouring a bucket is building, though it never fires a place event. Against a
     * block list it counts as the bucket, such as {@code WATER_BUCKET}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!RegionEnforcement.active()) return;
        if (denies(event.getPlayer(), event.getBlock(), Check.BUILD, event.getBucket())) {
            event.setCancelled(true);
        }
    }

    /**
     * Refuses the block, not the hand: cancelling the whole click would also stop a
     * player looking at a block from eating, drinking, throwing a pearl or drawing a
     * bow. Stepping on a plate or trampling farmland has no hand to keep, so it is
     * cancelled outright.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!RegionEnforcement.active()) return;
        Block block = event.getClickedBlock();
        if (block == null || !denies(event.getPlayer(), block, Check.INTERACT, null)) return;
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        } else {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!RegionEnforcement.active()) return;
        if (decoration(event.getRightClicked(), event.getPlayer())) event.setCancelled(true);
    }

    /** Armour stands are clicked through this one; it has its own handler list. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (!RegionEnforcement.active()) return;
        if (decoration(event.getRightClicked(), event.getPlayer())) event.setCancelled(true);
    }

    /**
     * Both ends: a player outside cannot shoot into a no-PvP region, nor out of one.
     * Arrows, TNT, crystals, potions and tamed wolves count as whoever is behind them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!RegionEnforcement.active()) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = Culprits.playerBehind(event);
        if (attacker != null && RegionEnforcement.pvpDenied(attacker, victim)) event.setCancelled(true);
    }

    /** A harmful splash reaches no protected player; a healing one still does. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!RegionEnforcement.active()) return;
        Player thrower = Culprits.playerBehind(event.getPotion());
        if (thrower == null || !harmful(event.getPotion().getEffects())) return;
        for (LivingEntity hit : event.getAffectedEntities()) {
            if (hit instanceof Player victim && RegionEnforcement.pvpDenied(thrower, victim)) event.setIntensity(victim, 0);
        }
    }

    /** The same for a lingering potion's cloud. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        if (!RegionEnforcement.active()) return;
        AreaEffectCloud cloud = event.getEntity();
        Player thrower = Culprits.playerBehind(cloud);
        if (thrower == null) return;
        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        if (cloud.getBasePotionType() != null) effects.addAll(cloud.getBasePotionType().getPotionEffects());
        if (!harmful(effects)) return;
        event.getAffectedEntities().removeIf(hit -> hit instanceof Player victim && RegionEnforcement.pvpDenied(thrower, victim));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (!RegionEnforcement.active()) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)) return;
        if (denies(player, player.getLocation(), Check.FALL_DAMAGE)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!RegionEnforcement.active()) return;
        Player player = event.getPlayer();
        if (denies(player, player.getLocation(), Check.ITEM_DROP)) event.setCancelled(true);
    }

    /** The Bukkit event rather than Paper's attempt event, so the library loads on Spigot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!RegionEnforcement.active()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (denies(player, player.getLocation(), Check.ITEM_PICKUP)) event.setCancelled(true);
    }

    /** Armour stands, item frames and paintings: blocks in all but name. */
    private static boolean decoration(Entity entity, Player player) {
        return (entity instanceof ArmorStand || entity instanceof Hanging)
                && denies(player, entity.getLocation(), Check.INTERACT);
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) return true;
        }
        return false;
    }

    /** At the block's minimum corner, which is the coordinate region shapes agree with. */
    private static boolean denies(Player player, Block block, Check check, Material material) {
        return RegionEnforcement.denies(player, block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), check, material);
    }

    private static boolean denies(Player player, Location at, Check check) {
        return at.getWorld() != null && RegionEnforcement.denies(player, at.getWorld().getUID(),
                at.getX(), at.getY(), at.getZ(), check, null);
    }
}
