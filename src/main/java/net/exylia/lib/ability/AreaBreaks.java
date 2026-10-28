package net.exylia.lib.ability;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breaking many blocks at once without the lag: 3x3, 3x3x3, vein mining,
 * lumberjack, excavation.
 *
 * <pre>{@code
 * AreaBreaks.of(plugin)
 *         .around(player, event.getBlock(), AreaShape.plane(1, 0))
 *         .check((breaker, block) -> worldGuard.canBreak(breaker, block))
 *         .start();
 * }</pre>
 *
 * <h2>Why it is cheap</h2>
 * <ul>
 *   <li>The area is planned once into packed coordinates in one primitive
 *       array: no {@code Location} or {@code Block} per planned block. Air,
 *       liquids, unbreakables, the blacklist and blocks the tool is not
 *       effective on are left out while planning, with the tool check cached
 *       per material.</li>
 *   <li>No {@code BlockBreakEvent} per block by default. Protection is one
 *       ExyliaLib region lookup ({@code break}) per block, plus the caller's
 *       {@link AreaBreak#check}. {@link AreaBreak#compatEvents(int)} fires
 *       real events for jobs and skills plugins, off by default and capped.</li>
 *   <li>A budget of blocks per tick (64 by default); the rest continues next
 *       tick on the breaker's scheduler. A 3x3x3 or a 64-block vein ends in the
 *       tick it started.</li>
 *   <li>Drops merge into one list and land once at the end; experience is one
 *       orb; the tool loses durability once, Unbreaking rolled in one pass.</li>
 *   <li>Blocks change without physics, except next to a liquid or a falling
 *       block, where the neighbours must notice.</li>
 *   <li>No recursion: while it fires compatibility events,
 *       {@link #isBreaking()} is true, and abilities ignore those events.</li>
 *   <li>Folia: only blocks the current region owns are touched; the rest are
 *       skipped.</li>
 * </ul>
 *
 * <p>Start it on the breaker's thread, normally inside their
 * {@code BlockBreakEvent}. Running operations end with the plugin; a breaker
 * who quits gets the drops collected so far at the origin.
 *
 * @since 1.385.0
 */
public final class AreaBreaks {

    private static final ThreadLocal<int[]> BREAKING = ThreadLocal.withInitial(() -> new int[1]);
    private static final Map<String, Set<AreaBreak>> RUNNING = new ConcurrentHashMap<>();
    private static final Set<String> LISTENING = ConcurrentHashMap.newKeySet();

    private final Plugin plugin;

    private AreaBreaks(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Area breaks on behalf of a plugin. The first call registers a quit
     * listener under it, so call from {@code onEnable} or later.
     *
     * @param plugin the owner
     * @return the entry point
     */
    public static @NotNull AreaBreaks of(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (LISTENING.add(plugin.getName())) {
            try {
                Bukkit.getPluginManager().registerEvents(new QuitListener(plugin.getName()), plugin);
            } catch (RuntimeException notEnabled) {
                LISTENING.remove(plugin.getName());
            }
        }
        return new AreaBreaks(plugin);
    }

    /**
     * An operation around a block, ready to be tuned and {@linkplain AreaBreak#start() started}.
     *
     * @param breaker who breaks: their tool, drops and protection
     * @param origin  the block they broke; it is left to its own event
     * @param shape   which blocks around it
     * @return the operation
     */
    public @NotNull AreaBreak around(@NotNull Player breaker, @NotNull Block origin, @NotNull AreaShape shape) {
        return new AreaBreak(plugin, breaker, origin, shape);
    }

    /**
     * Whether this thread is inside a compatibility {@code BlockBreakEvent}
     * fired by an area break. A listener that reacts to breaks with more breaks
     * returns early when it is.
     */
    public static boolean isBreaking() {
        return BREAKING.get()[0] > 0;
    }

    static void enterBreaking() {
        BREAKING.get()[0]++;
    }

    static void exitBreaking() {
        BREAKING.get()[0]--;
    }

    static void track(String plugin, AreaBreak run) {
        RUNNING.computeIfAbsent(plugin, name -> ConcurrentHashMap.newKeySet()).add(run);
    }

    static void untrack(String plugin, AreaBreak run) {
        Set<AreaBreak> runs = RUNNING.get(plugin);
        if (runs != null) runs.remove(run);
    }

    /** Ends a player's running operations, delivering what they collected. */
    static void abort(String plugin, UUID player) {
        Set<AreaBreak> runs = RUNNING.get(plugin);
        if (runs == null) return;
        for (AreaBreak run : runs.toArray(AreaBreak[]::new)) {
            if (run.breakerId().equals(player)) run.abort();
        }
    }

    /** Ends every operation of a plugin. */
    static void release(String plugin) {
        LISTENING.remove(plugin);
        Set<AreaBreak> runs = RUNNING.remove(plugin);
        if (runs == null) return;
        for (AreaBreak run : runs) {
            try {
                run.abort();
            } catch (RuntimeException | LinkageError offThread) {
                // Folia: the origin belongs to another region; nothing more can be done at disable.
            }
        }
    }

    static void releaseAll() {
        RUNNING.keySet().forEach(AreaBreaks::release);
        LISTENING.clear();
    }

    /** Ends a quitting player's operations, under the owning plugin. */
    private record QuitListener(String plugin) implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            abort(plugin, event.getPlayer().getUniqueId());
        }
    }
}
