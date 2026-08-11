package net.exylia.lib.config.internal;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.config.Migration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * The working parts of a config file: read, bind, migrate, write.
 *
 * <p>Loading follows a fixed order, and each step exists for a reason:
 * <ol>
 *   <li><b>read</b> the file, falling back to defaults if it is unreadable;</li>
 *   <li><b>migrate</b> it to the current layout, so old keys are carried over
 *       before anything looks for them;</li>
 *   <li><b>bind</b> it to the record, reporting rather than throwing;</li>
 *   <li><b>write</b> it back when keys were added, so the file always shows the
 *       full set of options.</li>
 * </ol>
 *
 * @param <T> the schema record type
 */
public final class ConfigFileImpl<T> implements ConfigFile<T> {

    /** Key holding the layout version. Leading dot-free name, written first. */
    private static final String VERSION_KEY = "config-version";

    private final Plugin plugin;
    private final String name;
    private final Class<T> schemaType;
    private final SchemaNode schema;
    private final int currentVersion;
    private final Map<Integer, Migration> migrations;
    private final File file;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    /** Published atomically on reload; readers never see a half-applied config. */
    private volatile T values;
    private volatile List<ConfigIssue> issues = List.of();

    public ConfigFileImpl(Plugin plugin, String name, Class<T> schemaType,
                          int currentVersion, Map<Integer, Migration> migrations) {
        this.plugin = plugin;
        this.name = name;
        this.schemaType = schemaType;
        this.schema = SchemaCache.of(schemaType, plugin.getName());
        this.currentVersion = currentVersion;
        this.migrations = migrations;
        this.file = new File(plugin.getDataFolder(), name.replace('/', File.separatorChar) + ".yml");
        this.values = defaults();
    }

    /** Reads the file for the first time, creating it when absent. */
    public void initialLoad() {
        List<ConfigIssue> found = load(true);
        logIssues(found);
    }

    @Override
    public @NotNull T get() {
        return values;
    }

    @Override
    public @NotNull List<ConfigIssue> reload() {
        List<ConfigIssue> found = load(false);
        logIssues(found);

        T snapshot = values;
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.SEVERE,
                        "A reload listener for " + name + ".yml threw an exception", throwable);
            }
        }
        return found;
    }

    @Override
    public void onReload(@NotNull Consumer<T> listener) {
        listeners.add(listener);
    }

    @Override
    public void save() {
        YamlConfiguration yaml = readFile(new ArrayList<>());
        if (yaml == null) {
            plugin.getLogger().warning("Not saving " + name + ".yml: it could not be read, "
                    + "and overwriting it would discard whatever is in there.");
            return;
        }
        render(yaml, values);
        writeFile(yaml);
    }

    /** Writes the schema into the YAML tree, including the file header. */
    private void render(YamlConfiguration yaml, T snapshot) {
        Binder.write(yaml, schema, snapshot, "");
        yaml.set(VERSION_KEY, currentVersion);
        // The leading null renders as a blank line, separating this bookkeeping
        // key from the settings that matter to the reader.
        yaml.setComments(VERSION_KEY, java.util.Arrays.asList(
                null,
                "Layout version of this file. ExyliaLib uses it to upgrade the file automatically.",
                "Do not edit."));

        List<String> header = Binder.header(schema);
        if (!header.isEmpty()) {
            yaml.options().setHeader(header);
        }
    }

    @Override
    public void update(@NotNull UnaryOperator<T> change) {
        synchronized (this) {
            values = change.apply(values);
            save();
        }
    }

    @Override
    public @NotNull List<ConfigIssue> issues() {
        return issues;
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    /** Returns whether this file belongs to the given plugin. */
    public boolean ownedBy(Plugin other) {
        return plugin.equals(other);
    }

    /** Returns the owning plugin's name. */
    public String pluginName() {
        return plugin.getName();
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private List<ConfigIssue> load(boolean initial) {
        List<ConfigIssue> found = new ArrayList<>();
        boolean existed = file.exists();

        YamlConfiguration yaml = readFile(found);
        if (yaml == null) {
            // Unreadable file. Keep whatever is already live rather than
            // reverting a running server to defaults behind the owner's back.
            issues = List.copyOf(found);
            return issues;
        }

        boolean migrated = existed && migrate(yaml, found);

        T bound = Binder.read(yaml, schema, defaults(), "", name, found);
        values = bound;

        // Writing back is what makes new keys appear in existing files, and what
        // keeps comments in sync with the code.
        boolean addedKeys = found.stream().anyMatch(issue -> issue.type() == ConfigIssue.Type.MISSING_KEY);
        if (!existed || migrated || addedKeys || initial) {
            render(yaml, bound);
            writeFile(yaml);
        }

        issues = List.copyOf(found);
        return issues;
    }

    private YamlConfiguration readFile(List<ConfigIssue> found) {
        if (!file.exists()) {
            return new YamlConfiguration();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            found.add(new ConfigIssue(ConfigIssue.Type.BROKEN_FILE, name,
                    "could not be read (" + exception.getMessage()
                            + "). The file was left untouched and previous values are still in use.",
                    name));
            return null;
        }
    }

    private boolean migrate(YamlConfiguration yaml, List<ConfigIssue> found) {
        // A file with no marker predates versioning, so it starts at 1.
        int fileVersion = yaml.getInt(VERSION_KEY, 1);
        if (fileVersion >= currentVersion) {
            return false;
        }

        boolean changed = false;
        for (int version = fileVersion; version < currentVersion; version++) {
            Migration migration = migrations.get(version);
            if (migration == null) {
                continue;
            }
            try {
                migration.apply(new YamlMutableConfig(yaml));
                changed = true;
                plugin.getLogger().info("Migrated " + name + ".yml from version "
                        + version + " to " + (version + 1) + ".");
            } catch (Throwable throwable) {
                found.add(new ConfigIssue(ConfigIssue.Type.INVALID_VALUE, name,
                        "migration from version " + version + " failed: " + throwable.getMessage(), name));
                plugin.getLogger().log(Level.SEVERE,
                        "Migration of " + name + ".yml from version " + version + " failed", throwable);
            }
        }

        yaml.set(VERSION_KEY, currentVersion);
        return changed || fileVersion != currentVersion;
    }

    /**
     * Writes through a temporary file and moves it into place.
     *
     * <p>A crash or a full disk halfway through a direct write leaves a
     * truncated config, which is worse than not writing at all. Moving a
     * finished file is atomic on every platform we target.
     */
    private void writeFile(YamlConfiguration yaml) {
        try {
            Path target = file.toPath();
            Files.createDirectories(target.getParent());

            Path temporary = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not write " + name + ".yml", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private T defaults() {
        try {
            var constructor = schemaType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (T) constructor.newInstance();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Could not build the defaults of " + schemaType.getSimpleName(), exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(
                    "The no-argument constructor of " + schemaType.getSimpleName() + " threw an exception",
                    exception.getCause());
        }
    }

    private void logIssues(List<ConfigIssue> found) {
        for (ConfigIssue issue : found) {
            switch (issue.type()) {
                case INVALID_VALUE, BROKEN_FILE ->
                        plugin.getLogger().warning(issue.describe());
                case UNKNOWN_KEY ->
                        plugin.getLogger().info(issue.describe());
                case MISSING_KEY -> {
                    // Expected on upgrades: the key is written back automatically.
                }
            }
        }
    }

    /** Exposed for tests: the file this config reads and writes. */
    public File file() {
        return file;
    }

    /** Exposed for tests: the section a migration would see. */
    ConfigurationSection rawForTesting() {
        return readFile(new ArrayList<>());
    }
}
