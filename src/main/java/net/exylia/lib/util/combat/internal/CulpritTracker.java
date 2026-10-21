package net.exylia.lib.util.combat.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.util.combat.Culprits;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;

/**
 * Who armed what, for the explosions that do not remember it themselves.
 *
 * <p>An end crystal set off by a respawn anchor, a bed or another crystal, and
 * an anchor or bed blown up outside its dimension, carry no causing entity in
 * their damage source. This remembers the player behind them for a few seconds:
 * an explosion lands in the same tick it is set off, so a short life is all a
 * record needs, and nothing outlives a fight.
 *
 * <p>Not cleared when the crystal is removed: a crystal is removed before its
 * explosion damages anybody, so that is exactly when the record is needed.
 * The expiry is the cleanup.
 *
 * <p>Both maps are concurrent, so the region threads of a Folia server write
 * and read them without coordination.
 */
public final class CulpritTracker implements Listener {

    private static final Duration LIFETIME = Duration.ofSeconds(5);

    private static final Cache<UUID, UUID> ENTITIES = Caffeine.newBuilder()
            .expireAfterWrite(LIFETIME)
            .maximumSize(4096)
            .build();

    private static final Cache<BlockKey, UUID> BLOCKS = Caffeine.newBuilder()
            .expireAfterWrite(LIFETIME)
            .maximumSize(4096)
            .build();

    /** The player who last set this entity off, while they are online. */
    public static @Nullable Player armer(Entity entity) {
        return online(ENTITIES.getIfPresent(entity.getUniqueId()));
    }

    /** The player who last set off the block at this position, while they are online. */
    public static @Nullable Player armer(@Nullable Location where) {
        if (where == null || where.getWorld() == null) {
            return null;
        }
        return online(BLOCKS.getIfPresent(new BlockKey(where.getWorld().getUID(),
                where.getBlockX(), where.getBlockY(), where.getBlockZ())));
    }

    public static void shutdown() {
        ENTITIES.invalidateAll();
        BLOCKS.invalidateAll();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        Player culprit = Culprits.playerBehind(event);
        if (culprit != null) {
            ENTITIES.put(crystal.getUniqueId(), culprit.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        World world = block.getWorld();
        UUID player = event.getPlayer().getUniqueId();
        if (block.getType() == Material.RESPAWN_ANCHOR) {
            if (!world.isRespawnAnchorWorks()) {
                arm(block, player);
            }
        } else if (block.getBlockData() instanceof Bed bed && !world.isBedWorks()) {
            arm(block, player);
            arm(bed.getPart() == Bed.Part.HEAD
                    ? block.getRelative(bed.getFacing().getOppositeFace())
                    : block.getRelative(bed.getFacing()), player);
        }
    }

    private static void arm(Block block, UUID player) {
        BLOCKS.put(new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()), player);
    }

    private static @Nullable Player online(@Nullable UUID id) {
        return id == null ? null : Bukkit.getPlayer(id);
    }

    private record BlockKey(UUID world, int x, int y, int z) {
    }
}
