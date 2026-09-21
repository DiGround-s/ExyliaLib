package net.exylia.lib.util.crate.internal;

import net.exylia.lib.block.BlockClick;
import net.exylia.lib.block.Blocks;
import net.exylia.lib.block.ClickableBlock;
import net.exylia.lib.block.PluginBlocks;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The blocks that open a crate when they are clicked.
 *
 * <p>A list in the plugin's configuration rather than anything written into the
 * world, so the blocks survive a world edit, a schematic paste and a rollback.
 * Registered through the clickable block module, which is what keeps them from
 * being broken, blown up or pushed away, and what stops a click on a crate drawn
 * as a barrel from opening a barrel.
 *
 * <p>Only the registrations made here are taken down again: the plugin may have
 * clickable blocks of its own.
 */
public final class BoundBlocks implements Listener {

    private final PluginBlocks blocks;
    private final Debug debug;
    private final Supplier<List<String>> stored;
    private final Consumer<List<String>> save;
    private final Consumer<BlockClick> open;
    private final List<ClickableBlock> registered = new ArrayList<>();

    public BoundBlocks(@NotNull Plugin plugin, @NotNull Supplier<List<String>> stored,
                       @NotNull Consumer<List<String>> save, @NotNull Consumer<BlockClick> open) {
        this.blocks = Blocks.of(plugin);
        this.debug = Debug.of(plugin);
        this.stored = stored;
        this.save = save;
        this.open = open;
    }

    /**
     * Registers every block the settings name, dropping what was registered.
     *
     * <p>Rebuilt whole rather than patched: the list can have changed in any
     * direction, and there are only ever a handful of blocks.
     */
    public synchronized void rebuild() {
        unregister();
        int count = register(null);
        if (count > 0) debug.debug("Registered " + count + " crate blocks");
    }

    /** Takes down every block registered here. */
    public synchronized void unregister() {
        registered.forEach(ClickableBlock::unregister);
        registered.clear();
    }

    /**
     * A world loaded after the plugin: a hub a world manager loads is not there
     * when the plugin enables, and its blocks would stop answering after every
     * restart.
     */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        String world = event.getWorld().getName();
        int count;
        synchronized (this) {
            count = register(world);
        }
        if (count > 0) debug.debug("Registered " + count + " crate blocks in " + world);
    }

    private int register(@Nullable String onlyWorld) {
        int count = 0;
        for (String entry : stored.get()) {
            if (onlyWorld != null && !onlyWorld.equals(worldOf(entry))) continue;
            Location where = parse(entry);
            if (where == null) {
                // Only while registering everything: a world that turns up later
                // says so once, here, rather than on every world loaded afterwards.
                if (onlyWorld == null) {
                    debug.warn("Crate block '" + entry + "' names a world that is not loaded;"
                            + " it is registered when that world loads");
                }
                continue;
            }
            registered.add(blocks.at(where).onClick(open).register());
            count++;
        }
        return count;
    }

    /** Binds a block; answers whether it was not bound already. */
    public boolean add(@NotNull Location where) {
        String entry = key(where);
        if (stored.get().stream().anyMatch(other -> entry.equals(normalise(other)))) return false;
        change(list -> {
            list.add(entry);
            return list;
        });
        return true;
    }

    /** Unbinds a block; answers whether it was bound. */
    public boolean remove(@NotNull Location where) {
        String entry = key(where);
        if (stored.get().stream().noneMatch(other -> entry.equals(normalise(other)))) return false;
        change(list -> {
            list.removeIf(other -> entry.equals(normalise(other)));
            return list;
        });
        return true;
    }

    /** Unbinds every block; answers how many there were. */
    public int clear() {
        int had = stored.get().size();
        if (had > 0) {
            change(list -> {
                list.clear();
                return list;
            });
        }
        return had;
    }

    public @NotNull List<String> all() {
        return List.copyOf(stored.get());
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        unregister();
    }

    private void change(UnaryOperator<List<String>> edit) {
        save.accept(List.copyOf(edit.apply(new ArrayList<>(stored.get()))));
        rebuild();
    }

    /** How a block is written down: the block's own corner, facing nowhere. */
    public static @NotNull String key(@NotNull Location where) {
        Location corner = new Location(where.getWorld(), where.getBlockX(), where.getBlockY(), where.getBlockZ());
        return ExyliaLocation.of(corner).toString();
    }

    /**
     * The same entry with any drift squeezed out: one typed by hand can name the
     * middle of the block or carry a yaw, and is still the same block.
     */
    private static @NotNull String normalise(@NotNull String entry) {
        Location where = parse(entry);
        return where == null ? entry.trim() : key(where);
    }

    private static @Nullable Location parse(@NotNull String entry) {
        try {
            return ExyliaLocation.fromString(entry).toBukkitLocation();
        } catch (RuntimeException notALocation) {
            return null;
        }
    }

    private static @Nullable String worldOf(@NotNull String entry) {
        try {
            return ExyliaLocation.fromString(entry).world();
        } catch (RuntimeException notALocation) {
            return null;
        }
    }
}
