package net.exylia.lib.web.internal;

import net.exylia.lib.web.YamlSubject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/** A registered subject over a YAML file the plugin reads itself. */
final class YamlSubjectImpl extends SubjectImpl implements YamlSubject {

    static final Pattern PATH = Pattern.compile("[A-Za-z0-9_-]{1,64}(?:/[A-Za-z0-9_-]{1,64}){0,3}");

    private final YamlSource yaml;

    private YamlSubjectImpl(Plugin plugin, String id, YamlSource yaml, String kind) {
        super(plugin, id, yaml, kind);
        this.yaml = yaml;
    }

    static YamlSubjectImpl of(Plugin plugin, String id, String path, String kind) {
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Not a file path: '" + path
                    + "' (relative to the data folder, without .yml: letters, digits, -, _ and /)");
        }
        File file = new File(plugin.getDataFolder(), path + ".yml");
        return new YamlSubjectImpl(plugin, id, new YamlSource(file, path), kind);
    }

    /** A file discovery found: already inside the data folder, so its path is not held to {@link #PATH}. */
    static YamlSubjectImpl of(Plugin plugin, String id, File file, String path, String kind) {
        return new YamlSubjectImpl(plugin, id, new YamlSource(file, path), kind);
    }

    @Override
    public @NotNull YamlSubject check(@NotNull Function<ConfigurationSection, List<String>> checker) {
        yaml.check(checker);
        return this;
    }

    @Override
    public @NotNull YamlSubject title(@NotNull String title) {
        super.title(title);
        return this;
    }

    @Override
    public @NotNull YamlSubject permission(@Nullable String permission) {
        super.permission(permission);
        return this;
    }

    @Override
    public @NotNull YamlSubject sensitive(@NotNull String... paths) {
        super.sensitive(paths);
        return this;
    }

    @Override
    public @NotNull YamlSubject onApplied(@NotNull Runnable listener) {
        super.onApplied(listener);
        return this;
    }
}
