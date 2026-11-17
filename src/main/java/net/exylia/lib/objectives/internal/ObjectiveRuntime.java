package net.exylia.lib.objectives.internal;

import net.exylia.lib.objectives.ObjectiveProgress;
import net.exylia.lib.objectives.ObjectiveType;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Who listens to which objective type, and the one listener set that feeds them. */
@ApiStatus.Internal
public final class ObjectiveRuntime {

    /** One {@code listen} call. */
    public record Sub(Plugin plugin, ObjectiveType type, Predicate<Player> wants, Consumer<ObjectiveProgress> handler) {
    }

    private static final Map<ObjectiveType, List<Sub>> SUBS = new EnumMap<>(ObjectiveType.class);
    private static volatile @Nullable ObjectiveListener listener;
    /** The plugin the listener and its hops run under: the library, or the first subscriber where there is no library (tests). */
    private static volatile @Nullable Plugin host;

    static {
        for (ObjectiveType type : ObjectiveType.values()) SUBS.put(type, new CopyOnWriteArrayList<>());
    }

    private ObjectiveRuntime() {
    }

    public static Sub add(Plugin plugin, ObjectiveType type, Predicate<Player> wants, Consumer<ObjectiveProgress> handler) {
        Sub sub = new Sub(plugin, type, wants, handler);
        SUBS.get(type).add(sub);
        start(plugin);
        return sub;
    }

    public static void remove(Sub sub) {
        SUBS.get(sub.type()).remove(sub);
    }

    public static void release(String pluginName) {
        for (List<Sub> subs : SUBS.values()) subs.removeIf(sub -> sub.plugin().getName().equals(pluginName));
        Plugin current = host;
        if (current != null && current.getName().equals(pluginName)) host = null;
    }

    /** Whether anybody listens to this type at all: the first, cheapest check of every handler. */
    public static boolean any(ObjectiveType type) {
        return !SUBS.get(type).isEmpty();
    }

    /** Whether some subscriber wants this player for this type. */
    public static boolean wanted(ObjectiveType type, Player player) {
        for (Sub sub : SUBS.get(type)) {
            if (wants(sub, player)) return true;
        }
        return false;
    }

    /** Hands progress to each subscriber that wants its player. Call on the player's thread. */
    public static void deliver(ObjectiveProgress progress) {
        for (Sub sub : SUBS.get(progress.type())) {
            if (!wants(sub, progress.player())) continue;
            try {
                sub.handler().accept(progress);
            } catch (RuntimeException | LinkageError failure) {
                sub.plugin().getLogger().log(Level.WARNING,
                        "Objective handler failed on " + progress.type() + " for " + progress.player().getName(), failure);
            }
        }
    }

    /** Builds progress and delivers it on the player's thread: now when already there, else on the next tick. */
    public static void deliverOn(Player player, Supplier<ObjectiveProgress> progress) {
        Plugin plugin = host;
        if (plugin == null) return;
        if (Tasks.of(plugin).isOwnedBy(player)) {
            deliver(progress.get());
        } else {
            later(player, progress);
        }
    }

    /** Builds and delivers progress on the player's thread on a later tick, even when called there. */
    public static void later(Player player, Supplier<ObjectiveProgress> progress) {
        Plugin plugin = host;
        if (plugin == null) return;
        Tasks.of(plugin).runAtEntity(player, () -> {
            if (player.isOnline()) deliver(progress.get());
        });
    }

    public static void fire(Player player, String customType, String thing, int amount) {
        if (!any(ObjectiveType.CUSTOM)) return;
        deliverOn(player, () -> new ObjectiveProgress(player, ObjectiveType.CUSTOM, thing, player.getLocation(),
                amount, false, null, null, customType));
    }

    private static boolean wants(Sub sub, Player player) {
        try {
            return sub.wants().test(player);
        } catch (RuntimeException failure) {
            sub.plugin().getLogger().log(Level.WARNING, "Objective filter failed on " + sub.type(), failure);
            return false;
        }
    }

    /** Registers the listeners once, under the library's own plugin. */
    private static synchronized void start(Plugin subscriber) {
        if (host == null) host = library(subscriber);
        if (listener != null) return;
        ObjectiveListener created = new ObjectiveListener();
        listener = created;
        try {
            Bukkit.getPluginManager().registerEvents(created, host);
        } catch (RuntimeException | LinkageError noServer) {
            // A test without a plugin manager: handlers are called directly.
        }
    }

    private static Plugin library(Plugin fallback) {
        try {
            return JavaPlugin.getProvidingPlugin(ObjectiveRuntime.class);
        } catch (RuntimeException | LinkageError noLoader) {
            return fallback;
        }
    }

    /** The registered listener, for tests that call its handlers. */
    @ApiStatus.Internal
    public static @Nullable ObjectiveListener listenerForTests() {
        return listener;
    }

    /** Forgets everything, between tests. */
    @ApiStatus.Internal
    public static synchronized void resetForTests() {
        SUBS.values().forEach(List::clear);
        listener = null;
        host = null;
    }

    static @NotNull String spot(org.bukkit.block.Block block) {
        return block.getWorld().getName() + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }
}
