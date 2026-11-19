package net.exylia.lib.node.internal;

import net.exylia.lib.node.ClientNode;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One node: its two entity ids on the viewer's client and what it looks like.
 *
 * <p>Changes and the visibility pass both go through this object's lock, so a
 * look sent while the node is being taken off screen cannot reach the client
 * after the removal.
 */
final class NodeImpl implements ClientNode {

    /** How small a pop shrinks it, and how fast it goes and comes back. */
    private static final float POP_SIZE = 0.82f;
    private static final int POP_IN_TICKS = 2;
    private static final int POP_OUT_TICKS = 3;

    final NodeRuntime.Owner owner;
    final Player viewer;
    final Location at;
    final int displayId;
    final int hitboxId;

    /** A {@link BlockData} or an {@link ItemStack}; the kind never changes. */
    private Object look;
    private Integer glow;
    private float size = 1f;
    /** Whether the viewer's client has it now. */
    private boolean shown;
    private boolean removed;

    NodeImpl(NodeRuntime.Owner owner, Player viewer, Location at, Object look, int displayId, int hitboxId) {
        this.owner = owner;
        this.viewer = viewer;
        this.at = at;
        this.look = look;
        this.displayId = displayId;
        this.hitboxId = hitboxId;
    }

    boolean isBlock() {
        return look instanceof BlockData;
    }

    synchronized Object look() {
        return look;
    }

    synchronized Integer glowColor() {
        return glow;
    }

    synchronized float size() {
        return size;
    }

    synchronized boolean shown() {
        return shown;
    }

    /** The centre of what the viewer sees, which is what reach is measured to. */
    double centerX() {
        return isBlock() ? at.getX() + 0.5 : at.getX();
    }

    double centerY() {
        return isBlock() ? at.getY() + 0.5 : at.getY();
    }

    double centerZ() {
        return isBlock() ? at.getZ() + 0.5 : at.getZ();
    }

    /** Puts it on the viewer's screen or takes it off. Called by the visibility pass. */
    synchronized void show(boolean show) {
        if (removed || show == shown) {
            return;
        }
        shown = show;
        if (show) {
            NodeRuntime.sink().spawn(this);
        } else {
            NodeRuntime.sink().despawn(this);
        }
    }

    /** The client dropped it on its own (respawn, world change): sent again on the next pass. */
    synchronized void dropped() {
        shown = false;
    }

    /** Forgets it without a packet: its viewer left, or it is being removed. */
    synchronized boolean retire() {
        if (removed) {
            return false;
        }
        removed = true;
        return true;
    }

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public @NotNull Location location() {
        return at.clone();
    }

    @Override
    public synchronized void look(@NotNull BlockData block) {
        if (!isBlock()) {
            throw new IllegalStateException("An item node cannot look like a block");
        }
        look = block;
        if (shown && !removed) {
            NodeRuntime.sink().look(this);
        }
    }

    @Override
    public synchronized void look(@NotNull ItemStack item) {
        if (isBlock()) {
            throw new IllegalStateException("A block node cannot look like an item");
        }
        look = item.clone();
        if (shown && !removed) {
            NodeRuntime.sink().look(this);
        }
    }

    @Override
    public synchronized void glow(@Nullable Color color) {
        Integer next = color == null ? null : color.asRGB();
        if (java.util.Objects.equals(next, glow)) {
            return;
        }
        glow = next;
        if (shown && !removed) {
            NodeRuntime.sink().glow(this);
        }
    }

    @Override
    public synchronized void scale(float size, int ticks) {
        this.size = Math.max(0f, size);
        if (shown && !removed) {
            NodeRuntime.sink().scale(this, this.size, Math.max(0, ticks));
        }
    }

    @Override
    public void pop() {
        float full;
        synchronized (this) {
            if (!shown || removed) {
                return;
            }
            full = size;
            NodeRuntime.sink().scale(this, POP_SIZE * full, POP_IN_TICKS);
        }
        NodeRuntime.later(POP_IN_TICKS, () -> {
            synchronized (this) {
                // A scale asked for meanwhile wins over the spring back.
                if (shown && !removed && size == full) {
                    NodeRuntime.sink().scale(this, full, POP_OUT_TICKS);
                }
            }
        });
    }

    @Override
    public void remove() {
        NodeRuntime.remove(this);
    }

    /** Takes it off screen and marks it removed. Called by the runtime only. */
    synchronized void destroy() {
        if (removed) {
            return;
        }
        if (shown) {
            NodeRuntime.sink().despawn(this);
            shown = false;
        }
        removed = true;
    }

    @Override
    public synchronized boolean isRemoved() {
        return removed;
    }
}
