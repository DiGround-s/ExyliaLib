package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.ConfigIssue;
import net.exylia.lib.web.Problem;
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

    /** The file it reads and writes, or {@code null} when it is not one file. */
    default @Nullable java.io.File disk() {
        return null;
    }

    /** Whether reading it has to happen on the global thread: entries live in the plugin's memory. */
    default boolean global() {
        return false;
    }

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
     * @param problems every problem found when it is refused, for the browser to pin to its field
     * @param console paths whose new text runs a line as the console where the diff cannot see it
     *              (a step inside a screen's markup), held for {@code confirm} like a console line
     */
    record Prepared(@Nullable String problem, @Nullable JsonObject after, List<String> holds,
                    @Nullable Supplier<CompletableFuture<Void>> write, List<String> console, List<Problem> problems) {

        Prepared(@Nullable String problem, @Nullable JsonObject after, List<String> holds,
                 @Nullable Supplier<CompletableFuture<Void>> write, List<String> console) {
            this(problem, after, holds, write, console, problem == null ? List.of() : List.of(Problem.of(problem)));
        }

        Prepared(@Nullable String problem, @Nullable JsonObject after, List<String> holds,
                 @Nullable Supplier<CompletableFuture<Void>> write) {
            this(problem, after, holds, write, List.of());
        }

        static Prepared refused(String problem) {
            return new Prepared(problem, null, List.of(), null);
        }

        /** Refused for every problem found; the admin reads the first one in chat. */
        static Prepared refused(List<Problem> problems) {
            return refused(problems.get(0).describe(), problems);
        }

        /** Refused for every problem found, the admin reading {@code reason} in chat. */
        static Prepared refused(String reason, List<Problem> problems) {
            return new Prepared(reason, null, List.of(), null, List.of(), List.copyOf(problems));
        }

        /** A write that is done when it returns. */
        static Prepared now(JsonObject after, Runnable write) {
            return new Prepared(null, after, List.of(), () -> {
                write.run();
                return CompletableFuture.completedFuture(null);
            });
        }
    }

    /**
     * A record config file, read and written through the binder, then through the plugin's own
     * checker when it set one.
     */
    final class RecordFile implements Source {

        private final ConfigFile<?> file;
        private volatile java.util.function.Function<Object, List<Problem>> check = value -> List.of();

        RecordFile(ConfigFile<?> file) {
            this.file = file;
        }

        ConfigFile<?> file() {
            return file;
        }

        /** What the plugin says about the decoded record; replaces any checker set before. */
        void problems(java.util.function.Function<Object, List<Problem>> check) {
            this.check = check;
        }

        @Override
        public String name() {
            return file instanceof net.exylia.lib.config.internal.ConfigFileImpl<?> impl ? impl.path() : file.name();
        }

        @Override
        public java.io.File disk() {
            return file instanceof net.exylia.lib.config.internal.ConfigFileImpl<?> impl ? impl.file() : null;
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
            Object value = decoded.value();
            // The binder's problems first, then the plugin's on the same record (a key the binder
            // refused holds its current value there), so the browser sees both at once.
            List<Problem> problems = new java.util.ArrayList<>();
            decoded.problems().forEach(issue -> problems.add(Problem.at(issue.path(), issue.message())));
            if (value != null) {
                try {
                    List<Problem> found = check.apply(value);
                    if (found != null) problems.addAll(found);
                } catch (RuntimeException failure) {
                    problems.add(Problem.of(failure.getMessage() == null ? failure.toString() : failure.getMessage()));
                }
            }
            if (!problems.isEmpty()) {
                return decoded.rejected()
                        ? Prepared.refused(decoded.problems().get(0).describe(), problems)
                        : Prepared.refused(problems);
            }
            return Prepared.now(ValueCodec.toJson(type, pluginName, value),
                    () -> ((ConfigFile<Object>) file).update(current -> value));
        }
    }
}
