package net.exylia.lib.kitroom;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
        Objects.requireNonNull(plugin, "plugin");
        return ROOMS.compute(plugin.getName(), (name, room) ->
                room != null && room.plugin() == plugin ? room : new KitRoom(plugin));
    }

    /** Forgets a plugin's room, when it disables. */
    public static void release(@NotNull String pluginName) {
        ROOMS.remove(pluginName);
    }
}
