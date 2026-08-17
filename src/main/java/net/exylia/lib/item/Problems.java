package net.exylia.lib.item;

/**
 * Where a problem with part of an item is reported.
 *
 * <p>An item is many independent pieces, and one bad enchantment should not
 * cost the other twenty. ExyliaCommons agreed, but reported nothing: a
 * mistyped enchantment name simply did not appear, so a broken item looked
 * exactly like a working one until somebody counted the levels.
 *
 * <p>Structural mistakes are not reported here — they are thrown. A section
 * that does not describe an item is a different kind of wrong from an item with
 * a typo in one of its parts.
 *
 * @since 1.22.0
 */
@FunctionalInterface
public interface Problems {

    /**
     * Reports something wrong with a part of an item.
     *
     * @param where   which part, such as {@code enchantment SHARPNES}
     * @param problem what is wrong with it, written for whoever edits the file
     */
    void found(String where, String problem);

    /** Ignores problems, for callers that only want the definition. */
    Problems IGNORED = (where, problem) -> { };
}
