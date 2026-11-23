package net.exylia.lib.web.internal;

import net.exylia.lib.web.FolderSubject;
import net.exylia.lib.web.Problem;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.function.BiFunction;

final class FolderSubjectImpl extends SubjectImpl implements FolderSubject {

    private final FolderSource folder;

    private FolderSubjectImpl(Plugin plugin, String id, FolderSource folder, String kind) {
        super(plugin, id, folder, kind);
        this.folder = folder;
    }

    static FolderSubjectImpl of(Plugin plugin, String id, String path, String kind) {
        if (!YamlSubjectImpl.PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Not a folder path: '" + path
                    + "' (relative to the data folder: letters, digits, -, _ and /)");
        }
        return new FolderSubjectImpl(plugin, id, new FolderSource(new File(plugin.getDataFolder(), path), path), kind);
    }

    @Override
    public @NotNull FolderSubject check(@NotNull BiFunction<String, ConfigurationSection, List<String>> checker) {
        return problems((entry, yaml) -> {
            List<String> lines = checker.apply(entry, yaml);
            return lines == null ? List.of() : lines.stream().map(Problem::of).toList();
        });
    }

    @Override
    public @NotNull FolderSubject problems(@NotNull BiFunction<String, ConfigurationSection, List<Problem>> checker) {
        folder.problems((entry, yaml) -> {
            List<Problem> found = checker.apply(entry, yaml);
            return found == null ? List.of() : found;
        });
        return this;
    }

    @Override
    public @NotNull FolderSubject title(@NotNull String title) {
        super.title(title);
        return this;
    }

    @Override
    public @NotNull FolderSubject permission(@Nullable String permission) {
        super.permission(permission);
        return this;
    }

    @Override
    public @NotNull FolderSubject sensitive(@NotNull String... paths) {
        super.sensitive(paths);
        return this;
    }

    @Override
    public @NotNull FolderSubject group(@NotNull String group) {
        super.group(group);
        return this;
    }

    @Override
    public @NotNull FolderSubject readOnly() {
        super.readOnly();
        return this;
    }

    @Override
    public @NotNull FolderSubject onApplied(@NotNull Runnable listener) {
        super.onApplied(listener);
        return this;
    }
}
