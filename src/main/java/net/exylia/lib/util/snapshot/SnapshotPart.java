package net.exylia.lib.util.snapshot;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One piece of a player that a snapshot can put back.
 *
 * <pre>{@code
 * // Everything, which is what almost every caller wants:
 * snapshot.restoreTo(player);
 *
 * // Only what a kit editor touched:
 * snapshot.restoreTo(player, SnapshotPart.set(
 *         SnapshotPart.INVENTORY, SnapshotPart.ARMOR, SnapshotPart.OFF_HAND));
 * }</pre>
 *
 * <h2>Why an enum and not six booleans</h2>
 * A restore used to be all or nothing, so a plugin that only wanted the
 * inventory back reached into the snapshot and applied it by hand &mdash; which
 * is how the ecosystem ended up with four copies of "put these items in this
 * player". A set of parts says the same thing in one argument, cannot be
 * assembled in the wrong order, and adding a part later does not change the
 * meaning of any call already written.
 *
 * <p>A part that the snapshot does not carry is skipped rather than defaulted.
 * A row written by ExyliaCommons has no ender chest and no physical state, and
 * asking for those parts on such a snapshot leaves the player's own alone
 * &mdash; emptying an ender chest because a two-year-old row never mentioned
 * one would be a very expensive way to be consistent.
 *
 * @since 1.33.0
 */
public enum SnapshotPart {

    /** The 36 main inventory slots. */
    INVENTORY,

    /** The four armour slots. */
    ARMOR,

    /** The off-hand slot. */
    OFF_HAND,

    /** The ender chest. Absent from anything ExyliaCommons wrote. */
    ENDER_CHEST,

    /** Health and maximum health. */
    HEALTH,

    /** Food level and saturation. */
    HUNGER,

    /** Experience level and progress. */
    EXPERIENCE,

    /** Active potion effects. Restoring them clears whatever is active first. */
    POTION_EFFECTS,

    /** The game mode. */
    GAME_MODE,

    /** Whether flight is allowed, whether they were flying, and how fast. */
    FLIGHT,

    /**
     * Fire ticks, remaining air, velocity, walk speed and invulnerability.
     *
     * <p>Absent from anything ExyliaCommons wrote.
     */
    PHYSICAL;

    /** Every part. The default for a restore, and the usual answer. */
    public static final Set<SnapshotPart> ALL =
            Collections.unmodifiableSet(EnumSet.allOf(SnapshotPart.class));

    /**
     * A set of parts, for a partial restore.
     *
     * @param parts the parts to include
     * @return an immutable set of them
     */
    public static @NotNull Set<SnapshotPart> set(@NotNull SnapshotPart... parts) {
        if (parts.length == 0) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.of(parts[0], parts));
    }

    /**
     * Every part except the ones named.
     *
     * <p>The shape a caller wants when one part belongs to somebody else
     * &mdash; a minigame that hands out its own kit still wants the health,
     * hunger and experience back.
     *
     * @param parts the parts to leave out
     * @return an immutable set of the rest
     */
    public static @NotNull Set<SnapshotPart> allExcept(@NotNull SnapshotPart... parts) {
        EnumSet<SnapshotPart> kept = EnumSet.allOf(SnapshotPart.class);
        for (SnapshotPart part : parts) {
            kept.remove(part);
        }
        return Collections.unmodifiableSet(kept);
    }
}
