package net.exylia.lib.ui;

import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.item.Problems;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.internal.BuiltInActions;
import net.exylia.lib.ui.internal.MenuLoader;
import net.exylia.lib.ui.internal.MenuRuntime;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The menus belonging to one plugin.
 *
 * <p>Obtained from {@link Menus#of(Plugin)}. Compiled definitions and open
 * windows both belong to the plugin, so disabling it releases them and nothing
 * else.
 *
 * <pre>{@code
 * PluginMenus menus = Menus.of(this);
 *
 * // when configs load, once
 * menus.load("kits", YamlConfiguration.loadConfiguration(kitsFile));
 *
 * // when somebody asks for it
 * menus.open(player, "kits");
 * }</pre>
 *
 * @since 1.22.0
 */
public final class PluginMenus {

    /** Key a packaged menu file declares to force {@link #refreshVersionedDirectory} over edits. */
    private static final String MENU_VERSION_KEY = "menu-version";

    /** What {@link #refreshVersionedDirectory} installed, by path: the hashes it compares against. */
    private static final String INSTALLED_FILES = ".bundled-files";

    private final Plugin plugin;
    private final MenuRuntime runtime;
    private final PluginActions actions;
    private final Debug debug;

    private UiSounds defaults = UiSounds.DEFAULTS;

    PluginMenus(Plugin plugin, String namespace) {
        this.plugin = plugin;
        this.runtime = MenuRuntime.of(plugin);
        this.actions = Actions.of(plugin, namespace);
        this.debug = Debug.of(plugin);
        // next_page, previous_page, back, close and refresh: every menu in the
        // ecosystem already writes them, and none of them are a plugin's job.
        BuiltInActions.register(actions);
    }

    /** The plugin these menus belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Sets what this plugin's menus sound like unless a file says otherwise.
     *
     * @param sounds the defaults
     * @return this
     */
    public @NotNull PluginMenus sounds(@NotNull UiSounds sounds) {
        this.defaults = sounds;
        return this;
    }

    // ----------------------------------------------------------------- loading

    /**
     * Compiles a menu and registers it under an id.
     *
     * <p>Do this when configuration loads, never when a player asks: reading a
     * file is the expensive half and opening a menu is the cheap one.
     *
     * <p>A part that will not compile — an action that does not exist, a
     * mistyped enchantment — becomes a dead button and a line in the console,
     * and the rest of the menu still opens. A file that does not describe a
     * menu at all throws, because guessing would hide the mistake.
     *
     * @param id     what to call it, such as {@code kits}
     * @param config the file's root section
     * @return the compiled menu
     * @throws IllegalArgumentException if the file is not a menu
     */
    public @NotNull UiDefinition load(@NotNull String id, @NotNull ConfigurationSection config) {
        return load(id, config, (where, problem) ->
                debug.warn("In menu \"" + id + "\", " + where + ": " + problem));
    }

    /**
     * Compiles a menu, reporting bad parts wherever the caller wants them.
     *
     * @param id       what to call it
     * @param config   the file's root section
     * @param problems where to report bad parts
     * @return the compiled menu
     */
    public @NotNull UiDefinition load(@NotNull String id, @NotNull ConfigurationSection config,
                                      @NotNull Problems problems) {
        UiDefinition definition = MenuLoader.load(qualify(id), config, actions::template,
                defaults, problems::found);
        runtime.register(definition);
        return definition;
    }

    /**
     * Registers an already-compiled menu.
     *
     * @param definition the menu
     * @return this
     */
    public @NotNull PluginMenus register(@NotNull UiDefinition definition) {
        runtime.register(definition);
        return this;
    }

    /**
     * A menu this plugin registered.
     *
     * @param id what it was called
     * @return the menu, or empty
     */
    public @NotNull Optional<UiDefinition> definition(@NotNull String id) {
        return Optional.ofNullable(runtime.definition(qualify(id)));
    }

    /**
     * Forgets every registered menu.
     *
     * <p>For a reload: the definitions go, and menus already on screen keep
     * working until they close. Re-registering is what a reload does next.
     */
    public void unload() {
        runtime.clearDefinitions();
    }

    /**
     * Replaces a directory in this plugin's data folder with its packaged files.
     *
     * <p>Call this during startup before loading bundled menus, so updated files
     * replace old defaults while administrator changes outside this directory
     * remain untouched.
     *
     * <pre>{@code
     * Menus.of(this).refreshBundledDirectory(MyPlugin.class, "menus/admin");
     * }</pre>
     *
     * <p>The directory is read recursively from {@code anchor}'s artifact and is
     * staged before the existing target is replaced. If extracting or replacing
     * it fails, the previous target remains in place and a warning is logged.
     *
     * @param anchor the consumer plugin class packaged with the resources
     * @param resourceDirectory a relative resource and data-folder directory
     * @return whether the packaged directory replaced the target
     * @throws IllegalArgumentException if the directory is blank, absolute, or escapes the data folder
     */
    public boolean refreshBundledDirectory(@NotNull Class<?> anchor, @NotNull String resourceDirectory) {
        Path relative = bundledDirectory(resourceDirectory);
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = insideDataFolder(dataFolder, relative);

        Path staging = null;
        Path backup = null;
        try {
            Files.createDirectories(dataFolder);
            staging = Files.createTempDirectory(dataFolder, ".bundled-");
            extractBundledDirectory(anchor, relative, staging);

            backup = Files.createTempDirectory(dataFolder, ".previous-");
            Files.delete(backup);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                move(target, backup);
            }
            try {
                move(staging, target);
            } catch (IOException replacementFailure) {
                try {
                    if (Files.exists(backup)) {
                        move(backup, target);
                        backup = null;
                    }
                } catch (IOException restorationFailure) {
                    replacementFailure.addSuppressed(restorationFailure);
                }
                throw replacementFailure;
            }
            staging = null;
            deleteTree(backup);
            backup = null;
            return true;
        } catch (IOException | URISyntaxException | SecurityException failure) {
            debug.warn("Could not refresh bundled directory \"" + resourceDirectory + "\": "
                    + failure.getMessage());
            return false;
        } finally {
            deleteTree(staging);
        }
    }

    /**
     * Installs a directory of menus players see and brings it up to date
     * without undoing what a server owner changed in it.
     *
     * <p>A packaged file opts into updates by declaring its version at the top:
     *
     * <pre>{@code
     * menu-version: 2
     * title: "..."
     * }</pre>
     *
     * <p>Every packaged file is written when it is missing, versioned or not,
     * so this one call replaces copying defaults by hand. A file already on
     * disk is replaced only when the packaged {@code menu-version} is higher
     * than its own; a file with no key counts as version 0, so the first
     * version a file declares reaches installations that predate it. Replacing
     * throws away the owner's changes, so the old file is kept next to it as
     * {@code <name>.v<old version>} and the update is logged. A file on disk
     * that does not parse is left untouched and reported: overwriting it would
     * turn a typo into lost work.
     *
     * <p>Each file is moved into place atomically and a failing one does not
     * stop the rest. Unlike {@link #refreshBundledDirectory(Class, String)},
     * nothing on disk is ever deleted.
     *
     * <pre>{@code
     * Menus.of(this).refreshVersionedDirectory(MyPlugin.class, "menus");
     * }</pre>
     *
     * @param anchor the consumer plugin class packaged with the resources
     * @param resourceDirectory a relative resource and data-folder directory
     * @return whether every packaged file is now installed and up to date;
     *         every reason it is not has been logged
     * @throws IllegalArgumentException if the directory is blank, absolute, or escapes the data folder
     * @since 1.157.0
     */
    public boolean refreshVersionedDirectory(@NotNull Class<?> anchor, @NotNull String resourceDirectory) {
        Path relative = bundledDirectory(resourceDirectory);
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = insideDataFolder(dataFolder, relative);

        Path staging = null;
        try {
            Files.createDirectories(dataFolder);
            staging = Files.createTempDirectory(dataFolder, ".bundled-");
            extractBundledDirectory(anchor, relative, staging);

            boolean upToDate = true;
            try (var files = Files.walk(staging)) {
                for (Path packaged : files.filter(Files::isRegularFile).toList()) {
                    Path file = staging.relativize(packaged);
                    String name = relative.resolve(file).toString().replace('\\', '/');
                    upToDate &= updateVersioned(packaged, target.resolve(file), name);
                }
            }
            return upToDate;
        } catch (IOException | URISyntaxException | SecurityException failure) {
            debug.warn("Could not refresh versioned directory \"" + resourceDirectory + "\": "
                    + failure.getMessage());
            return false;
        } finally {
            deleteTree(staging);
        }
    }

    /** Installs or updates one file, reporting a failure instead of throwing it. */
    private boolean updateVersioned(Path packaged, Path onDisk, String name) {
        try {
            Integer packagedVersion = declaredMenuVersion(packaged);
            if (Files.notExists(onDisk)) {
                install(packaged, onDisk);
                return true;
            }
            if (packagedVersion == null) {
                return true;
            }

            Integer declared;
            try {
                declared = declaredMenuVersion(onDisk);
            } catch (InvalidConfigurationException broken) {
                debug.warn("Not updating " + name + " to version " + packagedVersion + ": it could not be read ("
                        + broken.getMessage() + "). The file was left untouched.");
                return false;
            }
            int current = declared == null ? 0 : declared;
            if (current >= packagedVersion) {
                return true;
            }

            Path previous = onDisk.resolveSibling(onDisk.getFileName() + ".v" + current);
            Files.copy(onDisk, previous, StandardCopyOption.REPLACE_EXISTING);
            install(packaged, onDisk);
            debug.log("Updated " + name + " from version " + current + " to " + packagedVersion
                    + "; the previous file was kept as " + previous.getFileName() + ".");
            return true;
        } catch (IOException | InvalidConfigurationException | SecurityException failure) {
            debug.warn("Could not update " + name + ": " + failure.getMessage());
            return false;
        }
    }

    /**
     * Moves a staged file into place.
     *
     * <p>The staging directory lives in the data folder, so this is a rename:
     * a crash halfway never leaves a truncated menu behind.
     */
    private static void install(Path packaged, Path onDisk) throws IOException {
        Files.createDirectories(onDisk.getParent());
        move(packaged, onDisk);
    }

    /**
     * The {@code menu-version} a YAML file declares, or {@code null} if it
     * declares none or is not YAML.
     */
    private static @Nullable Integer declaredMenuVersion(Path file) throws IOException, InvalidConfigurationException {
        String name = file.getFileName().toString();
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml.isInt(MENU_VERSION_KEY) ? yaml.getInt(MENU_VERSION_KEY) : null;
    }

    // ------------------------------------------------------------------ opening

    /**
     * Opens a registered menu.
     *
     * <p>Safe from any thread: the work is moved onto the one that owns the
     * player, which is what makes this correct on Folia.
     *
     * @param viewer who to show it to
     * @param id     which menu
     * @return whether there is a menu by that name
     */
    public boolean open(@NotNull Player viewer, @NotNull String id) {
        return open(viewer, id, Map.of());
    }

    /**
     * Opens a registered menu with values it is about.
     *
     * <p>The context fills placeholders everywhere the menu draws — the title,
     * every fixed slot, every row — so a menu titled {@code %kit_name%} needs
     * no resolver of its own.
     *
     * <pre>{@code
     * menus.open(player, "leaderboard", Map.of("kit_name", kit.name()));
     * }</pre>
     *
     * @param viewer  who to show it to
     * @param id      which menu
     * @param context what it is about
     * @return whether there is a menu by that name
     */
    public boolean open(@NotNull Player viewer, @NotNull String id,
                        @NotNull Map<String, Object> context) {
        UiDefinition definition = runtime.definition(qualify(id));
        if (definition == null) {
            debug.warn("Something asked to open the menu \"" + id + "\", which is not loaded.");
            return false;
        }
        open(viewer, definition, context);
        return true;
    }

    /**
     * Opens a menu that was compiled but not registered.
     *
     * @param viewer     who to show it to
     * @param definition what to show
     * @param context    what it is about
     */
    public void open(@NotNull Player viewer, @NotNull UiDefinition definition,
                     @NotNull Map<String, Object> context) {
        Map<String, Object> copy = new LinkedHashMap<>(context);
        Tasks.of(plugin).runAtEntity(viewer, () -> runtime.open(viewer, definition, copy));
    }

    /**
     * Opens a menu and hands back the session it created.
     *
     * <p>For the caller that has to fill a list right away. Must be called on
     * the thread that owns the player, since it cannot return a session it has
     * not opened yet.
     *
     * @param viewer     who to show it to
     * @param definition what to show
     * @param context    what it is about
     * @return the session
     */
    public @NotNull UiSession openNow(@NotNull Player viewer, @NotNull UiDefinition definition,
                                      @NotNull Map<String, Object> context) {
        return runtime.open(viewer, definition, new LinkedHashMap<>(context));
    }

    /**
     * Opens a menu with its lists already filled.
     *
     * <p>What the caller that fills a list right away should use instead of
     * {@link #openNow(Player, UiDefinition, Map)} followed by
     * {@link UiSession#entries(String, java.util.Collection)}: the rows are in
     * before the menu is drawn, so every list slot is rendered once rather
     * than drawn as the pagination filler and immediately painted over. Must
     * be called on the thread that owns the player.
     *
     * @param viewer     who to show it to
     * @param definition what to show
     * @param context    what it is about
     * @param sections   the rows of each list, by section id
     * @return the session
     */
    public @NotNull UiSession openNow(@NotNull Player viewer, @NotNull UiDefinition definition,
                                      @NotNull Map<String, Object> context,
                                      @NotNull Map<String, ? extends Collection<UiEntry>> sections) {
        return runtime.open(viewer, definition, new LinkedHashMap<>(context), sections);
    }

    // ------------------------------------------------------------------ open ones

    /**
     * The menu a player has open, if it is one of this plugin's.
     *
     * @param viewer the player
     * @return the session, or empty
     */
    public @NotNull Optional<UiSession> session(@NotNull Player viewer) {
        UiSession session = runtime.publicSessionOf(viewer);
        return Optional.ofNullable(session);
    }

    /**
     * Every menu of this plugin that somebody has open right now.
     *
     * <p>What a timer that redraws open screens should walk. Asking every player
     * on the server whether they have something open is the same answer for far
     * more work, and it grows with the player count rather than with the number
     * of menus actually on screen.
     *
     * @return the open sessions, empty when nobody has one
     */
    public @NotNull List<UiSession> sessions() {
        return runtime.publicSessions();
    }

    /**
     * Takes a player back to the menu they came from.
     *
     * @param viewer the player
     * @return whether there was anywhere to go
     */
    public boolean back(@NotNull Player viewer) {
        return runtime.back(viewer);
    }

    /**
     * What a player left behind in a menu the last time they closed it.
     *
     * <p>The page of each list is put back on its own. This is for the rest of
     * where they were — the tab that was open, and anything else the menu
     * marked with {@link UiSession#remember(String...)}. Read it before
     * opening, because which tab was open decides which rows the menu builds:
     *
     * <pre>{@code
     * Object tab = menus.remembered(player, "effects").get("category");
     * }</pre>
     *
     * <p>Reading does not consume it, and nothing is put back behind the
     * caller's back: what to do with it is the menu's decision.
     *
     * @param viewer who is about to open it
     * @param menuId the menu
     * @return what they left, empty when there is nothing worth putting back
     * @since 1.84.4
     */
    public @NotNull Map<String, Object> remembered(@NotNull Player viewer, @NotNull String menuId) {
        return runtime.remembered(viewer, qualify(menuId));
    }

    /** Forgets where a player has been, so back has nowhere to go. */
    public void clearHistory(@NotNull Player viewer) {
        runtime.clearHistory(viewer);
    }

    /**
     * Closes whatever menu a player has open, if it is one of this plugin's.
     *
     * @param viewer the player
     */
    public void close(@NotNull Player viewer) {
        session(viewer).ifPresent(UiSession::close);
    }

    /** Qualifies a short id with this plugin's namespace, for readable logs. */
    private String qualify(String id) {
        return id.indexOf(':') >= 0 ? id : actions.namespace() + ':' + id;
    }

    private static Path bundledDirectory(String resourceDirectory) {
        if (resourceDirectory.isBlank()) {
            throw new IllegalArgumentException("Resource directory cannot be blank.");
        }
        Path directory = Path.of(resourceDirectory).normalize();
        if (directory.toString().isEmpty() || directory.isAbsolute() || directory.startsWith("..")) {
            throw new IllegalArgumentException("Resource directory must be relative and cannot escape its plugin.");
        }
        return directory;
    }

    private static Path insideDataFolder(Path dataFolder, Path relative) {
        Path target = dataFolder.resolve(relative).normalize();
        if (!target.startsWith(dataFolder)) {
            throw new IllegalArgumentException("Resource directory must stay inside the plugin data folder.");
        }
        return target;
    }

    private static void extractBundledDirectory(Class<?> anchor, Path resourceDirectory, Path staging)
            throws IOException, URISyntaxException {
        URL location = artifactOf(anchor);
        if (location == null) {
            // No artifact on disk to walk. A plugin whose classes were defined
            // from bytes — a bootstrap loader that decrypts its payload in
            // memory — has neither a jar to open nor a directory to list, and
            // its resources live only inside its classloader.
            extractFromClassLoader(anchor, resourceDirectory, staging);
            return;
        }
        URI artifact = location.toURI();
        if ("file".equals(artifact.getScheme()) && Files.isDirectory(Path.of(artifact))) {
            Path source = Path.of(artifact).resolve(resourceDirectory).normalize();
            if (!source.startsWith(Path.of(artifact)) || !Files.isDirectory(source)) {
                throw new IOException("Packaged directory does not exist.");
            }
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path destination = staging.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination);
                }
            }
            return;
        }

        String prefix = prefixOf(resourceDirectory);
        try (JarFile jar = new JarFile(Path.of(artifact).toFile())) {
            boolean found = false;
            for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                found = true;
                Path destination = staging.resolve(entry.getName().substring(prefix.length())).normalize();
                if (!destination.startsWith(staging)) {
                    throw new IOException("Packaged entry escapes the requested directory.");
                }
                Files.createDirectories(destination.getParent());
                try (var input = jar.getInputStream(entry)) {
                    Files.copy(input, destination);
                }
            }
            if (!found) {
                throw new IOException("Packaged directory does not exist.");
            }
        }
    }

    /**
     * The artifact the class was loaded from, or {@code null} when there is
     * none to read.
     *
     * <p>A class defined from a byte array carries the classloader's default
     * protection domain, whose code source is present but locationless. Both
     * that and a missing code source mean the same thing here: there is no jar
     * or directory to walk.
     */
    private static URL artifactOf(Class<?> anchor) {
        CodeSource source = anchor.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }

    /** The resource path of a directory, always ending in a slash. */
    private static String prefixOf(Path resourceDirectory) {
        String prefix = resourceDirectory.toString().replace('\\', '/');
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /**
     * Copies a packaged directory out of the classloader itself.
     *
     * <p>The names come from the loader's own resource table and the bytes come
     * back through {@link ClassLoader#getResourceAsStream}, so nothing here
     * depends on the payload existing as a file.
     */
    private static void extractFromClassLoader(Class<?> anchor, Path resourceDirectory, Path staging)
            throws IOException {
        String prefix = prefixOf(resourceDirectory);
        ClassLoader loader = anchor.getClassLoader();
        Collection<String> names = bundledResourceNames(loader, prefix);
        if (names.isEmpty()) {
            throw new IOException("Packaged directory does not exist.");
        }
        for (String name : names) {
            Path destination = staging.resolve(name.substring(prefix.length())).normalize();
            if (!destination.startsWith(staging)) {
                throw new IOException("Packaged entry escapes the requested directory.");
            }
            try (var input = loader.getResourceAsStream(name)) {
                if (input == null) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * The resources a classloader holds under a directory.
     *
     * <p>{@code ClassLoader} can hand back a resource by name but cannot list
     * one, so a loader that keeps its payload in memory is asked for its table
     * directly: every map it declares is read, and the keys that sit under the
     * directory are the entries. Reflection is the only door there is, and a
     * loader that does not open it simply reports nothing, which the caller
     * reads as "no packaged directory".
     */
    private static Collection<String> bundledResourceNames(ClassLoader loader, String prefix) {
        if (loader == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Class<?> type = loader.getClass(); type != null && type != ClassLoader.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(Modifier.isStatic(field.getModifiers()) ? null : loader);
                    if (!(value instanceof Map<?, ?> table)) {
                        continue;
                    }
                    for (Object key : table.keySet()) {
                        if (key instanceof String name && name.startsWith(prefix) && !name.endsWith("/")) {
                            names.add(name);
                        }
                    }
                } catch (RuntimeException | ReflectiveOperationException ignored) {
                    // A table this loader will not open is a table with nothing
                    // in it, as far as looking for packaged menus goes.
                }
            }
        }
        return names;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A failed cleanup must not change the refresh result.
                }
            });
        } catch (IOException ignored) {
            // A failed cleanup must not change the refresh result.
        }
    }

    /** The definitions this plugin registered, for diagnostics. */
    public @NotNull Map<String, UiDefinition> definitions() {
        return runtime.definitions();
    }

    /** The action namespace these menus compile against. */
    public @NotNull String namespace() {
        return actions.namespace();
    }

    /** The runtime, for the library's own lifecycle. Not part of the API. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public @Nullable MenuRuntime runtime() {
        return runtime;
    }
}
