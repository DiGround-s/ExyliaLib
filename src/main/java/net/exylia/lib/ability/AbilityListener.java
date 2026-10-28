package net.exylia.lib.ability;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Turns Bukkit events into the built-in triggers, and keeps holdings fresh.
 *
 * <p>Every handler asks {@link PluginAbilities#holds} first, so an event nobody
 * holds an ability for costs a map read. Portable API only: this class loads on
 * Spigot. Paper's armour event lives in {@link PaperArmourListener}.
 */
final class AbilityListener implements Listener {

    private final PluginAbilities abilities;

    AbilityListener(PluginAbilities abilities) {
        this.abilities = abilities;
    }

    // ------------------------------------------------------------------ triggers

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof LivingEntity victim
                && abilities.holds(attacker, Triggers.ATTACK)) {
            abilities.fire(attacker, Triggers.ATTACK, ctx -> ctx.event(event).victim(victim));
        }
        if (event.getEntity() instanceof Player victim && abilities.holds(victim, Triggers.HIT_TAKEN)) {
            LivingEntity attacker = living(event.getDamager());
            abilities.fire(victim, Triggers.HIT_TAKEN, ctx -> ctx.event(event).attacker(attacker));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !abilities.holds(player, Triggers.LOW_HEALTH)) return;
        double max = net.exylia.lib.ability.internal.Targets.maxHealth(player);
        double before = player.getHealth();
        double after = before - event.getFinalDamage();
        double line = max * PluginAbilities.LOW_HEALTH;
        if (before > line && after <= line && after > 0) {
            LivingEntity attacker = event instanceof EntityDamageByEntityEvent byEntity
                    ? living(byEntity.getDamager()) : null;
            abilities.fire(player, Triggers.LOW_HEALTH, ctx -> ctx.event(event).attacker(attacker));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && abilities.holds(killer, Triggers.KILL)) {
            abilities.fire(killer, Triggers.KILL, ctx -> ctx.event(event).victim(event.getEntity())
                    .with(ContextKey.DROPS, event.getDrops()));
        }
        if (event instanceof PlayerDeathEvent death && abilities.holds(death.getEntity(), Triggers.DEATH)) {
            abilities.fire(death.getEntity(), Triggers.DEATH, ctx -> ctx.event(event).attacker(killer));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player shooter && abilities.holds(shooter, Triggers.BOW_SHOOT)) {
            abilities.fire(shooter, Triggers.BOW_SHOOT, ctx -> ctx.event(event));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity().getShooter() instanceof Player shooter
                && abilities.holds(shooter, Triggers.PROJECTILE_HIT)) {
            LivingEntity victim = event.getHitEntity() instanceof LivingEntity living ? living : null;
            abilities.fire(shooter, Triggers.PROJECTILE_HIT, ctx -> ctx.event(event).victim(victim)
                    .block(event.getHitBlock()).location(event.getEntity().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        // The extra blocks of an area break never fire BLOCK_BREAK: no recursion.
        if (AreaBreaks.isBreaking() || !abilities.holds(event.getPlayer(), Triggers.BLOCK_BREAK)) return;
        abilities.fire(event.getPlayer(), Triggers.BLOCK_BREAK, ctx -> ctx.event(event).block(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        PlayerFishEvent.State state = event.getState();
        if ((state == PlayerFishEvent.State.CAUGHT_FISH || state == PlayerFishEvent.State.CAUGHT_ENTITY)
                && abilities.holds(event.getPlayer(), Triggers.FISH)) {
            LivingEntity caught = event.getCaught() instanceof LivingEntity living ? living : null;
            abilities.fire(event.getPlayer(), Triggers.FISH, ctx -> ctx.event(event).victim(caught));
        }
    }

    /** The jump statistic: the one jump signal Spigot and Paper share. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStatistic(PlayerStatisticIncrementEvent event) {
        if (event.getStatistic() == Statistic.JUMP && abilities.holds(event.getPlayer(), Triggers.JUMP)) {
            abilities.fire(event.getPlayer(), Triggers.JUMP, ctx -> ctx.event(event));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && abilities.holds(event.getPlayer(), Triggers.SNEAK)) {
            abilities.fire(event.getPlayer(), Triggers.SNEAK, ctx -> ctx.event(event));
        }
    }

    // ------------------------------------------------------------------ holdings

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        abilities.refreshSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        abilities.forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        abilities.refreshSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        abilities.refreshSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        abilities.refreshSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) abilities.refreshSoon(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        abilities.refreshSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) abilities.refreshSoon(player);
    }

    /** Death took every potion effect: everything held equips again. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        abilities.tasks().runAtEntityLater(player, 1, () -> abilities.refresh(player, true));
    }

    /** Milk took every potion effect: PASSIVE ones go back on. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.MILK_BUCKET) {
            Player player = event.getPlayer();
            abilities.tasks().runAtEntityLater(player, 1, () -> abilities.refresh(player, true));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static LivingEntity living(Entity damager) {
        if (damager instanceof LivingEntity living) return living;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living) return living;
        }
        return null;
    }
}
