package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What a shape's points are drawn with.
 *
 * <p>A shape says <em>where</em> and this says <em>what</em>. Splitting them is
 * what lets one geometry serve both kinds of effect: the same twenty shapes,
 * the same {@code ticks:} animation, the same {@code rotate:} and {@code scale:}
 * and the same visibility rules draw a ring of particles or a ring of swords,
 * and neither knows about the other.
 *
 * <p>Implementations are compiled once and shared by every play, so they must
 * hold nothing that belongs to one player or one moment.
 */
interface Paint {

    /**
     * What this draws for one play of one sequence.
     *
     * <p>Almost always itself: a flame is a flame whoever set it off. The hook
     * exists for paints that depend on who is involved &mdash; a head wearing
     * the victim's face &mdash; so that resolving it happens once per play
     * rather than once per point.
     *
     * @param target the play's target
     * @return the paint to use for this play
     */
    default @NotNull Paint forPlay(@NotNull SequenceTarget target) {
        return this;
    }

    /**
     * Draws one point, offset from the anchor.
     *
     * @param observers who sees it; the same list is handed to every point of a
     *                  frame and must not be modified
     * @param anchor    where the sequence is happening
     * @param x         offset east
     * @param y         offset up
     * @param z         offset south
     */
    void drawAt(@NotNull List<Player> observers, @NotNull Location anchor,
                double x, double y, double z);

    /**
     * How long what this draws keeps being visible after it is drawn.
     *
     * <p>Zero for a particle, which the client owns the moment it is sent. A
     * display lives for as long as its motion, and a sequence is not over until
     * the last one has gone &mdash; which is what stops a menu preview handing
     * the player back with swords still in the air.
     *
     * @return the time in milliseconds
     */
    default long trailMillis() {
        return 0L;
    }
}
