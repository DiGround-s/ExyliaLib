package net.exylia.lib.web;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiFunction;

/**
 * A folder holding one YAML file per entry, made editable as one subject: its values are a map
 * from each file's name (without {@code .yml}) to what that file holds, so the browser lists the
 * entries and creates, edits and removes them like any map.
 *
 * <pre>{@code
 * WebEditor.of(this).folder("items", "items", "specials/item")
 *         .title("Items")
 *         .permission("exyliaspecials.admin")
 *         .sensitive("*.features.actions")
 *         .problems((name, yaml) -> registry.problems(name, yaml))
 *         .onApplied(registry::load);
 * }</pre>
 *
 * <p>Applying rewrites only the files whose contents changed, creates a file for each new entry
 * and deletes the file of each removed one; every deletion is held for {@code confirm}. The
 * checker runs once per entry, on exactly the text about to be written, and its problems are
 * pinned under the entry ({@code <name>.<field>}).
 *
 * @since 1.647.0
 */
public interface FolderSubject extends Subject {

    /** {@code null}: a folder is not one config file. */
    @Override
    @Nullable net.exylia.lib.config.ConfigFile<?> file();

    /**
     * What the plugin's own reader says about one entry, one line per problem; replaces any
     * checker set before.
     *
     * @param checker the entry's file name without {@code .yml}, and what it would hold
     * @return this subject
     */
    @NotNull FolderSubject check(@NotNull BiFunction<String, ConfigurationSection, List<String>> checker);

    /**
     * {@link #check} answering {@link Problem}s, each pinned to a field of the entry (dotted from
     * the file's root); the library puts {@code <name>.} in front.
     *
     * @param checker the entry's file name without {@code .yml}, and what it would hold
     * @return this subject
     */
    @NotNull FolderSubject problems(@NotNull BiFunction<String, ConfigurationSection, List<Problem>> checker);

    @Override
    @NotNull FolderSubject title(@NotNull String title);

    @Override
    @NotNull FolderSubject permission(@Nullable String permission);

    @Override
    @NotNull FolderSubject sensitive(@NotNull String... paths);

    @Override
    @NotNull FolderSubject group(@NotNull String group);

    @Override
    @NotNull FolderSubject readOnly();

    @Override
    @NotNull FolderSubject onApplied(@NotNull Runnable listener);
}
