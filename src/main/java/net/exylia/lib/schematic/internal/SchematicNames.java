package net.exylia.lib.schematic.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Checks a schematic name before it becomes a path.
 *
 * <p>ExyliaCommons had no check at all, so {@code ../../plugins/Other/config}
 * was a valid schematic name and an empty one was a file called {@code .schem}
 * that every plugin shared.
 *
 * <p>A whitelist rather than a blacklist of separators: a blacklist has to
 * anticipate every platform's idea of a path, and the set of names an arena
 * actually wants is small.
 */
@ApiStatus.Internal
public final class SchematicNames {

    /** Long enough for any real arena name, short enough for every filesystem. */
    static final int MAX_LENGTH = 128;

    private SchematicNames() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns why a name is unusable, or {@code null} when it is fine.
     *
     * <p>Never throws: a refused name is a {@code FAILED} result and a console
     * line, not an exception that takes down the menu reading it.
     *
     * @param name the name a plugin's config holds
     * @return the reason it was refused, or {@code null}
     */
    public static @Nullable String reasonToRefuse(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return "a schematic name cannot be empty";
        }
        if (name.length() > MAX_LENGTH) {
            return "a schematic name cannot be longer than " + MAX_LENGTH
                    + " characters (was " + name.length() + ")";
        }
        if (name.charAt(0) == '.') {
            // Covers "..", and also the hidden file an empty-ish name produces.
            return "a schematic name cannot start with a dot: " + name;
        }
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (!isAllowed(character)) {
                return "a schematic name may only contain letters, digits, "
                        + "'.', '_' and '-': " + name;
            }
        }
        return null;
    }

    /**
     * Returns whether a name can be used.
     *
     * @param name the name
     * @return {@code true} when it is usable
     */
    public static boolean isValid(@Nullable String name) {
        return reasonToRefuse(name) == null;
    }

    /**
     * The file a name maps to, in either folder.
     *
     * @param name a name already checked
     * @return the file name
     */
    public static @NotNull String fileName(@NotNull String name) {
        return name + SchematicStore.EXTENSION;
    }

    private static boolean isAllowed(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == '-';
    }
}
