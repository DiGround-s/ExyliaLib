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

    /**
     * Files it under another group of the plugin's Settings tab instead of a tab of its own.
     *
     * <p>Only {@code content} subjects get a tab in the editor. A file that tunes how the plugin
     * behaves (its {@code config}, a chat filter) rather than listing things an admin creates
     * declares {@code settings}; {@code messages}, {@code menus} and {@code files} are the other
     * groups discovery uses.
     *
     * @param group {@code content} (the default), {@code settings}, {@code messages},
     *              {@code menus} or {@code files}
     * @return this subject
     * @throws IllegalArgumentException for any other group
     * @since 1.643.0
     */
    default @NotNull Subject group(@NotNull String group) {
        return this;
    }

    /**
     * Shows it in the editor without letting anyone change it: for what is placed in game (spawns,
     * spots, layouts set through a wizard), where an edit typed in the browser would only break it.
     *
     * <p>Its metadata carries {@code "readOnly": true}; a change or a draft that would alter it is
     * refused with a problem saying it is set in game, and so is an in-game edit through the
     * content screens.
     *
     * @return this subject
     * @since 1.647.0
     */
    default @NotNull Subject readOnly() {
        return this;
    }

    /**
     * Whether {@link #readOnly()} was called.
     *
     * @since 1.647.0
     */
    default boolean isReadOnly() {
        return false;
    }

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

    /**
     * What the plugin says about a record subject's values before they are written, on top of
     * the binder: the cross-references and rules a record cannot express (a phase list whose
     * lines must parse, a dungeon naming a loot table that must exist).
     *
     * <p>Runs on the global thread with the record exactly as it would be written, after the
     * binder read it; a key the binder refused still holds its current value. The binder's
     * problems and these are reported together, each on its field, and any of them refuses the
     * subject whole. Paths are dotted from the file's root, as {@link YamlSubject#problems}
     * names them. A checker that throws refuses the change with its message. Web edits,
     * drafts and in-game edits all go through it.
     *
     * <pre>{@code
     * WebEditor.of(this).subject("zones", zonesFile)
     *         .problems(ZonesFile.class, zones -> zones.problems());
     * }</pre>
     *
     * @param type    the file's record type, or a supertype of it
     * @param checker the record about to be written, to its problems; an empty list accepts
     * @return this subject
     * @throws IllegalArgumentException when {@code type} is not the file's record type
     * @throws IllegalStateException    when this subject is not a record file (entries check in
     *                                  their store, YAML subjects through {@link YamlSubject#problems})
     * @since 1.645.0
     */
    default <T> @NotNull Subject problems(@NotNull Class<T> type,
                                          @NotNull java.util.function.Function<? super T, java.util.List<Problem>> checker) {
        throw new IllegalStateException(key() + " is not a record subject");
    }

    /**
     * {@link #problems(Class, java.util.function.Function)} answering plain lines, each shown at
     * the top of the subject rather than on a field. Replaces any checker set before.
     *
     * @param type    the file's record type, or a supertype of it
     * @param checker the record about to be written, to one line per problem
     * @return this subject
     * @since 1.645.0
     */
    default <T> @NotNull Subject check(@NotNull Class<T> type,
                                       @NotNull java.util.function.Function<? super T, java.util.List<String>> checker) {
        return problems(type, value -> {
            java.util.List<String> lines = checker.apply(value);
            return lines == null ? java.util.List.of() : lines.stream().map(Problem::of).toList();
        });
    }

    /**
     * Extra facts the subject's {@link #kind() kind} screen reads besides the schema and values: a default to
     * reset to, sample words for a preview, the names an action may take. Sent as {@code details} in the plugin
     * payload, read afresh for every upload; a screen that does not know them ignores them.
     *
     * <p>The map is turned into JSON as Gson turns maps, lists, strings, numbers and booleans; a supplier that
     * throws, or answers {@code null}, sends none. Read off the main thread, so it reads memory and files only.
     *
     * @param details what to send, read once per upload
     * @return this subject
     * @since 1.464.0
     */
    default @NotNull Subject details(@NotNull java.util.function.Supplier<? extends java.util.Map<String, ?>> details) {
        return this;
    }

    /**
     * The same, read for the sender who opened the session: a screen whose samples depend on who looks (a
     * catalogue's first offer and words, read for that admin). Replaces a supplier given before.
     *
     * @param details what to send, read once per upload with the session's sender
     * @return this subject
     * @since 1.467.0
     */
    default @NotNull Subject details(@NotNull java.util.function.Function<? super org.bukkit.command.CommandSender,
            ? extends java.util.Map<String, ?>> details) {
        return this;
    }
}
