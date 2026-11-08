package net.exylia.lib.kitroom;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Entry point of the kit room module: one {@link KitRoom} per plugin.
 *
 * @since 1.511.0
 */
public final class KitRooms {

    private static final Map<String, KitRoom> ROOMS = new ConcurrentHashMap<>();

    private KitRooms() {
    }

    /** The plugin's kit room, loaded from its database on first use. */
    public static @NotNull KitRoom of(@NotNull Plugin plugin) {
        return of(plugin, () -> CompletableFuture.completedFuture(List.of()));
    }

    /**
     * The plugin's kit room, filled from {@code seed} the first time its table
     * is found empty: how a plugin moves a kit room it kept itself into this
     * one. The seed is asked off the main thread, once, and only on the call
     * that creates the room; an empty answer falls back to the defaults.
     *
     * @since 1.512.0
     */
    public static @NotNull KitRoom of(@NotNull Plugin plugin,
                                      @NotNull Supplier<? extends CompletionStage<List<KitRoomCategory>>> seed) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(seed, "seed");
        return ROOMS.compute(plugin.getName(), (name, room) ->
                room != null && room.plugin() == plugin ? room : new KitRoom(plugin, seed));
    }

    /** Forgets a plugin's room, when it disables. */
    public static void release(@NotNull String pluginName) {
        ROOMS.remove(pluginName);
    }
}
