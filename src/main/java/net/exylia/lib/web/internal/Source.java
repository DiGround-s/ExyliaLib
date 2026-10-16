package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Where a subject's values come from and go back to: a record-backed {@link ConfigFile}, a
 * YAML file a plugin compiles itself ({@link YamlSource}), or entries a plugin stores itself
 * ({@link EntrySource}). The runtime asks the same four things of all of them.
 */
interface Source {

    /** The file, relative to the plugin's data folder and without {@code .yml}; empty when not a file. */
    String name();

    /** What the browser draws the form from. */
    JsonObject schema(String pluginName);

    /** What there is now. */
    JsonObject values(String pluginName);

    /** The browser's values read the way the source would, ready to compare and write. */
    Prepared prepare(String pluginName, JsonObject values);

    /**
     * One subject of a change, read: either the reason it is refused, or what it would hold
     * afterwards, what needs {@code confirm} on top of the diff, and how to write it.
     *
     * @param holds lines shown before applying, beyond the sensitive paths and console lines
     *              the runtime finds in the diff itself
     * @param write runs on the global thread; the future completes once written, or
     *              exceptionally with the reason it was not
     */
    record Prepared(@Nullable String problem, @Nullable JsonObject after, List<String> holds,
                    @Nullable Supplier<CompletableFuture<Void>> write) {

        static Prepared refused(String problem) {
            return new Prepared(problem, null, List.of(), null);
        }

        /** A write that is done when it returns. */
        static Prepared now(JsonObject after, Runnable write) {
            return new Prepared(null, after, List.of(), () -> {
                write.run();
                return CompletableFuture.completedFuture(null);
            });
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
            return Prepared.now(ValueCodec.toJson(type, pluginName, value),
                    () -> ((ConfigFile<Object>) file).update(current -> value));
        }
    }
}
