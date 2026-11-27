package net.exylia.lib.display;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Displays sent to one viewer that travel along a path, with a cap on how many each viewer has moving
 * at once: items riding a conveyor, coins flowing to a collector. The client moves them (one pose per
 * corner, interpolated), so a display costs a spawn, one packet per corner and a destroy, whatever its
 * speed. The flow itself is the plugin's numbers; what is drawn is a sample of it (see {@link #stride}).
 * <pre>{@code
 * MovingDisplays belt = MovingDisplays.of(this, 40);           // once, on enable
 * DisplayMotion ride = MovingDisplays.along(corners, 2.0);     // once per path: 2 blocks a second
 * int every = MovingDisplays.stride(perSecond, ride.lifeMillis(), belt.cap());
 * if (made++ % every == 0) belt.send(owner, DisplayModel.item(ore), ride, start);
 * }</pre>
 *
 * @since 1.676.0
 */
public final class MovingDisplays implements Listener {

    private record Live(DisplayHandle handle, long endsAt) {
    }

    private final PluginDisplays displays;
    private final int cap;
    private final Map<UUID, Deque<Live>> live = new ConcurrentHashMap<>();

    private MovingDisplays(Plugin plugin, int cap) {
        this.displays = Displays.of(plugin);
        this.cap = Math.max(1, cap);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Moving displays with at most {@code cap} on the way per viewer. Create once (it listens for quits). */
    public static @NotNull MovingDisplays of(@NotNull Plugin plugin, int cap) {
        return new MovingDisplays(plugin, cap);
    }

    /** How many displays one viewer may have moving at once. */
    public int cap() {
        return cap;
    }

    /**
     * Sends one display to one viewer, moving from {@code origin} as {@code motion} says (offsets from
     * the origin, as {@link #along} builds them), and removed when the motion ends. Any thread.
     *
     * @return its handle, or {@code null} when the viewer already has {@link #cap} on the way or the
     *         server cannot show displays
     */
    public @Nullable DisplayHandle send(@NotNull Player viewer, @NotNull DisplayModel model,
                                        @NotNull DisplayMotion motion, @NotNull Location origin) {
        Deque<Live> mine = live.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        mine.removeIf(l -> l.endsAt() <= now || !l.handle().isShowing());
        if (mine.size() >= cap) return null;
        DisplayHandle handle = displays.show(model, motion, origin, List.of(viewer));
        if (handle != null) mine.add(new Live(handle, now + motion.lifeMillis()));
        return handle;
    }

    /** How many displays this viewer has on the way now. */
    public int live(@NotNull Player viewer) {
        Deque<Live> mine = live.get(viewer.getUniqueId());
        if (mine == null) return 0;
        long now = System.currentTimeMillis();
        mine.removeIf(l -> l.endsAt() <= now || !l.handle().isShowing());
        return mine.size();
    }

    /** Removes every display this viewer has on the way. Any thread. */
    public void clear(@NotNull Player viewer) {
        Deque<Live> mine = live.remove(viewer.getUniqueId());
        if (mine != null) mine.forEach(l -> l.handle().remove());
    }

    /**
     * A ride through every point at a steady speed, straight between them: one linear segment per leg,
     * as offsets from the first point's origin. Two poses a leg, so a path with four corners is about
     * five packets a viewer however long it lasts.
     *
     * @param points          the path, at least two points, relative to where the display is sent
     * @param blocksPerSecond how fast it moves
     * @return the motion, lasting the path's length over the speed (at least one tick a leg)
     */
    public static @NotNull DisplayMotion along(@NotNull List<Vector> points, double blocksPerSecond) {
        if (points.size() < 2) throw new IllegalArgumentException("A path needs at least two points.");
        if (!(blocksPerSecond > 0)) throw new IllegalArgumentException("The speed must be positive.");
        List<DisplayMotion> legs = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            Vector a = points.get(i - 1), b = points.get(i);
            long millis = Math.max(50L, Math.round(a.distance(b) / blocksPerSecond * 1000));
            legs.add(DisplayMotion.builder().life(millis)
                    .from(a.getX(), a.getY(), a.getZ()).to(b.getX(), b.getY(), b.getZ()).build());
        }
        return DisplayMotion.chain(legs.toArray(DisplayMotion[]::new));
    }

    /**
     * Which share of a flow to draw so a viewer stays under a cap: draw one in every {@code stride}.
     * {@code perSecond} things set off each taking {@code travelMillis} keep {@code perSecond * travel}
     * on the way; one in {@code ceil(that / cap)} keeps it at or under the cap.
     *
     * @return 1 or more
     */
    public static int stride(double perSecond, long travelMillis, int cap) {
        double onTheWay = Math.max(0, perSecond) * Math.max(0, travelMillis) / 1000.0;
        return (int) Math.max(1, Math.ceil(onTheWay / Math.max(1, cap)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        live.remove(event.getPlayer().getUniqueId());
    }
}
