package net.exylia.lib.web;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.web.internal.WebEditorRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * One plugin's registrations with the {@link WebEditor}.
 *
 * <p>Obtained from {@link WebEditor#of}. Registering the same id twice replaces the earlier
 * registration, so a plugin that re-enables a module re-registers freely. Everything a plugin
 * registered is forgotten when it is disabled.
 *
 * @since 1.304.0
 */
public final class PluginWebEditor {

    private final Plugin plugin;

    PluginWebEditor(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Makes a config file editable as a form drawn from its schema.
     *
     * @param id   the subject's name inside this plugin, such as {@code holograms}; letters,
     *             digits, {@code -} and {@code _}
     * @param file the file, as {@link net.exylia.lib.config.Configs#define} returned it
     * @param <T>  the file's record
     * @return the subject, to describe further
     * @throws IllegalArgumentException when the id is not a valid name
     */
    public <T extends Record> @NotNull Subject subject(@NotNull String id, @NotNull ConfigFile<T> file) {
        return subject(id, file, Subject.GENERIC);
    }

    /**
     * Makes a config file editable on a screen the editor ships for that shape.
     *
     * @param id   the subject's name inside this plugin
     * @param file the file
     * @param kind the screen, such as {@code displays/hologram}; {@link Subject#GENERIC} for
     *             the form every subject gets
     * @param <T>  the file's record
     * @return the subject, to describe further
     * @throws IllegalArgumentException when the id is not a valid name
     */
    public <T extends Record> @NotNull Subject subject(@NotNull String id, @NotNull ConfigFile<T> file,
                                                       @NotNull String kind) {
        return WebEditorRuntime.get().register(plugin, id, file, kind);
    }

    /**
     * Makes a YAML file the plugin reads itself editable, as a form inferred from its contents.
     *
     * <p>For files no record describes, such as a catalogue a loader compiles entry by entry.
     * Give it a {@link YamlSubject#check checker}: nothing else reads the browser's values
     * before they are written.
     *
     * @param id   the subject's name inside this plugin
     * @param path the file relative to the data folder, without {@code .yml}, such as
     *             {@code cosmetics/tags}; letters, digits, {@code -}, {@code _} and {@code /}
     * @return the subject, to describe further
     * @throws IllegalArgumentException when the id or the path is not valid
     * @since 1.305.0
     */
    public @NotNull YamlSubject yaml(@NotNull String id, @NotNull String path) {
        return yaml(id, path, Subject.GENERIC);
    }

    /**
     * Makes a YAML file the plugin reads itself editable on a screen the editor ships for it.
     *
     * @param id   the subject's name inside this plugin
     * @param path the file relative to the data folder, without {@code .yml}
     * @param kind the screen, such as {@code chatcosmetics/catalog}; {@link Subject#GENERIC}
     *             for the form
     * @return the subject, to describe further
     * @throws IllegalArgumentException when the id or the path is not valid
     * @since 1.305.0
     */
    public @NotNull YamlSubject yaml(@NotNull String id, @NotNull String path, @NotNull String kind) {
        return WebEditorRuntime.get().registerYaml(plugin, id, path, kind);
    }

    /**
     * Forgets one subject, for a module that is turned off while its plugin keeps running.
     *
     * @param id the subject's name
     */
    public void remove(@NotNull String id) {
        WebEditorRuntime.get().unregister(plugin.getName(), id);
    }
}
