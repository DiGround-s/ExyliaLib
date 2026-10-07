package net.exylia.lib.replay.internal;

import io.papermc.paper.event.block.BlockBreakProgressUpdateEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import net.exylia.lib.replay.ReplayMark;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Cake;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.DaylightDetector;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server's own events, turned into what the recorders keep.
 *
 * <p>All at MONITOR and, where the event can be cancelled, only when it was not:
 * what is recorded is what happened, after every other plugin had its say.
 *
 * <p>Two places listen: recordings that follow an actor or watch a place, and
 * the black box. A recording only hears about what it follows; the black box
 * hears about everybody it has a tape for, and about every block that changes.
 */
@ApiStatus.Internal
final class ReplayEvents implements Listener {

    /** Liquid spreading is a change per block per tick; this many a tick, at most. */
    private static final int FLOWS_PER_TICK = 256;

    /** When each player last swung, for whether a hit was fully charged. */
    private final Map<UUID, Integer> swungAt = new ConcurrentHashMap<>();
    private final AtomicInteger flowTick = new AtomicInteger();
    private final AtomicInteger flows = new AtomicInteger();

    private ReplayEvents() {
    }

    static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new ReplayEvents(), plugin);
        try {
            Bukkit.getPluginManager().registerEvents(new Recent(), plugin);
        } catch (NoClassDefFoundError older) {
            // A server without the newer events: no cracks while mining, no
            // shield knocked out. The rest is recorded.
        }
    }

    // ------------------------------------------------------------ dispatching

    /** A mark about one actor, to whoever keeps them. */
    static void actor(String kind, Entity actor, byte @Nullable [] data) {
        UUID id = actor.getUniqueId();
        ReplayRuntime.following(id, recording -> recording.markActor(kind, id, data));
        BlackBox box = ReplayRuntime.box();
        if (box != null && box.knows(id)) box.mark(kind, id, data);
    }

    /** A mark that happened at a place. */
    static void place(String kind, @Nullable Entity actor, Location at, @Nullable String text) {
        if (actor != null) {
            UUID id = actor.getUniqueId();
            ReplayRuntime.following(id, recording -> recording.markAt(kind, at, text));
        }
        BlackBox box = ReplayRuntime.box();
        if (box != null) box.markAt(kind, actor == null ? null : actor.getUniqueId(), at, text, 0f);
    }

    /** A block about to change: what it is now. */
    static void block(Block block, BlockData was) {
        if (!ReplayRuntime.capturing()) return;
        BlackBox box = ReplayRuntime.box();
        if (box != null) box.changed(block.getWorld(), block.getX(), block.getY(), block.getZ(), was);
        ReplayRuntime.watching(block.getLocation(), recording -> recording.blockSeen(block.getLocation(), was));
    }

    private static void block(Block block) {
        // A server where nothing records pays nothing for its block events.
        if (!ReplayRuntime.capturing()) return;
        block(block, block.getBlockData());
    }

    private static void blocks(List<Block> blocks) {
        for (Block block : blocks) block(block);
    }

    private static void states(List<BlockState> states) {
        for (BlockState state : states) {
            Block block = state.getBlock();
            block(block, block.getBlockData());
        }
    }

    // ---------------------------------------------------------------- players

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        BlackBox box = ReplayRuntime.box();
        if (box != null) box.watch(player);
        // Somebody who was watching a replay when the server went down comes
        // back on a stage nothing will ever play on again.
        if (player.getWorld().getName().equals(net.exylia.lib.util.world.TemporaryWorld.NAME)) {
            player.teleportAsync(Bukkit.getWorlds().getFirst().getSpawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        net.exylia.lib.replay.ReplayViewer.quit(event.getPlayer());
        actor(ReplayMark.QUIT, event.getPlayer(),
                event.getReason().name().getBytes(StandardCharsets.UTF_8));
        swungAt.remove(event.getPlayer().getUniqueId());
        BlackBox box = ReplayRuntime.box();
        if (box != null) {
            box.unwatch(event.getPlayer().getUniqueId());
            box.gone(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        PlayerAnimationType type = event.getAnimationType();
        if (type != PlayerAnimationType.ARM_SWING && type != PlayerAnimationType.OFF_ARM_SWING) return;
        boolean offHand = type == PlayerAnimationType.OFF_ARM_SWING;
        if (!offHand) swungAt.put(event.getPlayer().getUniqueId(), ReplayClock.now());
        actor(ReplayMark.SWING, event.getPlayer(), offHand ? new byte[] {1} : null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        Entity source = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        boolean blocked = victim instanceof HumanEntity human && human.isBlocking()
                && event.getFinalDamage() <= 0 && source != null;
        if (!blocked) {
            float direction = 0f;
            if (source != null) {
                // The game's own: where the blow came from, relative to the body.
                double dx = source.getLocation().getX() - victim.getLocation().getX();
                double dz = source.getLocation().getZ() - victim.getLocation().getZ();
                direction = (float) (Math.toDegrees(Math.atan2(dz, dx)) - victim.getLocation().getYaw());
            }
            actor(ReplayMark.HURT, victim, MarkData.hurt(direction));
        }
        if (!(source instanceof Player attacker)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        int how = 0;
        boolean strong = charged(attacker);
        if (cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) how |= MarkData.SWEEP;
        if (((EntityDamageByEntityEvent) event).isCritical()) how |= MarkData.CRIT;
        if (attacker.isSprinting() && strong) how |= MarkData.KNOCKBACK;
        if (strong) how |= MarkData.STRONG;
        if (blocked) how |= MarkData.BLOCKED;
        else if (event.getFinalDamage() <= 0) how |= MarkData.NO_DAMAGE;
        actor(ReplayMark.ATTACK, attacker, MarkData.attack(victim.getUniqueId(), how));
    }

    /**
     * Whether a swing was charged when it landed.
     *
     * <p>The game resets the charge before the damage event fires, so it is
     * worked out from the time since the swing before this one and the attack
     * speed of what is held.
     */
    private boolean charged(Player attacker) {
        Integer last = swungAt.get(attacker.getUniqueId());
        if (last == null) return true;
        AttributeInstance speed = attacker.getAttribute(Attribute.ATTACK_SPEED);
        double perSecond = speed == null ? 4.0 : speed.getValue();
        double delay = perSecond <= 0 ? 0 : 20.0 / perSecond;
        // The client sends the hit before the swing that goes with it, so the
        // last swing on record is the click before this one.
        return ReplayClock.now() - last >= delay * 0.9 || delay <= 1;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        actor(ReplayMark.DEATH, event.getEntity(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        actor(ReplayMark.RESPAWN, event.getPlayer(), null);
        resume(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        resume(event.getPlayer());
    }

    /**
     * Picks somebody up again after the server may have dropped the timers
     * bound to them: Folia retires an entity's tasks across a respawn or a
     * change of world.
     */
    private static void resume(Player player) {
        ReplayRuntime.scheduler().runAtEntityLater(player, 2L, () -> {
            BlackBox box = ReplayRuntime.box();
            // Two ticks on, a live timer sampled on this tick or the last.
            if (box != null) box.ensure(player, 1);
            ReplayRuntime.following(player.getUniqueId(), recording -> recording.resume(player));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent event) {
        actor(ReplayMark.TOTEM, event.getEntity(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        actor(ReplayMark.TELEPORT, event.getPlayer(),
                event.getCause().name().getBytes(StandardCharsets.UTF_8));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        BlackBox box = ReplayRuntime.box();
        if (box == null || !box.knows(event.getPlayer().getUniqueId())) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        box.mark(ReplayMark.CHAT, event.getPlayer().getUniqueId(), text.getBytes(StandardCharsets.UTF_8));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        actor(ReplayMark.MOUNT, event.getEntity(), MarkData.other(event.getMount().getUniqueId(), 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        actor(ReplayMark.DISMOUNT, event.getEntity(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        actor(ReplayMark.PICKUP, event.getEntity(), MarkData.other(event.getItem().getUniqueId(),
                event.getItem().getItemStack().getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        BlackBox box = ReplayRuntime.box();
        if (box != null) box.notice(entity);
        if (!Tracking.worthRecording(entity)) return;
        ReplayRuntime.watching(entity.getLocation(), recording -> recording.follow(entity));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoved(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
        BlackBox box = ReplayRuntime.box();
        if (box != null && !(event.getEntity() instanceof Player)) box.gone(event.getEntity().getUniqueId());
    }

    // ----------------------------------------------------------------- blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                block(replaced.getBlock(), replaced.getBlockData());
            }
            return;
        }
        block(event.getBlock(), event.getBlockReplacedState().getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        // A respawn anchor or a bed is gone before the event: what it was is
        // kept beside it, and without it the anchor never stood there at all.
        BlockState exploded = event.getExplodedBlockState();
        if (exploded != null) block(event.getBlock(), exploded.getBlockData());
        explosion(event.getBlock().getLocation().add(0.5, 0.5, 0.5), event.blockList(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explosion(event.getLocation(), event.blockList(), event.getEntity());
    }

    private static void explosion(Location at, List<Block> removed, @Nullable Entity source) {
        float power = Math.max(1f, removed.size() / 12f);
        BlackBox box = ReplayRuntime.box();
        if (box != null) box.markAt(ReplayMark.EXPLOSION, null, at, null, power);
        ReplayRuntime.watching(at, recording -> recording.explosion(at, power));
        blocks(removed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        int now = ReplayClock.now();
        if (flowTick.getAndSet(now) != now) flows.set(0);
        if (flows.incrementAndGet() > FLOWS_PER_TICK) return;
        block(event.getToBlock());
    }

    /**
     * A liquid's level changing or drying up, which is not a flow: without it
     * water that ran and then went back is never seen running.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevel(FluidLevelChangeEvent event) {
        int now = ReplayClock.now();
        if (flowTick.getAndSet(now) != now) flows.set(0);
        if (flows.incrementAndGet() > FLOWS_PER_TICK) return;
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        piston(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        piston(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    /** Every position a piston touches: itself, its head, and both ends of what it moves. */
    private static void piston(Block piston, List<Block> moved, BlockFace direction) {
        block(piston);
        block(piston.getRelative(direction));
        for (Block block : moved) {
            block(block);
            block(block.getRelative(direction));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructure(StructureGrowEvent event) {
        states(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        states(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent event) {
        block(event.getBlock());
        states(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent event) {
        block(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent event) {
        block(event.getBlock());
    }

    /**
     * A door opened, a lever pulled, a note block tuned, an anchor charged:
     * changes the game makes without a block event of their own.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        BlockData data = block.getBlockData();
        if (data instanceof Openable || data instanceof Powerable || data instanceof NoteBlock
                || data instanceof Repeater || data instanceof Comparator
                || data instanceof DaylightDetector || data instanceof Cake
                || data instanceof RespawnAnchor) {
            block(block, data);
            // A door is two blocks and both change.
            if (data instanceof org.bukkit.block.data.Bisected half && data instanceof Openable) {
                Block other = block.getRelative(half.getHalf() == org.bukkit.block.data.Bisected.Half.TOP
                        ? BlockFace.DOWN : BlockFace.UP);
                block(other);
            }
        }
    }

    /** Events newer than the oldest server the library runs on. */
    static final class Recent implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onProgress(BlockBreakProgressUpdateEvent event) {
            Block block = event.getBlock();
            int stage = event.getProgress() <= 0 || event.getProgress() >= 1
                    ? -1 : (int) (event.getProgress() * 10f);
            place(ReplayMark.BREAKING, event.getEntity(), block.getLocation(), String.valueOf(stage));
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onShield(PlayerShieldDisableEvent event) {
            actor(ReplayMark.SHIELD_DISABLED, event.getPlayer(), null);
        }
    }
}
