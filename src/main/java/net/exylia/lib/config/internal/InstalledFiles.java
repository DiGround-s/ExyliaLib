package net.exylia.lib.config.internal;

import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each plugin installs from its jar, as paths relative to its data folder: the files and
 * folders it keeps up to date for the owner to edit, and the folders it rewrites on every start,
 * which are the plugin's own. The web editor offers the first and never the second.
 */
public final class InstalledFiles {

    private static final Map<String, Set<String>> EDITABLE = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> OWNED = new ConcurrentHashMap<>();

    private InstalledFiles() {
    }

    /** A file or folder the plugin installs and updates, for the owner to edit. */
    public static void editable(Plugin plugin, Path relative) {
        EDITABLE.computeIfAbsent(plugin.getName(), ignored -> ConcurrentHashMap.newKeySet()).add(slashed(relative));
    }

    /** A folder the plugin rewrites from its jar on every start: edits to it would be lost. */
    public static void owned(Plugin plugin, Path relative) {
        OWNED.computeIfAbsent(plugin.getName(), ignored -> ConcurrentHashMap.newKeySet()).add(slashed(relative));
    }

    public static Set<String> editableOf(String plugin) {
        return Set.copyOf(EDITABLE.getOrDefault(plugin, Set.of()));
    }

    public static Set<String> ownedOf(String plugin) {
        return Set.copyOf(OWNED.getOrDefault(plugin, Set.of()));
    }

    private static String slashed(Path relative) {
        return relative.normalize().toString().replace('\\', '/');
    }
}
