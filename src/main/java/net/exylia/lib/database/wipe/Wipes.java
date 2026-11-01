package net.exylia.lib.database.wipe;

import net.exylia.lib.database.wipe.internal.WipeMenu;
import net.exylia.lib.database.wipe.internal.WipeRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A plugin's part in the library's wipes and player resets.
 *
 * <p>The wipe itself is the library's: {@code /exylialib wipe} picks tables by
 * the {@link net.exylia.lib.database.DataKind} each {@code @Table} declares,
 * backs them up, deletes, and locks the network. A plugin only declares what
 * its tables hold, and — when it keeps a player's rows in memory beyond their
 * session — what to forget when that player is reset:
 *
 * <pre>{@code
 * Wipes.of(this).onPlayerReset(uuid -> topList.evict(uuid));
 * }</pre>
 *
 * <p>A plugin that loads a player on join and drops them on quit needs nothing:
 * a reset player is kicked and locked out until their rows are gone. Whole
 * wipes need no hook either; the emptied tables are frozen until a restart.
 *
 * @since 1.439.0
 */
public final class Wipes {

    private final Plugin plugin;

    private Wipes(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The plugin's part.
     *
     * @param plugin the plugin
     * @return a handle; cheap, make one whenever
     */
    public static @NotNull Wipes of(@NotNull Plugin plugin) {
        return new Wipes(Objects.requireNonNull(plugin, "plugin"));
    }

    /**
     * Runs when a player's rows were deleted by a player reset.
     *
     * <p>Runs on <b>every server of the network</b> where this plugin is
     * installed, on a background thread, after the rows are gone and while the
     * player is still locked out. Forget what is cached about them: an indexed
     * map, a top list, a clan's member list. Hop through {@code Tasks} before
     * touching the world. Released when the plugin disables.
     *
     * @param hook what to forget, given the player's id
     * @return this handle
     */
    public @NotNull Wipes onPlayerReset(@NotNull Consumer<UUID> hook) {
        WipeRuntime.hook(plugin, Objects.requireNonNull(hook, "hook"));
        return this;
    }

    /**
     * Opens the library's player reset for one player, ready to confirm.
     *
     * <p>What a plugin's "reset this player" button calls instead of deleting
     * rows itself: the viewer sees every table that holds the player's rows,
     * by kind, takes a backup and confirms. With {@code only} the reset is
     * narrowed to that plugin's tables; the viewer can still review them.
     *
     * @param viewer who resets; needs {@code exylialib.admin.wipe}
     * @param target whose rows
     * @param name   what to call them
     * @param only   only this plugin's tables, or {@code null} for every plugin
     */
    public static void openPlayerReset(@NotNull Player viewer, @NotNull UUID target, @NotNull String name,
                                       @Nullable Plugin only) {
        WipeMenu.openPlayerReset(viewer, target, name, only == null ? null : only.getName());
    }

    /**
     * Forgets a plugin's hooks. The library calls this when the plugin disables.
     *
     * @param plugin the plugin
     */
    public static void release(@NotNull Plugin plugin) {
        WipeRuntime.release(plugin);
    }
}
