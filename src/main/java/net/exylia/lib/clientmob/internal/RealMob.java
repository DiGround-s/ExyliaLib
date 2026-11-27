package net.exylia.lib.clientmob.internal;

import com.destroystokyo.paper.entity.Pathfinder;
import net.exylia.lib.clientmob.ClientMob;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Flying;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * A mob that is a real server entity, shown to one player only. Its vanilla AI is off; it walks a path the server's
 * pathfinder finds, with gravity and collisions, so it climbs steps and goes round walls instead of through them.
 * A walk that gets stuck ends with a teleport to the target, so the mob never stays behind.
 *
 * <p>Every call is queued and applied on the entity's own thread on its next tick, so any thread may call.
 */
final class RealMob implements ClientMob {

    /** Types that fly: no gravity, a straight line in three dimensions instead of a path. */
    private static final Set<EntityType> FLYERS = Set.of(EntityType.ALLAY, EntityType.BEE, EntityType.PARROT,
            EntityType.VEX, EntityType.BAT, EntityType.BLAZE, EntityType.GHAST, EntityType.PHANTOM);
    /** Status 4 is these types' own attack; the rest swing their arm. */
    private static final Set<EntityType> STATUS_ATTACK = Set.of(
            EntityType.IRON_GOLEM, EntityType.RAVAGER, EntityType.HOGLIN, EntityType.ZOGLIN, EntityType.WARDEN);
    private static final double JUMP = 0.42;
    /** Ticks without moving a tenth of a block before a walk gives up and teleports. */
    private static final int STUCK_TICKS = 30;

    final MobRuntime.Owner owner;
    final Player viewer;
    final EntityType type;
    private final boolean flies;
    private final TaskScheduler tasks;
    private final Queue<Consumer<Mob>> pending = new ConcurrentLinkedQueue<>();

    private volatile Mob entity;
    private volatile Location location;
    private volatile boolean removed;
    volatile boolean clickThrough;
    private volatile Integer glow;
    private TaskHandle ticker;

    // Walk state, entity thread only.
    private CompletableFuture<Void> walk;
    private Location target;
    private List<Location> path;
    private int next;
    private double perTick;
    private int still;
    private Location lastAt;
    private long deadline;

    RealMob(MobRuntime.Owner owner, TaskScheduler tasks, Player viewer, EntityType type, Location at) {
        this.owner = owner;
        this.tasks = tasks;
        this.viewer = viewer;
        this.type = type;
        this.flies = FLYERS.contains(type) || Flying.class.isAssignableFrom(type.getEntityClass());
        this.location = at.clone();
        tasks.runAtLocation(at, () -> spawn(at));
    }

    @SuppressWarnings("unchecked")
    private void spawn(Location at) {
        if (removed || !viewer.isOnline()) {
            MobRuntime.unregister(this);
            return;
        }
        Mob mob = at.getWorld().spawn(at, (Class<? extends Mob>) type.getEntityClass(),
                CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
                    spawned.setVisibleByDefault(false);
                    spawned.setPersistent(false);
                    spawned.setRemoveWhenFarAway(false);
                    spawned.setInvulnerable(true);
                    spawned.setSilent(true);
                    spawned.setCollidable(false);
                    spawned.setCanPickupItems(false);
                    spawned.setAware(false); // no vanilla AI, goals or brain: it only does what it is told
                    if (flies) spawned.setGravity(false);
                    if (spawned instanceof Breedable breedable) breedable.setAgeLock(true);
                    if (spawned instanceof Zombie zombie) zombie.setShouldBurnInDay(false);
                    if (spawned instanceof AbstractSkeleton skeleton) skeleton.setShouldBurnInDay(false);
                    if (spawned instanceof Phantom phantom) phantom.setShouldBurnInDay(false);
                });
        entity = mob;
        MobRuntime.registerReal(this, mob.getEntityId(), mob.getUniqueId());
        tasks.runAtEntity(viewer, () -> {
            if (!removed && mob.isValid()) viewer.showEntity(owner.plugin, mob);
        });
        ticker = tasks.runAtEntityTimer(mob, 1, 1, this::tick);
        if (removed) mob.remove();
    }

    private void tick() {
        Mob mob = entity;
        if (mob == null) return;
        if (removed || !mob.isValid()) {
            stopWalk();
            if (ticker != null) ticker.cancel();
            if (mob.isValid()) mob.remove();
            MobRuntime.unregister(this);
            return;
        }
        Consumer<Mob> op;
        while ((op = pending.poll()) != null) op.accept(mob);
        if (walk != null) step(mob);
        location = mob.getLocation();
    }

    private void step(Mob mob) {
        Location at = mob.getLocation();
        if (at.getWorld() != target.getWorld() || System.currentTimeMillis() > deadline) {
            arrive(mob, true);
            return;
        }
        still = lastAt != null && lastAt.distanceSquared(at) < 0.01 ? still + 1 : 0;
        lastAt = at;
        if (still > STUCK_TICKS) {
            arrive(mob, true);
            return;
        }
        Location aim = path != null && next < path.size() ? path.get(next) : target;
        double dx = aim.getX() - at.getX();
        double dy = aim.getY() - at.getY();
        double dz = aim.getZ() - at.getZ();
        double flat = Math.hypot(dx, dz);
        if (aim != target && flat < 0.4 && Math.abs(dy) < 1.2) {
            next++;
            return;
        }
        if (aim == target && flat < Math.max(0.15, perTick) && (flies ? Math.abs(dy) < 0.3 : Math.abs(dy) < 1.2)) {
            arrive(mob, false);
            return;
        }
        double length = flies ? Math.sqrt(flat * flat + dy * dy) : flat;
        double move = Math.min(perTick, length) / Math.max(length, 1e-6);
        double vy = flies ? dy * move : mob.getVelocity().getY();
        if (!flies && mob.isOnGround() && dy > 0.5) vy = JUMP;
        mob.setVelocity(new Vector(dx * move, vy, dz * move));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mob.setRotation(yaw, 0);
        mob.setBodyYaw(yaw);
    }

    private void arrive(Mob mob, boolean teleport) {
        CompletableFuture<Void> done = walk;
        Location to = target;
        walk = null;
        path = null;
        still = 0;
        lastAt = null;
        mob.setVelocity(new Vector());
        if (teleport && to != null) {
            mob.teleportAsync(to).whenComplete((moved, failed) -> {
                if (done != null) done.complete(null);
            });
        } else if (done != null) {
            done.complete(null);
        }
    }

    private void stopWalk() {
        CompletableFuture<Void> done = walk;
        walk = null;
        path = null;
        if (done != null) done.complete(null);
    }

    private void queue(Consumer<Mob> op) {
        if (!removed) pending.add(op);
    }

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public @NotNull Location location() {
        return location.clone();
    }

    @Override
    public @NotNull CompletableFuture<Void> moveTo(@NotNull Location to, double blocksPerSecond) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Location goal = to.clone();
        if (removed) {
            future.complete(null);
            return future;
        }
        queue(mob -> {
            stopWalk();
            walk = future;
            target = goal;
            perTick = Math.max(0.01, blocksPerSecond) / 20.0;
            next = 0; // the stall count carries over: a follower re-aimed every few ticks still gets unstuck
            Location from = mob.getLocation();
            double distance = from.getWorld() == goal.getWorld() ? from.distance(goal) : 0;
            deadline = System.currentTimeMillis() + (long) (distance / Math.max(0.01, blocksPerSecond) * 2500) + 2000;
            path = null;
            if (!flies && distance > 1.5) {
                Pathfinder.PathResult found = mob.getPathfinder().findPath(goal);
                if (found != null && found.canReachFinalPoint()) {
                    path = found.getPoints().stream().map(point -> point.add(0.5, 0, 0.5)).toList();
                }
            }
        });
        return future;
    }

    @Override
    public void teleport(@NotNull Location to) {
        Location goal = to.clone();
        queue(mob -> {
            stopWalk();
            mob.teleportAsync(goal);
        });
    }

    @Override
    public void lookAt(@NotNull Location point) {
        Location aim = point.clone();
        queue(mob -> {
            Location at = mob.getLocation();
            float yaw = (float) Math.toDegrees(Math.atan2(-(aim.getX() - at.getX()), aim.getZ() - at.getZ()));
            mob.setRotation(yaw, 0);
            mob.setBodyYaw(yaw);
        });
    }

    @Override
    public void swing() {
        queue(mob -> {
            if (STATUS_ATTACK.contains(type)) MobRuntime.sendStatus(viewer, mob.getEntityId(), (byte) 4);
            else mob.swingMainHand();
        });
    }

    @Override
    public void status(byte status) {
        queue(mob -> MobRuntime.sendStatus(viewer, mob.getEntityId(), status));
    }

    @Override
    public void name(@Nullable String miniMessage) {
        queue(mob -> {
            mob.customName(miniMessage == null ? null : net.exylia.lib.text.Text.component(miniMessage));
            mob.setCustomNameVisible(miniMessage != null);
        });
    }

    @Override
    public void glow(@Nullable Color color) {
        Integer rgb = color == null ? null : color.asRGB();
        queue(mob -> {
            mob.setGlowing(rgb != null);
            if (glow != null) MobRuntime.sendTeam(viewer, mob.getUniqueId(), null);
            glow = rgb;
            if (rgb != null) MobRuntime.sendTeam(viewer, mob.getUniqueId(), rgb);
        });
    }

    @Override
    public void sitting(boolean sitting) {
        queue(mob -> {
            if (sitting) stopWalk();
            if (mob instanceof Sittable sittable) sittable.setSitting(sitting);
            else if (sitting) mob.setPose(Pose.SNEAKING, true);
            else mob.setPose(Pose.STANDING, false);
        });
    }

    @Override
    public void baby(boolean baby) {
        queue(mob -> {
            if (!(mob instanceof Ageable ageable)) return;
            if (baby) ageable.setBaby();
            else ageable.setAdult();
        });
    }

    @Override
    public void clickThrough(boolean through) {
        this.clickThrough = through;
    }

    @Override
    public void remove() {
        if (removed) return;
        removed = true;
        Mob mob = entity;
        if (mob == null) return; // spawn sees the flag
        if (glow != null) MobRuntime.sendTeam(viewer, mob.getUniqueId(), null);
        tasks.runAtEntity(mob, () -> {
            stopWalk();
            if (ticker != null) ticker.cancel();
            mob.remove();
        });
        MobRuntime.unregister(this);
    }

    /** The viewer left: the entity goes, no packets. */
    void retire() {
        removed = true;
        Mob mob = entity;
        if (mob == null) return;
        try {
            tasks.runAtEntity(mob, mob::remove);
        } catch (RuntimeException shuttingDown) {
            // Not persistent: a server going down does not save it either.
        }
    }

    @Override
    public boolean isRemoved() {
        Mob mob = entity;
        return removed || (mob != null && !mob.isValid());
    }

    /** Where it stands for the reach check of a click. */
    @Nullable LivingEntity body() {
        return entity;
    }
}
