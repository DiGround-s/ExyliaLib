package net.exylia.lib.database.internal;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The plugins whose rows are still in the H2 file they used before a move to
 * a shared database, and what to do when the shared one already holds rows.
 *
 * <p>Kept in {@code plugins/ExyliaLib/moves.yml}. The setup writes an entry
 * when it points a plugin at a database server; {@link Carry} reads it the
 * first time each of the plugin's tables is prepared there, copies that
 * table's rows across before the plugin can read it, and writes the table
 * down as done. A table the plugin does not open on this start (a module
 * turned off) is copied whenever it first does. The old file is only read,
 * never written, so it stays as the copy of what the server had.
 *
 * @since 1.581.0
 */
public final class Moves {

    /** What happens to a table the shared database already holds rows in. */
    public enum Policy {
        /** Rows whose key the network does not have are added; where both have one, the network's stays. */
        MERGE,
        /** The network's rows stay as they are; this server's stay in the old file. */
        NETWORK;

        static Policy parse(@Nullable String text) {
            return "network".equalsIgnoreCase(text) ? NETWORK : MERGE;
        }
    }

    /**
     * One plugin's pending move.
     *
     * @param source the old H2 file, without {@code .mv.db}
     * @param policy what to do with a table the network already filled
     * @param done   tables already copied, or found to have nothing to copy
     */
    public record Move(@NotNull Path source, @NotNull Policy policy, @NotNull Set<String> done) {
    }

    private static final String FILE = "moves.yml";
    private static final Logger LOG = Logger.getLogger("ExyliaLib");

    private static YamlConfiguration state;
    private static File file;

    private Moves() {
        throw new AssertionError("No instances.");
    }

    /** The pending move of a plugin, or {@code null} when it has none. */
    public static synchronized @Nullable Move of(@NotNull String plugin) {
        ConfigurationSection section = load().getConfigurationSection(plugin);
        if (section == null) return null;
        String source = section.getString("source", "");
        if (source == null || source.isBlank()) return null;
        return new Move(Path.of(source), Policy.parse(section.getString("policy")),
                Set.copyOf(lowered(section.getStringList("done"))));
    }

    /**
     * Records a plugin as moving: its rows are copied from this file on the next start.
     *
     * @param plugin the plugin
     * @param source its H2 file, without {@code .mv.db}
     * @param policy what to do with a table the network already holds rows in
     */
    public static synchronized void add(@NotNull String plugin, @NotNull Path source, @NotNull Policy policy) {
        YamlConfiguration yaml = load();
        yaml.set(plugin, null);
        yaml.set(plugin + ".source", source.toAbsolutePath().toString());
        yaml.set(plugin + ".policy", policy.name().toLowerCase(Locale.ROOT));
        yaml.set(plugin + ".done", List.of());
        save();
    }

    /** Writes a table down as copied. */
    static synchronized void done(@NotNull String plugin, @NotNull String table) {
        YamlConfiguration yaml = load();
        if (!yaml.isConfigurationSection(plugin)) return;
        Set<String> done = new LinkedHashSet<>(lowered(yaml.getStringList(plugin + ".done")));
        if (!done.add(table.toLowerCase(Locale.ROOT))) return;
        yaml.set(plugin + ".done", new ArrayList<>(done));
        save();
    }

    /** Forgets a plugin's move: it is back on that file, or the move was undone. */
    public static synchronized void remove(@NotNull String plugin) {
        YamlConfiguration yaml = load();
        if (!yaml.contains(plugin)) return;
        yaml.set(plugin, null);
        save();
    }

    /** Test seam: forgets what was read, so the next call reads the file again. */
    static synchronized void reset() {
        state = null;
        file = null;
    }

    private static YamlConfiguration load() {
        if (state != null) return state;
        Plugin library = DatabaseRuntime.library();
        file = library == null ? null : new File(library.getDataFolder(), FILE);
        state = file != null && file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        return state;
    }

    private static void save() {
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            state.save(file);
        } catch (IOException failed) {
            LOG.warning("[ExyliaLib] Could not write " + file + ": " + failed.getMessage()
                    + ". A table copied on this start may be looked at again on the next.");
        }
    }

    private static List<String> lowered(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }
}
