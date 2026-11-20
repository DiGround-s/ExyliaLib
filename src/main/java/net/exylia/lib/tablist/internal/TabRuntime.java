package net.exylia.lib.tablist.internal;

import net.exylia.lib.platform.Features;
import net.exylia.lib.tablist.PluginTabList;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Drives every plugin's tab list from one timer; each update runs on the player's thread. */
public final class TabRuntime {

    // In registration order: the last handle with a field wins it.
    private static final List<PluginTabList> HANDLES = new CopyOnWriteArrayList<>();
    private static final Map<UUID, TabDiff> STATE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();
    private static volatile Plugin lib;
    private static volatile TaskHandle timer;
    private static volatile long period;

    private TabRuntime() {
    }

    public static void init(Plugin plugin) {
        lib = plugin;
    }

    public static PluginTabList of(Plugin plugin) {
        for (PluginTabList h : HANDLES) {
            if (h.pluginName().equals(plugin.getName())) {
                return h;
            }
        }
        PluginTabList created = new PluginTabList(plugin.getName());
        HANDLES.add(created);
        return created;
    }

    public static void release(String pluginName) {
        HANDLES.removeIf(h -> h.pluginName().equals(pluginName));
        reschedule();
        Bukkit.getOnlinePlayers().forEach(TabRuntime::refresh);
    }

    /** Cancels the timer and forgets everything. */
    public static void shutdown() {
        TaskHandle t = timer;
        if (t != null) {
            t.cancel();
        }
        timer = null;
        period = 0;
        HANDLES.clear();
        STATE.clear();
        REPORTED.clear();
    }

    /** Called when a player leaves. */
    public static void forget(Player player) {
        STATE.remove(player.getUniqueId());
        HANDLES.forEach(h -> h.forget(player.getUniqueId()));
    }

    /** Called when a player joins: first render a moment later, not on the login tick. */
    public static void join(Player player) {
        Plugin plugin = lib;
        if (plugin != null && !HANDLES.isEmpty()) {
            Tasks.of(plugin).runAtEntityLater(player, 2L, () -> render(player));
        }
    }

    /** Renders a player now, on their thread. */
    public static void refresh(Player player) {
        Plugin plugin = lib;
        if (plugin != null) {
            Tasks.of(plugin).runAtEntity(player, () -> render(player));
        }
    }

    /** Recomputes the timer period from the fastest interval and restarts it if it moved. */
    public static synchronized void reschedule() {
        Plugin plugin = lib;
        if (plugin == null) {
            return;
        }
        long wanted = HANDLES.stream().mapToLong(h -> Math.max(1L, h.interval().toMillis() / 50L)).min().orElse(0L);
        if (wanted == period) {
            return;
        }
        TaskHandle old = timer;
        if (old != null) {
            old.cancel();
        }
        timer = null;
        period = wanted;
        if (wanted > 0) {
            timer = Tasks.of(plugin).runTimer(wanted, wanted,
                    () -> Bukkit.getOnlinePlayers().forEach(TabRuntime::refresh));
        }
    }

    private static void render(Player player) {
        if (!player.isOnline()) {
            return;
        }
        TabDiff diff = STATE.computeIfAbsent(player.getUniqueId(), id -> new TabDiff());
        String header = text(player, diff, TabDiff.HEADER, h -> h.header());
        String footer = text(player, diff, TabDiff.FOOTER, h -> h.footer());
        String name = text(player, diff, TabDiff.NAME, h -> h.name());
        if (diff.changed(TabDiff.HEADER, header) | diff.changed(TabDiff.FOOTER, footer)) {
            player.sendPlayerListHeaderAndFooter(component(header), component(footer));
        }
        if (diff.changed(TabDiff.NAME, name)) {
            player.playerListName(name == null ? null : component(name));
        }
        if (Features.playerListOrder()) {
            Integer order = order(player);
            if (diff.changed(TabDiff.ORDER, order)) {
                player.setPlayerListOrder(order == null ? 0 : order);
            }
        }
    }

    private static Component component(String text) {
        return text == null ? Component.empty() : Text.component(text.replace("<nl>", "\n"));
    }

    private static String text(Player player, TabDiff diff, int slot, Function<PluginTabList, Function<Player, String>> field) {
        for (int i = HANDLES.size() - 1; i >= 0; i--) {
            PluginTabList h = HANDLES.get(i);
            Function<Player, String> fn = field.apply(h);
            if (fn == null || h.removed(player.getUniqueId())) {
                continue;
            }
            try {
                return fn.apply(player);
            } catch (RuntimeException e) {
                report(h, e);
                return (String) diff.last(slot);
            }
        }
        return null;
    }

    private static Integer order(Player player) {
        for (int i = HANDLES.size() - 1; i >= 0; i--) {
            PluginTabList h = HANDLES.get(i);
            if (h.order() == null || h.removed(player.getUniqueId())) {
                continue;
            }
            try {
                return h.order().applyAsInt(player);
            } catch (RuntimeException e) {
                report(h, e);
            }
        }
        return null;
    }

    private static void report(PluginTabList h, RuntimeException e) {
        if (REPORTED.putIfAbsent(h.pluginName(), true) == null) {
            Bukkit.getLogger().warning("[ExyliaLib] Tab list of " + h.pluginName() + " failed: " + e);
        }
    }
}
