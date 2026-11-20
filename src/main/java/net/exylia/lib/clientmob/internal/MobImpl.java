package net.exylia.lib.clientmob.internal;

import net.exylia.lib.clientmob.ClientMob;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One mob: its entity id on the viewer's client and what it looks like.
 *
 * <p>Changes, walking steps and the visibility pass go through this object's
 * lock, so a packet cannot reach the client after the mob was taken off it.
 */
final class MobImpl implements ClientMob {

    /** Beyond this a step is a teleport: a relative move holds 8 blocks. */
    private static final double MAX_RELATIVE = 7.0;

    final MobRuntime.Owner owner;
    final Player viewer;
    final EntityType type;
    final int id;
    final UUID uuid = UUID.randomUUID();

    private final World world;
    private double x, y, z;
    private float yaw, pitch;

    private String name;
    private Object nameComponent;
    private Integer glow;
    private boolean sitting;
    private boolean baby;
    private boolean shown;
    private boolean removed;

    private double tx, ty, tz;
    private double speed;
    private CompletableFuture<Void> walk;

    MobImpl(MobRuntime.Owner owner, Player viewer, EntityType type, Location at, int id) {
        this.owner = owner;
        this.viewer = viewer;
        this.type = type;
        this.id = id;
        this.world = at.getWorld();
        this.x = at.getX();
        this.y = at.getY();
        this.z = at.getZ();
        this.yaw = at.getYaw();
        this.pitch = at.getPitch();
    }

    World world() {
        return world;
    }

    synchronized double x() { return x; }
    synchronized double y() { return y; }
    synchronized double z() { return z; }
    synchronized float yaw() { return yaw; }
    synchronized float pitch() { return pitch; }
    synchronized boolean shown() { return shown; }
    synchronized Integer glowColor() { return glow; }
    synchronized boolean isSitting() { return sitting; }
    synchronized boolean isBaby() { return baby; }
    synchronized @Nullable Object nameComponent() { return nameComponent; }
    synchronized boolean walking() { return walk != null; }

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public synchronized @NotNull Location location() {
        return new Location(world, x, y, z, yaw, pitch);
    }

    synchronized void show(boolean show) {
        if (removed || show == shown) {
            return;
        }
        shown = show;
        if (show) {
            MobRuntime.sink().spawn(this);
        } else {
            MobRuntime.sink().despawn(this);
        }
    }

    /** The client dropped it on its own (respawn, world change): sent again on the next pass. */
    synchronized void dropped() {
        shown = false;
    }

    @Override
    public @NotNull CompletableFuture<Void> moveTo(@NotNull Location target, double blocksPerSecond) {
        if (target.getWorld() != world) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A mob walks inside its own world"));
        }
        if (!(blocksPerSecond > 0)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Speed must be above 0"));
        }
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> replaced;
        synchronized (this) {
            if (removed) {
                return CompletableFuture.completedFuture(null);
            }
            replaced = walk;
            walk = mine;
            tx = target.getX();
            ty = target.getY();
            tz = target.getZ();
            speed = blocksPerSecond;
        }
        MobRuntime.startMoving(this);
        if (replaced != null) {
            replaced.complete(null);
        }
        return mine;
    }

    /**
     * One walking step of {@code seconds}.
     *
     * @return whether it is done walking
     */
    boolean advance(double seconds) {
        CompletableFuture<Void> done = null;
        boolean finished;
        synchronized (this) {
            if (walk == null || removed) {
                return true;
            }
            double dx = tx - x;
            double dz = tz - z;
            if (dx * dx + dz * dz > 1.0E-4) {
                yaw = MobMath.yaw(dx, dz);
            }
            pitch = 0f;
            MobMath.Step step = MobMath.step(x, y, z, tx, ty, tz, speed * seconds);
            double mx = step.x() - x;
            double my = step.y() - y;
            double mz = step.z() - z;
            x = step.x();
            y = step.y();
            z = step.z();
            if (shown) {
                // The last step is a teleport so the client's rounding never piles up.
                if (step.arrived() || mx * mx + my * my + mz * mz > MAX_RELATIVE * MAX_RELATIVE) {
                    MobRuntime.sink().teleport(this);
                } else {
                    MobRuntime.sink().move(this, mx, my, mz);
                }
            }
            if (step.arrived()) {
                done = walk;
                walk = null;
            }
            finished = walk == null;
        }
        if (done != null) {
            done.complete(null);
        }
        return finished;
    }

    private CompletableFuture<Void> stopWalking() {
        CompletableFuture<Void> old = walk;
        walk = null;
        return old;
    }

    @Override
    public void teleport(@NotNull Location to) {
        CompletableFuture<Void> old;
        synchronized (this) {
            if (removed) {
                return;
            }
            old = stopWalking();
            x = to.getX();
            y = to.getY();
            z = to.getZ();
            yaw = to.getYaw();
            pitch = to.getPitch();
            if (shown) {
                MobRuntime.sink().teleport(this);
            }
        }
        if (old != null) {
            old.complete(null);
        }
    }

    @Override
    public synchronized void lookAt(@NotNull Location point) {
        if (removed) {
            return;
        }
        double dx = point.getX() - x;
        double dy = point.getY() - (y + 1.0);
        double dz = point.getZ() - z;
        if (dx * dx + dz * dz > 1.0E-6) {
            yaw = MobMath.yaw(dx, dz);
        }
        pitch = MobMath.pitch(dx, dy, dz);
        if (shown) {
            MobRuntime.sink().rotate(this);
        }
    }

    @Override
    public synchronized void swing() {
        if (shown && !removed) {
            MobRuntime.sink().swing(this);
        }
    }

    @Override
    public synchronized void status(byte status) {
        if (shown && !removed) {
            MobRuntime.sink().status(this, status);
        }
    }

    @Override
    public synchronized void name(@Nullable String miniMessage) {
        if (Objects.equals(name, miniMessage)) {
            return;
        }
        name = miniMessage;
        nameComponent = miniMessage == null ? null : net.exylia.lib.text.Text.component(miniMessage);
        if (shown && !removed) {
            MobRuntime.sink().state(this);
        }
    }

    @Override
    public synchronized void glow(@Nullable Color color) {
        Integer next = color == null ? null : color.asRGB();
        if (Objects.equals(next, glow)) {
            return;
        }
        glow = next;
        if (shown && !removed) {
            MobRuntime.sink().glow(this);
        }
    }

    @Override
    public synchronized void sitting(boolean sitting) {
        if (this.sitting == sitting) {
            return;
        }
        this.sitting = sitting;
        if (shown && !removed) {
            MobRuntime.sink().state(this);
        }
    }

    @Override
    public synchronized void baby(boolean baby) {
        if (this.baby == baby) {
            return;
        }
        this.baby = baby;
        if (shown && !removed) {
            MobRuntime.sink().state(this);
        }
    }

    @Override
    public void remove() {
        MobRuntime.remove(this);
    }

    /** Takes it off screen and marks it removed. Called by the runtime only. */
    void destroy() {
        CompletableFuture<Void> old;
        synchronized (this) {
            if (removed) {
                return;
            }
            if (shown) {
                MobRuntime.sink().despawn(this);
                shown = false;
            }
            removed = true;
            old = stopWalking();
        }
        if (old != null) {
            old.complete(null);
        }
    }

    /** Forgets it without a packet: its viewer left. */
    void retire() {
        CompletableFuture<Void> old;
        synchronized (this) {
            removed = true;
            old = stopWalking();
        }
        if (old != null) {
            old.complete(null);
        }
    }

    @Override
    public synchronized boolean isRemoved() {
        return removed;
    }
}
