package net.exylia.lib.util.showcase;

import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * One showcase, as its act sees it at the start of a turn.
 *
 * <p>Handed to {@link ShowcaseAct#play} and good for that call: the watching
 * list is who was close enough at that moment.
 *
 * @since 1.188.0
 */
public interface ShowcaseStage {

    /**
     * Where it stands, facing the way the admin who placed it faced.
     *
     * @return a copy, free to change
     */
    @NotNull Location where();

    /**
     * Who is close enough to watch, and allowed to. Never empty.
     *
     * @return an unmodifiable list, safe to hand to anything that keeps it
     */
    @NotNull List<Player> watching();

    /**
     * The same watchers as a sequence audience, anchored where the showcase
     * stands: within its radius and visible only to those allowed to see it.
     *
     * <p>Move it with {@link SequenceTarget#movedTo} to play somewhere else on
     * the stage, a body's chest or an arrow in flight.
     *
     * @return the audience
     */
    @NotNull SequenceTarget audience();

    /** The settings as they were when the turn started. */
    @NotNull ShowcaseSettings settings();

    /**
     * One of the watchers, at random: whose skin a body wears.
     *
     * @return a watcher
     */
    @NotNull Player someone();

    /**
     * Another watcher than this one, when there is another; the same one when
     * they are watching alone.
     *
     * <p>The second body of a pair: two players standing at a showcase see one
     * of them strike the other.
     *
     * @param other the one already cast
     * @return a different watcher when there is one
     */
    @NotNull Player someoneBut(@NotNull Player other);

    /**
     * The next cosmetic this showcase plays.
     *
     * <p>Only the ids {@link ShowcaseSettings#only()} names when it names any,
     * compared ignoring case, and never the one this showcase played last when
     * there is anything else: a crowd standing around never sees the same one
     * twice in a row. Remembered as played the moment it is picked.
     *
     * @param pool every cosmetic the plugin has
     * @param id   how to read one's id
     * @param <T>  the plugin's cosmetic type
     * @return the pick, or {@code null} when the pool, once filtered, is empty
     */
    <T> @Nullable T pick(@NotNull Collection<? extends T> pool, @NotNull Function<? super T, String> id);
}
