package net.exylia.lib.util.crate;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * What a player already had in a plugin's own table before its crate moved onto
 * this module: the keys on their account and the rewards they unlocked.
 *
 * <p>Answered by the function given to {@link PluginCrates#importing} and
 * written on the player's crate row the one time that row is created, in place
 * of the start keys. The plugin's own columns are only read, never changed.
 *
 * @param keys     keys on the old account; below zero reads as zero
 * @param unlocked the reward ids unlocked there, in the order they came; ids
 *                 that are blank or carry a comma are left out
 * @since 1.190.0
 */
public record CrateImport(int keys, @NotNull List<String> unlocked) {

    public CrateImport {
        keys = Math.max(0, keys);
        unlocked = unlocked == null ? List.of() : unlocked.stream().filter(Objects::nonNull).toList();
    }

    /** Whether there is nothing to carry across: no keys and nothing unlocked. */
    public boolean isEmpty() {
        return keys == 0 && unlocked.isEmpty();
    }
}
