package net.exylia.lib.config.internal;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/**
 * A YAML tree as a record file holds it once the record has read it and written it back: every key the
 * record owns, missing ones filled with its defaults, keys it does not own removed. What a bundled file
 * that is also read as a record looks like on disk when nobody has edited it.
 */
public final class RecordYaml {

    private RecordYaml() {
    }

    /** {@code yaml} read as {@code type} and written back, in a copy. */
    public static @NotNull YamlConfiguration normalised(@NotNull Class<?> type, @NotNull String pluginName,
                                                        @NotNull YamlConfiguration yaml) {
        SchemaNode schema = SchemaCache.of(type, pluginName);
        Object defaults = defaults(type);
        try {
            // Through a plain tree, whose separator is the dot the record's paths are written with, and back.
            YamlConfiguration plain = new YamlConfiguration();
            plain.loadFromString(yaml.saveToString());
            Object bound = Binder.read(plain, schema, defaults, "", "", new ArrayList<>());
            Binder.write(plain, schema, bound, defaults, "");
            YamlConfiguration copy = DefaultsMerge.yaml();
            copy.loadFromString(plain.saveToString());
            return copy;
        } catch (org.bukkit.configuration.InvalidConfigurationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object defaults(Class<?> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not build the defaults of " + type.getSimpleName(), failure);
        }
    }
}
