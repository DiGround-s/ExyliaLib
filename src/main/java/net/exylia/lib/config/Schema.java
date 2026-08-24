package net.exylia.lib.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.List;

/**
 * A read-only description of a config record's shape: its YAML keys, its
 * declared types, and the documentation written above them.
 *
 * <p>Obtained from {@link ConfigFile#schema()}. It is what lets a UI be
 * generated from a config file without the caller reflecting over the record
 * itself, and without reaching into {@code net.exylia.lib.config.internal}:
 *
 * <pre>{@code
 * for (Schema.Field field : storage.schema().fields()) {
 *     render(field.key(), field.type(), field.comments());
 * }
 * }</pre>
 *
 * <h2>It is a value, not a handle</h2>
 * A schema describes the <i>type</i>, never the values. Two {@code ConfigFile}s
 * of one record type holding completely different settings project schemas that
 * are {@link #equals(Object) equal}, and a schema taken before a reload is
 * unchanged after it. Reading a value stays {@link ConfigFile#get()}; writing
 * stays {@link ConfigFile#update}.
 *
 * <p>That is the point: nothing here is live. It holds no reference a caller
 * could follow back to the file, the backing YAML, or the canonical constructor.
 *
 * <h2>Threading</h2>
 * Deeply immutable and safe to read from any thread. Building one touches no
 * Bukkit API, no server and no filesystem, so it behaves identically on Spigot,
 * Paper, Purpur and Folia.
 *
 * <h2>Reload</h2>
 * Caches nothing derived from the colour palette, so this type is deliberately
 * exempt from the {@code invalidateAll()} rule; there is nothing to invalidate.
 *
 * @param type     the record class this schema describes
 * @param comments the {@link Comment} lines written above the section, in
 *                 declaration order; empty when undocumented
 * @param fields   the components, in canonical-constructor order
 * @since 1.50.0
 */
public record Schema(@NotNull Class<?> type,
                     @NotNull List<String> comments,
                     @NotNull List<Field> fields) {

    /**
     * Copies the lists it is given, so a caller holding the original cannot
     * mutate a schema after handing it over.
     */
    public Schema {
        comments = List.copyOf(comments);
        fields = List.copyOf(fields);
    }

    /**
     * One component of a config record: a value, or a nested section.
     *
     * <p>{@link #name()} and {@link #key()} are two different questions and are
     * kept apart on purpose. {@code name} is what the Java component is called
     * and is what an error message should quote; {@code key} is what the server
     * owner sees in the file, which {@link Key} may have renamed.
     *
     * @param name     the Java component name
     * @param key      the YAML key: the {@link Key} value, or the kebab-case
     *                 form of {@code name} when there is none
     * @param type     the declared type, erased
     * @param generic  the declared generic type, so the element type of a
     *                 {@code List<String>} is recoverable without reflecting
     *                 over the record again
     * @param comments the {@link Comment} lines written above this key, in
     *                 declaration order; empty when undocumented
     * @param nested   the schema of this component when it is itself a record,
     *                 otherwise {@code null}. This is the only accessor in the
     *                 projection that can answer {@code null}
     * @since 1.50.0
     */
    public record Field(@NotNull String name,
                        @NotNull String key,
                        @NotNull Class<?> type,
                        @NotNull Type generic,
                        @NotNull List<String> comments,
                        @Nullable Schema nested) {

        /** Copies the comment lines, for the reason given on {@link Schema}. */
        public Field {
            comments = List.copyOf(comments);
        }

        /**
         * Returns whether this component is a nested section rather than a value.
         *
         * <p>Equivalent to {@code nested() != null}, named for what a caller is
         * actually asking when it decides whether to draw a value control or a
         * link into a sub-section.
         *
         * @return {@code true} when {@link #nested()} is present
         */
        public boolean isSection() {
            return nested != null;
        }
    }
}
