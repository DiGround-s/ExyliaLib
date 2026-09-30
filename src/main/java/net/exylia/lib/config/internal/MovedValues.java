package net.exylia.lib.config.internal;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Values a migration took out of one file on their way into another.
 *
 * <p>Held here between the two loads, so it does not matter which file loads
 * first: a target not read yet picks them up when it is, and a target already
 * read is reloaded once the source finishes.
 */
public final class MovedValues {

    private static final Map<String, Map<String, Object>> PENDING = new ConcurrentHashMap<>();

    private MovedValues() {
    }

    /**
     * Keeps the leaves under a path for a target file.
     *
     * @param plugin the owning plugin's name
     * @param target the target file name, as it was declared
     * @param path   where the value goes in the target
     * @param value  the value, or a section whose every leaf is kept
     */
    public static void hold(@NotNull String plugin, @NotNull String target, @NotNull String path, Object value) {
        if (value == null) {
            return;
        }
        Map<String, Object> into = PENDING.computeIfAbsent(key(plugin, target), ignored -> new LinkedHashMap<>());
        if (value instanceof ConfigurationSection section) {
            section.getValues(true).forEach((child, leaf) -> {
                if (!(leaf instanceof ConfigurationSection)) {
                    into.put(path + "." + child, leaf);
                }
            });
        } else {
            into.put(path, value);
        }
    }

    /** Whether values are waiting for a target. */
    public static boolean waiting(@NotNull String plugin, @NotNull String target) {
        return PENDING.containsKey(key(plugin, target));
    }

    /**
     * Writes what is waiting for a target into its contents.
     *
     * <p>A value equal to the target's English default is left out: it is what
     * the file already says in English, and writing it would put English over
     * a translation.
     *
     * @param defaults the target's English defaults, rendered
     * @return whether anything was written
     */
    public static boolean deliver(@NotNull String plugin, @NotNull String target, @NotNull YamlConfiguration into,
                                  @NotNull YamlConfiguration defaults) {
        Map<String, Object> values = PENDING.remove(key(plugin, target));
        if (values == null) {
            return false;
        }
        boolean written = false;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (Objects.equals(String.valueOf(entry.getValue()), String.valueOf(defaults.get(entry.getKey())))) {
                continue;
            }
            into.set(entry.getKey(), entry.getValue());
            written = true;
        }
        return written;
    }

    /** Forgets what a plugin had waiting. */
    public static void release(@NotNull String plugin) {
        PENDING.keySet().removeIf(key -> key.startsWith(plugin + ":"));
    }

    private static String key(String plugin, String target) {
        return plugin + ":" + target;
    }
}
