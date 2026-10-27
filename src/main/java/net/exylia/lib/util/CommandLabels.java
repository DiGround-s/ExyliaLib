package net.exylia.lib.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;

/**
 * The label of a typed command, and whether it is on an allowlist: the check every game that
 * limits commands while a player is in it makes.
 *
 * <pre>{@code
 * if (!CommandLabels.listed(event.getMessage(), config.allowedCommands())) event.setCancelled(true);
 * }</pre>
 *
 * <p>A label is compared without the slash, ignoring case and any {@code plugin:} prefix, so
 * {@code /Essentials:MSG Bob} is {@code msg}. Allowlist entries may be written with or without a
 * slash.
 *
 * @since 1.380.0
 */
public final class CommandLabels {

    private CommandLabels() {
    }

    /**
     * The label of a command line: lower case, without the slash or a {@code plugin:} prefix.
     *
     * @param line what was typed, such as {@code /msg Bob hi}
     * @return the label, such as {@code msg}; empty for a line with none
     */
    public static @NotNull String label(@Nullable String line) {
        if (line == null) return "";
        String trimmed = line.strip();
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        int space = trimmed.indexOf(' ');
        String label = space < 0 ? trimmed : trimmed.substring(0, space);
        int colon = label.indexOf(':');
        return (colon < 0 ? label : label.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a command line's label is on the allowlist.
     *
     * @param line    what was typed
     * @param allowed labels, with or without a slash, any case
     * @return {@code false} for a line with no label
     */
    public static boolean listed(@Nullable String line, @NotNull Collection<String> allowed) {
        String label = label(line);
        if (label.isEmpty()) return false;
        for (String entry : allowed) {
            if (entry != null && label.equals(label(entry))) return true;
        }
        return false;
    }
}
