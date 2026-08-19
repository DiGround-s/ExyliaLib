package net.exylia.lib.util.wizard;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionStage;

/**
 * One player's live pass through a wizard.
 *
 * <pre>{@code
 * WizardRun run = wizards.start(player, arena, () -> menu.open(player));
 *
 * // The player clicked something that makes the flow pointless.
 * run.cancel();
 * }</pre>
 *
 * <p>Every run ends exactly once, however it ends: the player confirming, a
 * cancel, a timeout, a disconnect, a newer wizard replacing it, the plugin being
 * disabled, or a callback that threw. Whoever gets there first wins and every
 * other path does nothing, which is what makes {@link #cancel()} safe to call
 * from a quit handler, a menu close, and a command at the same time.
 *
 * <p>The handle is a view, not the state: everything mutable lives inside the
 * session, which nothing outside the module can reach. A plugin that holds one
 * of these after it has finished holds a few fields and nothing that keeps a
 * player, a task or a window alive.
 *
 * @since 1.34.0
 */
public interface WizardRun {

    /**
     * Who is being walked through it.
     *
     * @return the player, whether or not they are still online
     */
    @NotNull Player player();

    /**
     * What they are being walked through.
     *
     * @return the definition
     */
    @NotNull Wizard wizard();

    /**
     * How many steps they have already answered.
     *
     * <p>Counts the steps actually reached, so a branch that did not apply is
     * not among them. Zero before the first answer.
     *
     * @return the number of answered steps
     */
    int stepIndex();

    /**
     * How many steps this run expects in total.
     *
     * <p>An upper bound while branches are still undecided: it can only fall as
     * a branch is skipped, never rise, so the bar never jumps backwards.
     *
     * @return the expected step count
     */
    int stepCount();

    /**
     * Whether this run has already ended.
     *
     * @return {@code true} once a terminal outcome has been delivered
     */
    boolean isFinished();

    /**
     * Ends it now, as a cancellation.
     *
     * <p>Safe from any thread and safe to call twice. The reopen callback given
     * to {@code start} still runs, because a menu that launched a wizard has to
     * come back whether the wizard finished or was interrupted.
     *
     * @return {@code true} when this call is the one that ended it
     */
    boolean cancel();

    /**
     * What the run produced, when it is done.
     *
     * <p>Completed exactly once, however the run ended, and completed even for
     * a player who has already left &mdash; a caller waiting on it must be
     * released rather than left holding a stage that never finishes. It is the
     * reason {@link WizardResult} exists at all: without this, the type would
     * be public and unreachable.
     *
     * <p>The callback runs on whichever thread delivered the ending, which is
     * not necessarily the player's. Anything touching Bukkit state from it has
     * to hop, exactly as it would from an input callback. For the common case
     * &mdash; do this once the flow is over, whatever happened &mdash; the
     * {@code afterwards} argument of {@code start} is the safer tool: it is
     * already on the player's thread and already skipped for somebody offline.
     *
     * @return the stage carrying the outcome and, on success, the answers
     */
    @NotNull CompletionStage<WizardResult> result();
}
