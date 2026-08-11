package net.exylia.lib.config.internal;

import net.exylia.lib.config.MutableConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link MutableConfig} backed by a Bukkit configuration section, handed to
 * migrations while they rewrite a file.
 */
record YamlMutableConfig(ConfigurationSection section) implements MutableConfig {

    @Override
    public @Nullable Object get(@NotNull String path) {
        return section.get(path);
    }

    @Override
    public void set(@NotNull String path, @Nullable Object value) {
        section.set(path, value);
    }

    @Override
    public void remove(@NotNull String path) {
        section.set(path, null);
    }

    @Override
    public boolean contains(@NotNull String path) {
        return section.contains(path);
    }
}
