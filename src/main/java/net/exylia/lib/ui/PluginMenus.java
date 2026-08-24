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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
        Path target = dataFolder.resolve(relative).normalize();
        if (!target.startsWith(dataFolder)) {
            throw new IllegalArgumentException("Resource directory must stay inside the plugin data folder.");
        }

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
     * Takes a player back to the menu they came from.
     *
     * @param viewer the player
     * @return whether there was anywhere to go
     */
    public boolean back(@NotNull Player viewer) {
        return runtime.back(viewer);
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

    private static void extractBundledDirectory(Class<?> anchor, Path resourceDirectory, Path staging)
            throws IOException, URISyntaxException {
        if (anchor.getProtectionDomain().getCodeSource() == null) {
            throw new IOException("The anchor has no artifact location.");
        }
        URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
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

        String prefix = resourceDirectory.toString().replace('\\', '/');
        prefix = prefix.endsWith("/") ? prefix : prefix + "/";
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
