package net.exylia.lib.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Something wrong found in a config file, reported instead of thrown.
 *
 * <p>A typo in a YAML file must never stop a server from starting, so loading
 * never fails: the default is used, the problem is logged, and the issue is kept
 * here so it can also be shown in game to whoever ran the reload command.
 *
 * @param type    what kind of problem it is
 * @param path    the full dotted path of the offending key, for example
 *                {@code database.settings.pool-size}
 * @param message a message written for the server owner, not for a developer
 * @param file    the config file the issue was found in, without extension
 * @since 1.1.0
 */
public record ConfigIssue(@NotNull Type type,
                          @NotNull String path,
                          @NotNull String message,
                          @NotNull String file) {

    /** The kind of problem found. */
    public enum Type {

        /**
         * The value could not be read as the type the schema declares, for
         * example {@code pool-size: "ten"} for an int. The default is used.
         */
        INVALID_VALUE,

        /**
         * The key is not in the file. The default is used, and the key is added
         * on the next save so the user can see and edit it.
         */
        MISSING_KEY,

        /**
         * The file has a key the schema does not define. Usually a typo or a
         * leftover from an older version. The value is kept untouched.
         */
        UNKNOWN_KEY,

        /**
         * The file itself could not be parsed, typically broken indentation.
         * Every default is used and the broken file is preserved so nothing the
         * user wrote is lost.
         */
        BROKEN_FILE
    }

    /**
     * Formats the issue as one line, ready for a log or a chat message.
     *
     * @return for example {@code [config.yml] database.pool-size: expected a
     *         whole number but found "ten", using 10}
     */
    public @NotNull String describe() {
        return "[" + file + ".yml] " + path + ": " + message;
    }

    /**
     * Builds an issue for a value that could not be read as the declared type.
     *
     * @param file     config file name without extension
     * @param path     full dotted path of the key
     * @param expected human readable description of the expected type
     * @param found    the raw value found in the file
     * @param fallback the value that will be used instead
     * @return the issue
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static @NotNull ConfigIssue invalidValue(@NotNull String file, @NotNull String path,
                                                    @NotNull String expected, @Nullable Object found,
                                                    @Nullable Object fallback) {
        return new ConfigIssue(Type.INVALID_VALUE, path,
                "expected " + expected + " but found " + quote(found) + ", using " + fallback,
                file);
    }

    private static String quote(@Nullable Object value) {
        if (value == null) {
            return "nothing";
        }
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }
}
