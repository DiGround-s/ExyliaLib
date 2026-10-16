package net.exylia.lib.web;

import net.exylia.lib.config.ConfigFile;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One config file made editable in the browser.
 *
 * <p>Every setter applies at once and returns the subject, so a registration reads as one
 * chain and nothing has to be called last. All of them are optional.
 *
 * @since 1.304.0
 */
public interface Subject {

    /** The kind of a subject edited as a form drawn from its schema. */
    String GENERIC = "generic";

    /** The plugin that registered it. */
    @NotNull Plugin plugin();

    /** Its name inside the plugin. */
    @NotNull String id();

    /** {@code <plugin>/<id>}: how the editor and a change refer to it. */
    @NotNull String key();

    /**
     * The file it edits, or {@code null} for a {@link YamlSubject}, which no record describes.
     */
    @Nullable ConfigFile<?> file();

    /** The screen the editor draws it with; {@link #GENERIC} for the form. */
    @NotNull String kind();

    /**
     * Where the editor files it: {@code content} for what a plugin registers (this method's
     * answer for every subject made through {@link PluginWebEditor}); a subject the library
     * discovered on its own is {@code settings}, {@code messages}, {@code menus} or
     * {@code files}.
     *
     * @since 1.307.0
     */
    @NotNull String group();

    /** What the editor calls it: the title set, or the id capitalised. */
    @NotNull String title();

    /** The permission a sender needs to see and change it, or {@code null} for any sender. */
    @Nullable String permission();

    /**
     * The paths whose changes need {@code confirm} before they are applied.
     *
     * @return the patterns given to {@link #sensitive}, as given
     */
    @NotNull java.util.List<String> sensitive();

    /**
     * What the editor shows as its name.
     *
     * @param title the name, in English
     * @return this subject
     */
    @NotNull Subject title(@NotNull String title);

    /**
     * Who may see and change it.
     *
     * <p>A subject without one is shown to anyone who may run {@code /exylialib editor}.
     * The console passes every check.
     *
     * @param permission the node, or {@code null} for anyone
     * @return this subject
     */
    @NotNull Subject permission(@Nullable String permission);

    /**
     * Paths whose changes are shown first and applied only with {@code confirm}.
     *
     * <p>Dotted YAML paths, where {@code *} stands for any one key, matched as a prefix:
     * {@code holograms.*.click} covers every hologram's whole {@code click} block. List
     * indices are ignored, so {@code pages.click} also covers {@code pages[2].click}. On top
     * of these, any changed line that starts a console command is sensitive by itself.
     *
     * @param paths the patterns to add
     * @return this subject
     */
    @NotNull Subject sensitive(@NotNull String... paths);

    /**
     * What to run after a change was written to the file, on the global thread.
     *
     * <p>{@link ConfigFile#update} only writes; whatever the plugin draws from the file is
     * the plugin's to refresh, exactly as after its own reload command.
     *
     * @param listener what to run; a throwable it throws is logged and does not undo the write
     * @return this subject
     */
    @NotNull Subject onApplied(@NotNull Runnable listener);
}
