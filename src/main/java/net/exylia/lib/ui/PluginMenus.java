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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
