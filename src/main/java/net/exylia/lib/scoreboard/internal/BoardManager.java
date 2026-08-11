package net.exylia.lib.scoreboard.internal;

import net.exylia.lib.scoreboard.Board;
import net.exylia.lib.scoreboard.SidebarConfig;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Owns every scoreboard on the server and drives their refreshes.
 *
 * <p>Boards stack per player, across plugins: the board shown last pauses the
 * one underneath, and taking it down brings the previous one back. That is what
 * lets a minigame cover the lobby board and hand it back afterwards without
 * either plugin knowing about the other.
 *
 * <p>One async timer ticks all visible boards. Each board renders only when
 * its own interval is due, on a slot staggered by the player's id, so a reload
 * that recreates two hundred boards in one tick spreads their renders across
 * the interval instead of concentrating them.
 *
 * <p>The driver belongs to ExyliaLib itself: boards come and go with the
 * plugins that showed them, but the timer has to outlive any single one.
 */
public final class BoardManager {

    /** Ticks to wait before reclaiming a board after a world change or respawn. */
    private static final long REINIT_DELAY_TICKS = 20L;

    private static final Object LOCK = new Object();
    private static final Map<UUID, Deque<BoardImpl>> STACKS = new ConcurrentHashMap<>();

    private static TaskScheduler scheduler;
    private static SidebarFactory sidebars;
    private static Logger logger = Logger.getLogger("ExyliaLib");
    private static TaskHandle driver;
    /** The refresh clock. Swapped by tests; the wall clock everywhere else. */
    private static LongSupplier clock = System::currentTimeMillis;

    private BoardManager() {
    }

    /** Returns the current time on the refresh clock. */
    static long now() {
        return clock.getAsLong();
    }

    /** Overrides the refresh clock. For tests; {@code null} restores it. */
    static void clock(LongSupplier override) {
        clock = override == null ? System::currentTimeMillis : override;
    }

    /**
     * Wires the module to the runtime. Called by ExyliaLib at startup, and by
     * tests with a fake factory.
     *
     * @param plugin  the plugin whose scheduler drives the boards
     * @param factory creates the sidebars
     */
    public static void init(Plugin plugin, SidebarFactory factory) {
        synchronized (LOCK) {
            scheduler = Tasks.of(plugin);
            sidebars = factory;
            logger = plugin.getLogger();
        }
    }

    /** Where failures are reported. */
    static Logger logger() {
        return logger;
    }

    /**
     * Shows a board to a player.
     *
     * @param plugin the plugin the board belongs to
     * @param player the viewer
     * @param config what the board looks like
     * @param data   extra values placeholders can read, may be {@code null}
     * @return the shown board, or a no-op one when the config disables it
     */
    public static Board show(Plugin plugin, Player player, SidebarConfig config,
                             Map<String, Object> data) {
        if (plugin == null || player == null || config == null) {
            throw new IllegalArgumentException("plugin, player and config must not be null");
        }
        if (!config.enabled()) {
            return new NoopBoard(player, config);
        }

        SidebarFactory factory;
        TaskScheduler tasks;
        synchronized (LOCK) {
            factory = sidebars;
            tasks = scheduler;
        }
        if (factory == null || tasks == null) {
            throw new IllegalStateException(
                    "The scoreboard module is not ready; ExyliaLib must be enabled first");
        }

        List<String> lines = config.lines();
        if (lines.size() > SidebarHandle.MAX_LINES) {
            logger().warning(plugin.getName() + ": a scoreboard declares " + lines.size()
                    + " lines; only the first " + SidebarHandle.MAX_LINES + " are shown.");
            config = new SidebarConfig(true, config.title(),
                    lines.subList(0, SidebarHandle.MAX_LINES), config.update());
        }

        BoardImpl board = new BoardImpl(plugin.getName(), player, config,
                factory.create(player, SidebarHandle.MAX_LINES));
        if (data != null && !data.isEmpty()) {
            board.updateData(data);
        }

        synchronized (LOCK) {
            Deque<BoardImpl> stack =
                    STACKS.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
            BoardImpl current = stack.peekFirst();
            if (current != null) {
                current.pause();
            }
            stack.addFirst(board);
            board.sidebar().show();
            ensureDriver();
        }
        return board;
    }

    /**
     * Takes down the board currently visible to a player.
     *
     * @param player the viewer
     * @return {@code true} when there was a board to take down
     */
    public static boolean hide(Player player) {
        if (player == null) {
            return false;
        }
        synchronized (LOCK) {
            Deque<BoardImpl> stack = STACKS.get(player.getUniqueId());
            if (stack == null) {
                return false;
            }
            BoardImpl top = stack.peekFirst();
            if (top == null) {
                return false;
            }
            stopLocked(top);
            return true;
        }
    }

    /**
     * Returns the board currently visible to a player.
     *
     * @param player the viewer
     * @return the visible board, empty when there is none
     */
    public static Optional<Board> get(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            Deque<BoardImpl> stack = STACKS.get(player.getUniqueId());
            return stack == null ? Optional.empty() : Optional.ofNullable(stack.peekFirst());
        }
    }

    /**
     * Returns whether a player has any board, visible or paused.
     *
     * @param player the viewer
     * @return {@code true} when at least one board is on the player's stack
     */
    public static boolean has(Player player) {
        if (player == null) {
            return false;
        }
        synchronized (LOCK) {
            Deque<BoardImpl> stack = STACKS.get(player.getUniqueId());
            return stack != null && !stack.isEmpty();
        }
    }

    /**
     * Stops every board a plugin showed, wherever each sits in its stack.
     *
     * @param pluginName the plugin's name
     * @return how many boards were stopped
     */
    public static int stopAll(String pluginName) {
        int stopped = 0;
        synchronized (LOCK) {
            for (Map.Entry<UUID, Deque<BoardImpl>> entry : List.copyOf(STACKS.entrySet())) {
                Deque<BoardImpl> stack = entry.getValue();
                boolean topAffected = false;
                for (var it = stack.iterator(); it.hasNext(); ) {
                    BoardImpl board = it.next();
                    if (board.ownedBy(pluginName)) {
                        topAffected |= stack.peekFirst() == board;
                        it.remove();
                        board.stopInternal();
                        stopped++;
                    }
                }
                if (stack.isEmpty()) {
                    STACKS.remove(entry.getKey(), stack);
                } else if (topAffected) {
                    resumeTop(stack);
                }
            }
            stopDriverIfIdle();
        }
        return stopped;
    }

    /**
     * Stops every board a player has. Called when the player leaves.
     *
     * @param player the viewer
     * @return how many boards were stopped
     */
    public static int stopFor(Player player) {
        synchronized (LOCK) {
            Deque<BoardImpl> stack = STACKS.remove(player.getUniqueId());
            if (stack == null) {
                return 0;
            }
            int stopped = 0;
            for (BoardImpl board : stack) {
                board.stopInternal();
                stopped++;
            }
            stopDriverIfIdle();
            return stopped;
        }
    }

    /** Stops everything. Used on shutdown and by tests. */
    public static void stopEverything() {
        synchronized (LOCK) {
            for (Deque<BoardImpl> stack : STACKS.values()) {
                for (BoardImpl board : stack) {
                    board.stopInternal();
                }
            }
            STACKS.clear();
            stopDriverLocked();
        }
    }

    /**
     * Makes every visible board re-parse and re-send everything on its next
     * refresh. Used when the shared palette is reloaded: the raw text is the
     * same, but what it parses into is not. Paused boards re-render when they
     * resume, so they are covered too.
     */
    public static void invalidateAll() {
        synchronized (LOCK) {
            for (Deque<BoardImpl> stack : STACKS.values()) {
                BoardImpl top = stack.peekFirst();
                if (top != null) {
                    top.invalidate();
                }
            }
        }
    }

    /**
     * Re-sends a player's board after the client may have lost it.
     *
     * <p>World changes and respawns give other plugins and the server itself a
     * chance to replace the sidebar objective. Waiting a second before
     * reclaiming it lets whatever replaced it finish first.
     *
     * @param player the viewer
     */
    public static void reinit(Player player) {
        TaskScheduler tasks;
        synchronized (LOCK) {
            tasks = scheduler;
        }
        if (tasks == null) {
            return;
        }
        tasks.runLater(REINIT_DELAY_TICKS, () -> {
            synchronized (LOCK) {
                Deque<BoardImpl> stack = STACKS.get(player.getUniqueId());
                BoardImpl top = stack == null ? null : stack.peekFirst();
                if (top == null || top.stopped() || !player.isOnline()) {
                    return;
                }
                top.sidebar().hide();
                top.sidebar().show();
                top.invalidate();
            }
        });
    }

    /** Returns how many boards exist, visible and paused. */
    public static int activeCount() {
        synchronized (LOCK) {
            int count = 0;
            for (Deque<BoardImpl> stack : STACKS.values()) {
                count += stack.size();
            }
            return count;
        }
    }

    /** Stops a board wherever it sits in its player's stack. */
    static void stop(BoardImpl board) {
        synchronized (LOCK) {
            stopLocked(board);
        }
    }

    private static void stopLocked(BoardImpl board) {
        if (board.stopped()) {
            return;
        }
        UUID id = board.player().getUniqueId();
        Deque<BoardImpl> stack = STACKS.get(id);
        if (stack == null) {
            board.stopInternal();
            return;
        }
        boolean wasTop = stack.peekFirst() == board;
        stack.remove(board);
        board.stopInternal();
        if (stack.isEmpty()) {
            STACKS.remove(id, stack);
        } else if (wasTop) {
            resumeTop(stack);
        }
        stopDriverIfIdle();
    }

    private static void resumeTop(Deque<BoardImpl> stack) {
        BoardImpl next = stack.peekFirst();
        if (next != null) {
            next.resume();
        }
    }

    private static void ensureDriver() {
        if (driver == null) {
            driver = scheduler.runAsyncTimer(1L, 1L, BoardManager::tick);
        }
    }

    private static void stopDriverIfIdle() {
        if (STACKS.isEmpty()) {
            stopDriverLocked();
        }
    }

    private static void stopDriverLocked() {
        if (driver != null) {
            driver.cancel();
            driver = null;
        }
    }

    private static void tick() {
        List<BoardImpl> due = new ArrayList<>();
        long now = now();
        synchronized (LOCK) {
            for (Deque<BoardImpl> stack : List.copyOf(STACKS.values())) {
                BoardImpl top = stack.peekFirst();
                if (top == null) {
                    continue;
                }
                // The quit listener removes boards; this only covers the paths
                // where it cannot run, such as a hard shutdown.
                if (!top.player().isOnline()) {
                    stopLocked(top);
                    continue;
                }
                if (top.due(now)) {
                    due.add(top);
                }
            }
        }
        // Rendering is the slow part, so it happens outside the lock: showing
        // or hiding a board never waits on somebody's placeholder.
        for (BoardImpl board : due) {
            board.render();
        }
    }
}
