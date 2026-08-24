package net.exylia.lib.skull.internal;

import net.exylia.lib.skull.SkullHandle;
import net.exylia.lib.skull.SkullSource;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The handle a caller gets back, and the late swap that makes menus work.
 *
 * <p>Holds the finished item when it has one and a plain head when it does
 * not, and arranges for the callback to run on a thread allowed to touch
 * inventories.
 */
public final class Handle implements SkullHandle {

    private final SkullSource source;

    /** Applied to every item handed out: name, lore, amount, glow. */
    private final UnaryOperator<ItemStack> decorator;

    /** Whose thread the callback runs on, for Folia. May be {@code null}. */
    private final Player viewer;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean delivered = new AtomicBoolean();

    private volatile String texture;
    private volatile Consumer<ItemStack> action;

    Handle(SkullSource source, String texture, UnaryOperator<ItemStack> decorator,
           @Nullable Player viewer) {
        this.source = source;
        this.texture = texture;
        this.decorator = decorator;
        this.viewer = viewer;
    }

    @Override
    public @NotNull ItemStack item() {
        String known = texture;
        ItemStack head = known == null
                ? HeadFactory.create(SkullRuntime.fallback())
                : HeadFactory.create(known);
        return decorator.apply(head);
    }

    @Override
    public boolean isReady() {
        return texture != null;
    }

    @Override
    public @Nullable String texture() {
        return texture;
    }

    @Override
    public @NotNull SkullHandle onReady(@NotNull Consumer<ItemStack> action) {
        if (texture != null) {
            // Already known: run now, in the caller's thread, so a warm menu
            // is built in one pass with no scheduling at all.
            if (delivered.compareAndSet(false, true)) {
                action.accept(item());
            }
            return this;
        }
        this.action = action;
        return this;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        action = null;
    }

    /**
     * Called when the texture lands.
     *
     * <p>Hops onto the viewer's thread — on Folia that is the only thread
     * allowed to touch their inventory, and everywhere else it is the main
     * thread, which is where a menu must be edited anyway.
     */
    void complete(String resolved) {
        if (resolved == null || cancelled.get()) {
            return;
        }
        this.texture = resolved;
        Consumer<ItemStack> pending = action;
        if (pending == null || !delivered.compareAndSet(false, true)) {
            return;
        }
        action = null;
        TaskScheduler tasks = SkullRuntime.scheduler();
        if (tasks == null) {
            return;
        }
        Runnable deliver = () -> {
            if (!cancelled.get()) {
                pending.accept(item());
            }
        };
        if (viewer != null) {
            tasks.runAtEntity(viewer, deliver, null);
        } else {
            tasks.run(deliver);
        }
    }

    /** The source this handle was made for. */
    public SkullSource source() {
        return source;
    }
}
