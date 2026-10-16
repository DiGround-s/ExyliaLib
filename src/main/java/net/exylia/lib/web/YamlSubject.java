package net.exylia.lib.web;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * A YAML file the plugin reads itself made editable in the browser: a catalogue, a menu,
 * anything with no record behind it.
 *
 * <p>No binder stands between the browser and the file, so the plugin's own reader does:
 * {@link #check} receives what the file would hold and answers what is wrong with it, and a
 * change with any answer is refused whole.
 *
 * <pre>{@code
 * WebEditor.of(this).yaml("tags", "cosmetics/tags", "chatcosmetics/catalog")
 *         .title("Tags")
 *         .check(yaml -> catalog.problems(yaml))
 *         .onApplied(catalog::reload);
 * }</pre>
 *
 * @since 1.305.0
 */
public interface YamlSubject extends Subject {

    /** Always {@code null}: no record describes this file. */
    @Override
    @Nullable net.exylia.lib.config.ConfigFile<?> file();

    /**
     * What the plugin says about a file before it is written.
     *
     * <p>Runs on the global thread, on a copy: the file on disk is untouched until every
     * subject of the change passed. Read it the way the plugin's loader does and answer one
     * line per problem that would stop an entry from loading; warnings the loader shrugs off
     * are not problems. A checker that throws refuses the change.
     *
     * @param checker the contents to be written, to their problems; an empty list accepts
     * @return this subject
     */
    @NotNull YamlSubject check(@NotNull Function<ConfigurationSection, List<String>> checker);

    @Override
    @NotNull YamlSubject title(@NotNull String title);

    @Override
    @NotNull YamlSubject permission(@Nullable String permission);

    @Override
    @NotNull YamlSubject sensitive(@NotNull String... paths);

    @Override
    @NotNull YamlSubject onApplied(@NotNull Runnable listener);
}
