package net.exylia.lib.util.preview;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A preview that is running.
 *
 * <pre>{@code
 * Preview preview = previews.show(player, sequence, () -> menu.reopen(player));
 *
 * // The player clicked something else.
 * preview.end();
 * }</pre>
 *
 * <p>Every preview ends exactly once, however it ends: the effect finishing,
 * {@link #end()}, the player quitting or dying, the plugin being disabled, or
 * the safety timer. Whoever gets there first wins and the rest are ignored,
 * which is what makes the restore safe to trigger from anywhere.
 *
 * @since 1.31.0
 */
public interface Preview {

    /** Who is being shown the effect. */
    @NotNull Player viewer();

    /**
     * Ends it now and puts the player back.
     *
     * <p>Safe from any thread and safe to call twice. The completion callback
     * still runs, because a menu that opened a preview has to be reopened
     * whether the preview finished or was interrupted.
     */
    void end();

    /** Whether this has already ended. */
    boolean isFinished();
}
