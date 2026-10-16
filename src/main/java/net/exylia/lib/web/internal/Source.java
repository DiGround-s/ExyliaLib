package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import org.jetbrains.annotations.Nullable;

/**
 * Where a subject's values come from and go back to: a record-backed {@link ConfigFile}, or a
 * YAML file a plugin compiles itself ({@link YamlSource}). The runtime asks the same four
 * things of both.
 */
interface Source {

    /** The file, relative to the plugin's data folder and without {@code .yml}. */
    String name();

    /** What the browser draws the form from. */
    JsonObject schema(String pluginName);

    /** What the file holds now. */
    JsonObject values(String pluginName);

    /** The browser's values read the way the file would be, ready to compare and write. */
    Prepared prepare(String pluginName, JsonObject values);

    /**
     * One subject of a change, read: either the reason it is refused, or what the file would
     * hold afterwards and how to write it.
     */
    record Prepared(@Nullable String problem, @Nullable JsonObject after, @Nullable Runnable write) {

        static Prepared refused(String problem) {
            return new Prepared(problem, null, null);
        }
    }

    /** A record config file, read and written through the binder. */
    record RecordFile(ConfigFile<?> file) implements Source {

        @Override
        public String name() {
            return file.name();
        }

        @Override
        public JsonObject schema(String pluginName) {
            return net.exylia.lib.config.internal.RecordJson.schema(file.schema().type(), pluginName);
        }

        @Override
        public JsonObject values(String pluginName) {
            return ValueCodec.toJson(file.schema().type(), pluginName, file.get());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Prepared prepare(String pluginName, JsonObject values) {
            Class<?> type = file.schema().type();
            ValueCodec.Decoded<Object> decoded = ValueCodec.fromJson(type, pluginName, file.get(), values, file.name());
            if (decoded.rejected()) {
                ConfigIssue first = decoded.problems().get(0);
                return Prepared.refused(first.describe());
            }
            Object value = decoded.value();
            return new Prepared(null, ValueCodec.toJson(type, pluginName, value),
                    () -> ((ConfigFile<Object>) file).update(current -> value));
        }
    }
}
