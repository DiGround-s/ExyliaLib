package net.exylia.lib.tablist.internal;

import net.exylia.lib.platform.Features;
import net.exylia.lib.tablist.PluginTabList;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.internal.TextEffects;
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
    /** Ticks between full resolves; the timer itself runs at the frame rate. */
    private static volatile long resolveTicks;
    private static long tick;

    /**
     * Ticks between two frames of a moving line ({@code <flow>}, {@code <shine>},
     * {@code <frames>}...). Only players whose tab list moves pay for it.
     */
    // ponytail: fixed 10 fps; make it a PluginTabList setting if a plugin needs smoother or cheaper motion
    static final long FRAME_TICKS = 2L;

    /** A parsed text slot and whether it moves. */
    record Built(Component component, boolean moving) {
    }

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
        resolveTicks = 0;
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
            Tasks.of(plugin).runAtEntityLater(player, 2L, () -> render(player, true));
        }
    }

    /** Renders a player now, on their thread. */
    public static void refresh(Player player) {
        Plugin plugin = lib;
        if (plugin != null) {
            Tasks.of(plugin).runAtEntity(player, () -> render(player, true));
        }
    }

    /** Recomputes the timer period from the fastest interval and restarts it if it moved. */
    public static synchronized void reschedule() {
        Plugin plugin = lib;
        if (plugin == null) {
            return;
        }
        long resolve = HANDLES.stream().mapToLong(h -> Math.max(1L, h.interval().toMillis() / 50L)).min().orElse(0L);
        long wanted = resolve == 0 ? 0 : Math.min(resolve, FRAME_TICKS);
        resolveTicks = resolve;
        if (wanted == period) {
            return;
        }
        TaskHandle old = timer;
        if (old != null) {
            old.cancel();
        }
        timer = null;
        period = wanted;
        tick = 0;
        if (wanted > 0) {
            timer = Tasks.of(plugin).runTimer(wanted, wanted, TabRuntime::tick);
        }
    }

    /** One timer step: a full resolve when due, otherwise a new frame for players whose tab list moves. */
    private static void tick() {
        long now = tick += period;
        boolean resolve = resolveTicks > 0 && now % resolveTicks < period;
        Plugin plugin = lib;
        if (plugin == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            TabDiff diff = STATE.get(player.getUniqueId());
            if (resolve || diff != null && diff.moving()) {
                Tasks.of(plugin).runAtEntity(player, () -> render(player, resolve));
            }
        }
    }

    private static void render(Player player, boolean resolve) {
        if (!player.isOnline()) {
            return;
        }
        TabDiff diff = STATE.computeIfAbsent(player.getUniqueId(), id -> new TabDiff());
        // Between resolves only the frame moves: the text is the one resolved last.
        String header = resolve ? text(player, diff, TabDiff.HEADER, h -> h.header()) : (String) diff.last(TabDiff.HEADER);
        String footer = resolve ? text(player, diff, TabDiff.FOOTER, h -> h.footer()) : (String) diff.last(TabDiff.FOOTER);
        String name = resolve ? text(player, diff, TabDiff.NAME, h -> h.name()) : (String) diff.last(TabDiff.NAME);
        Component h = diff.build(TabDiff.HEADER, header, TabRuntime::parse);
        Component f = diff.build(TabDiff.FOOTER, footer, TabRuntime::parse);
        // A player-list name goes to every player on the server, so it is not
        // animated frame by frame: 400 moving names would be 400 x 400 packets
        // a frame. It changes when it is resolved again.
        Component n = resolve ? diff.build(TabDiff.NAME, name, TabRuntime::parse) : null;
        if (diff.show(TabDiff.HEADER, h) | diff.show(TabDiff.FOOTER, f)) {
            player.sendPlayerListHeaderAndFooter(h == null ? Component.empty() : h, f == null ? Component.empty() : f);
        }
        if (resolve && diff.show(TabDiff.NAME, n)) {
            player.playerListName(n);
        }
        if (resolve && Features.playerListOrder()) {
            Integer order = order(player);
            if (diff.changed(TabDiff.ORDER, order)) {
                player.setPlayerListOrder(order == null ? 0 : order);
            }
        }
    }

    /**
     * Parses a header, footer or name. A text whose lines start with moving tags
     * ({@code <flow>}, {@code <frames>}...) is parsed line by line, so each line
     * moves on its own; any other text is parsed whole, as before.
     */
    /** This frame's parses, shared: a moving header is the same frame for everybody who has that text. */
    private static final Map<String, Built> FRAME = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long frameOf = -1;

    static Built parse(String text) {
        // One server tick: every player drawn within it is drawn the same frame.
        long now = TextEffects.now() / 50L;
        if (now != frameOf) {
            frameOf = now;
            FRAME.clear();
        }
        return FRAME.computeIfAbsent(text, TabRuntime::parseNow);
    }

    private static Built parseNow(String text) {
        String[] lines = text.replace("<nl>", "\n").split("\n", -1);
        boolean moving = false;
        for (String line : lines) {
            moving |= TextEffects.moves(line);
        }
        if (!moving) {
            return new Built(Text.component(String.join("\n", lines)), false);
        }
        Component out = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(Text.component(lines[i]));
        }
        return new Built(out, true);
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
