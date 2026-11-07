package net.exylia.lib.screen.internal;

import net.exylia.lib.screen.Screen;
import net.exylia.lib.task.Tasks;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Screen state kept with the player's data ({@code persist="player"}): one string per key, under
 * {@code exylialib:screen/<scope>/<key>}. Values come back as text; {@code "false"} still reads false.
 */
final class Persisted {

    private Persisted() {
    }

    static Map<String, Object> load(Player player, Screen screen) {
        Map<String, Object> out = new HashMap<>();
        if (screen.persistScope() == null) return out;
        try {
            var data = player.getPersistentDataContainer();
            for (String key : screen.persisted()) {
                String value = data.get(key(screen.persistScope(), key), PersistentDataType.STRING);
                if (value != null) out.put(key, value);
            }
        } catch (RuntimeException | LinkageError noServer) {
            // A unit test without a server: nothing stored.
        }
        return out;
    }

    static void save(Plugin plugin, Player player, Screen screen, String key, Object value) {
        NamespacedKey id = key(screen.persistScope(), key);
        Runnable write = () -> {
            var data = player.getPersistentDataContainer();
            if (value == null) data.remove(id);
            else data.set(id, PersistentDataType.STRING, String.valueOf(value));
        };
        try {
            Tasks.of(plugin).runAtEntity(player, write);
        } catch (RuntimeException | LinkageError noServer) {
            // A unit test without a server.
        }
    }

    static NamespacedKey key(String scope, String key) {
        return new NamespacedKey("exylialib", "screen/" + clean(scope) + "/" + clean(key));
    }

    private static String clean(String part) {
        return part.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
