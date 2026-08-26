package net.exylia.lib.config;

import net.exylia.lib.config.internal.ConfigFileImpl;
import net.exylia.lib.config.internal.SchemaCache;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the config module.
 *
 * <p>Configs are declared as records and used as records. You never write YAML
 * by hand, and you never look up a value by string at runtime.
 *
 * <h2>Declare</h2>
 * <pre>{@code
 * @Comment("Everything about how players are stored.")
 * public record Settings(
 *         @Comment("Connections kept open. Rule of thumb: cores x 2.")
 *         int poolSize,
 *
 *         @Comment("How long a player stays cached after leaving.")
 *         int cacheMinutes,
 *
 *         Messages messages
 * ) {
 *     public Settings() {
 *         this(10, 30, new Messages());
 *     }
 *
 *     public record Messages(String joined, String left) {
 *         public Messages() {
 *             this("{primary}Welcome", "{muted}Bye");
 *         }
 *     }
 * }
 * }</pre>
 *
 * The no-argument constructor holds the defaults, so the file that ships to
 * servers is generated from the same source of truth the code reads.
 *
 * <h2>Load and use</h2>
 * <pre>{@code
 * ConfigFile<Settings> settings = Configs.define(this, "config", Settings.class).load();
 *
 * int pool = settings.get().poolSize();
 * String joined = settings.get().messages().joined();
 * }</pre>
 *
 * <p>On first run the file is created, complete with the comments from the
 * schema. On later runs, keys added to the record appear in the file
 * automatically and everything the user edited is left alone.
 *
 * @since 1.1.0
 */
public final class Configs {

    private static final Map<String, ConfigFileImpl<?>> FILES = new ConcurrentHashMap<>();

    private Configs() {
        throw new AssertionError("No instances.");
    }

    /**
     * Starts declaring a config file.
     *
     * <p>Nothing is read from disk until {@link Builder#load()} is called.
     *
     * @param plugin the plugin that owns the file
     * @param name   file name without extension, relative to the plugin folder;
     *               may contain {@code /} for subfolders, as in {@code menus/main}
     * @param schema the record type describing the file, which must have a
     *               no-argument constructor holding the defaults
     * @param <T>    the schema type
     * @return a builder to configure and then load
     */
    public static <T extends Record> @NotNull Builder<T> define(@NotNull Plugin plugin,
                                                                @NotNull String name,
                                                                @NotNull Class<T> schema) {
        return new Builder<>(plugin, name, schema);
    }

    /**
     * Reloads every config file declared by a plugin.
     *
     * <p>Intended for a {@code /reload} command. Files are reloaded one by one,
     * and a failure in one does not stop the rest.
     *
     * @param plugin the plugin whose files should be reloaded
     * @return every issue found, across all files, empty when all were clean
     */
    public static @NotNull List<ConfigIssue> reloadAll(@NotNull Plugin plugin) {
        return FILES.values().stream()
                .filter(file -> file.ownedBy(plugin))
                .flatMap(file -> file.reload().stream())
                .toList();
    }

    /**
     * Forgets a plugin's config files.
     *
     * <p>Called by ExyliaLib when the plugin is disabled. Consumers do not need
     * to call this.
     *
     * @param pluginName the name of the plugin being disabled
     */
    public static void release(@NotNull String pluginName) {
        FILES.values().removeIf(file -> file.pluginName().equals(pluginName));
        SchemaCache.release(pluginName);
    }

    /**
     * Forgets the config files of one load of a plugin.
     *
     * <p>The same plugin reloaded in place is a different {@code Plugin} object
     * from a different classloader, and both loads can be present at once: a
     * reload tool disables and enables within one tick, so the new load has
     * already read its files by the time the old one's cleanup runs. Releasing
     * by name there would throw away the files the new load just read. This
     * releases only what the given load owns.
     *
     * @param plugin the load being let go
     * @since 1.64.0
     */
    public static void release(@NotNull Plugin plugin) {
        FILES.values().removeIf(file -> file.ownedBy(plugin));
        SchemaCache.release(plugin.getName(), plugin.getClass().getClassLoader());
    }

    /**
     * Forgets every config file of every plugin.
     *
     * <p>Called by ExyliaLib on shutdown. Consumers do not need to call this.
     */
    public static void releaseAll() {
        FILES.clear();
        SchemaCache.releaseAll();
    }

    /**
     * Configures a config file before it is loaded.
     *
     * @param <T> the schema record type
     */
    public static final class Builder<T extends Record> {

        private final Plugin plugin;
        private final String name;
        private final Class<T> schema;
        private final Map<Integer, Migration> migrations = new java.util.TreeMap<>();
        private int version = 1;

        Builder(Plugin plugin, String name, Class<T> schema) {
            this.plugin = plugin;
            this.name = name;
            this.schema = schema;
        }

        /**
         * Declares the current layout version of this file.
         *
         * <p>Only needed once you start renaming or removing keys. The version
         * is written into the file so ExyliaLib knows which migrations a given
         * file still needs.
         *
         * @param version the current version, starting at 1
         * @return this builder
         */
        public @NotNull Builder<T> version(int version) {
            if (version < 1) {
                throw new IllegalArgumentException("version must be at least 1, got " + version);
            }
            this.version = version;
            return this;
        }

        /**
         * Registers a step that upgrades a file <em>from</em> the given version
         * to the next one.
         *
         * <pre>{@code
         * .version(2)
         * .migration(1, Migration.rename("pool", "pool-size"))
         * }</pre>
         *
         * <p>A file already at the current version runs nothing.
         *
         * @param fromVersion the version this step upgrades away from
         * @param migration   what to rewrite
         * @return this builder
         */
        public @NotNull Builder<T> migration(int fromVersion, @NotNull Migration migration) {
            if (fromVersion < 1) {
                throw new IllegalArgumentException("fromVersion must be at least 1, got " + fromVersion);
            }
            if (migrations.putIfAbsent(fromVersion, migration) != null) {
                throw new IllegalArgumentException(
                        "A migration from version " + fromVersion + " is already registered for " + name);
            }
            return this;
        }

        /**
         * Reads the file, creating or updating it as needed, and returns the
         * handle.
         *
         * <p>Calling this twice for the same plugin and file name returns the
         * same handle rather than reading the file again.
         *
         * <p>This touches the disk. Call it in {@code onEnable}, not on a hot
         * path.
         *
         * @return the loaded config file
         */
        @SuppressWarnings("unchecked")
        public @NotNull ConfigFile<T> load() {
            String key = plugin.getName() + ":" + name;
            // A handle cached under this name but owned by a different Plugin
            // object belongs to a previous load: the plugin was reloaded in
            // place and ExyliaLib's cleanup for the old load, which runs a tick
            // after it is disabled, has not had a tick yet. Handing that handle
            // back would hand back a record built by the old classloader, and
            // assigning it fails as a ClassCastException between two versions of
            // the same class. Let the previous load go and read the file again.
            ConfigFileImpl<?> cached = FILES.get(key);
            if (cached != null && !cached.ownedBy(plugin)) {
                release(cached.owner());
            }
            return (ConfigFile<T>) FILES.computeIfAbsent(key, ignored -> {
                ConfigFileImpl<T> file = new ConfigFileImpl<>(plugin, name, schema, version, Map.copyOf(migrations));
                file.initialLoad();
                return file;
            });
        }
    }

    /**
     * Returns every config file currently loaded, for diagnostics.
     *
     * @return an immutable snapshot of the loaded files
     */
    public static @NotNull Collection<? extends ConfigFile<?>> loaded() {
        return List.copyOf(FILES.values());
    }
}
