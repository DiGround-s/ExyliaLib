package net.exylia.lib.web;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One thing a checker found wrong, optionally pinned to the field it belongs to, so the browser
 * shows it on that field while the admin edits.
 *
 * <p>The path is dotted, the way the file writes its keys, with list items as their index:
 * {@code steps.2.objective}. A {@link YamlSubject} names it from the file's root; an
 * {@link EntryStore} names it from the entry's root ({@code world}, not {@code mines.spawn.world}),
 * and the library puts the entry in front.
 *
 * @param path    where the problem is, or {@code null} when it belongs to no one field
 * @param message what is wrong, one line in English
 * @since 1.644.0
 */
public record Problem(@Nullable String path, @NotNull String message) {

    /** A problem on one field. */
    public static @NotNull Problem at(@NotNull String path, @NotNull String message) {
        return new Problem(path, message);
    }

    /** A problem with no field of its own. */
    public static @NotNull Problem of(@NotNull String message) {
        return new Problem(null, message);
    }

    /** {@code path: message}, or the message alone: how the admin reads it in chat. */
    public @NotNull String describe() {
        return path == null || path.isEmpty() ? message : path + ": " + message;
    }
}
