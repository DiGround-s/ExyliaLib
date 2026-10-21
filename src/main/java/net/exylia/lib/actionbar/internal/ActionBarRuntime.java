package net.exylia.lib.actionbar.internal;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The action bar module's working parts: the registered segments and one timer
 * per online player, which exist only while at least one segment does.
 */
public final class ActionBarRuntime {

    static final long PERIOD_TICKS = 2L;
    private static final long JOIN_DELAY_TICKS = 20L;
    private static final String SEPARATOR = "&r {muted}| &r";

    private static final List<Segment> SEGMENTS = new CopyOnWriteArrayList<>();
    private static final Map<UUID, Viewer> VIEWERS = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final Object LOCK = new Object();

    private static volatile TaskScheduler tasks;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private ActionBarRuntime() {
    }

    public static void init(Plugin plugin) {
        tasks = Tasks.of(plugin);
        logger = plugin.getLogger();
    }

    public static void register(String plugin, String id, int order, Function<Player, Component> segment) {
        synchronized (LOCK) {
            boolean wasEmpty = SEGMENTS.isEmpty();
            List<Segment> next = new ArrayList<>(SEGMENTS);
            next.removeIf(existing -> existing.plugin.equals(plugin) && existing.id.equals(id));
            next.add(new Segment(plugin, id, order, segment));
            next.sort(Comparator.comparingInt(Segment::order));
            SEGMENTS.clear();
            SEGMENTS.addAll(next);
            FAILED.remove(plugin + ':' + id);
            if (wasEmpty) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    begin(player, 0L);
                }
            }
        }
    }

    public static void unregister(String plugin, String id) {
        synchronized (LOCK) {
            if (SEGMENTS.removeIf(segment -> segment.plugin.equals(plugin) && segment.id.equals(id))) {
                stopIfEmpty();
            }
        }
    }

    /** Drops every segment a disabled plugin registered. */
    public static void release(String plugin) {
        synchronized (LOCK) {
            if (SEGMENTS.removeIf(segment -> segment.plugin.equals(plugin))) {
                stopIfEmpty();
            }
        }
    }

    public static void joined(Player player) {
        if (!SEGMENTS.isEmpty()) {
            begin(player, JOIN_DELAY_TICKS);
        }
    }

    public static void forget(UUID player) {
        Viewer viewer = VIEWERS.remove(player);
        if (viewer != null && viewer.task != null) {
            viewer.task.cancel();
        }
    }

    public static void refresh(Player player) {
        Viewer viewer = VIEWERS.get(player.getUniqueId());
        TaskScheduler scheduler = tasks;
        if (viewer == null || scheduler == null || !player.isOnline()) {
            return;
        }
        if (scheduler.isOwnedBy(player)) {
            update(player, viewer);
        } else {
            scheduler.runAtEntity(player, () -> update(player, viewer));
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            SEGMENTS.clear();
            for (Viewer viewer : VIEWERS.values()) {
                if (viewer.task != null) {
                    viewer.task.cancel();
                }
            }
            VIEWERS.clear();
            FAILED.clear();
        }
    }

    private static void begin(Player player, long delay) {
        TaskScheduler scheduler = tasks;
        if (scheduler == null) {
            return;
        }
        Viewer viewer = new Viewer();
        Viewer running = VIEWERS.putIfAbsent(player.getUniqueId(), viewer);
        if (running != null) {
            if (running.task == null || !running.task.isCancelled() || !VIEWERS.replace(player.getUniqueId(), running, viewer)) {
                return;
            }
        }
        viewer.task = scheduler.runAtEntityTimer(player, delay, PERIOD_TICKS, () -> update(player, viewer));
    }

    private static void stopIfEmpty() {
        if (!SEGMENTS.isEmpty()) {
            return;
        }
        TaskScheduler scheduler = tasks;
        for (Map.Entry<UUID, Viewer> entry : VIEWERS.entrySet()) {
            Viewer viewer = entry.getValue();
            if (viewer.task != null) {
                viewer.task.cancel();
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && scheduler != null && !viewer.line.cleared()) {
                scheduler.runAtEntity(player, () -> player.sendActionBar(Component.empty()));
            }
        }
        VIEWERS.clear();
    }

    private static void update(Player player, Viewer viewer) {
        if (!player.isOnline()) {
            return;
        }
        List<Component> parts = null;
        for (Segment segment : SEGMENTS) {
            Component part = ask(segment, player);
            if (part != null && !Component.empty().equals(part)) {
                if (parts == null) {
                    parts = new ArrayList<>(2);
                }
                parts.add(part);
            }
        }
        Component line = parts == null ? null
                : ActionBarLine.compose(parts, parts.size() > 1 ? Text.component(SEPARATOR) : Component.empty());
        Component send = viewer.line.next(line, System.currentTimeMillis());
        if (send != null) {
            player.sendActionBar(send);
        }
    }

    private static Component ask(Segment segment, Player player) {
        try {
            return segment.text.apply(player);
        } catch (RuntimeException | LinkageError failure) {
            if (FAILED.add(segment.plugin + ':' + segment.id)) {
                logger.log(Level.WARNING, "Action bar segment '" + segment.id + "' of " + segment.plugin
                        + " failed; this is logged once and the segment is asked again every refresh.", failure);
            }
            return null;
        }
    }

    private record Segment(String plugin, String id, int order, Function<Player, Component> text) {
    }

    private static final class Viewer {
        private final ActionBarLine line = new ActionBarLine();
        private volatile TaskHandle task;
    }
}
