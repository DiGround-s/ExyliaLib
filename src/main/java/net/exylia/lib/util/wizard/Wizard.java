package net.exylia.lib.util.wizard;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A compiled guided flow, shared by every player who runs it.
 *
 * <pre>{@code
 * private Wizard arena;
 *
 * public void onEnable() {
 *     arena = Wizards.of(this).define("arena")
 *             .title("{primary}&lNEW ARENA")
 *             .ask(ID, step -> step.id("Enter the arena id"))
 *             .ask(SLOTS, step -> step.integer("Slots").range(2L, 64L))
 *             .summary()
 *             .onFinish(this::createArena)
 *             .build();
 * }
 * }</pre>
 *
 * <h2>Definition, not state</h2>
 * This object holds what the flow <em>is</em>. It holds nothing about anybody
 * running it: no current step, no half-built answers, no player. Compile it once
 * when the plugin loads its configuration and keep it in a field, exactly as a
 * {@code UiDefinition} is kept.
 *
 * <p>That separation is the point of the module. {@code EventConfigWizard} kept
 * its half-built state in a {@code static Map<UUID, String>}, which is a
 * definition and a run welded together: two players in the same flow shared one
 * object, a player who disconnected left their entry behind, and there was no
 * way to ask "what does this flow consist of?" without running it.
 *
 * <p>Immutable and safe to read from any thread. A run's mutable state lives in
 * its session, which nobody outside the module can reach.
 *
 * @since 1.34.0
 */
public final class Wizard {

    private final String id;
    private final String title;
    private final List<WizardStep> steps;
    private final boolean summary;
    private final boolean progress;
    private final Consumer<WizardValues> onFinish;
    private final Consumer<WizardOutcome> onCancel;

    Wizard(String id, String title, List<WizardStep> steps, boolean summary, boolean progress,
           @Nullable Consumer<WizardValues> onFinish, @Nullable Consumer<WizardOutcome> onCancel) {
        this.id = id;
        this.title = title;
        this.steps = steps;
        this.summary = summary;
        this.progress = progress;
        this.onFinish = onFinish;
        this.onCancel = onCancel;
    }

    /**
     * What this flow is called, for logs and diagnostics.
     *
     * @return the id it was defined under
     */
    public @NotNull String id() {
        return id;
    }

    /**
     * What the player sees named at the top of the flow.
     *
     * <p>Raw text in the library's notation: parsed per render rather than
     * cached as a component, so a palette reload recolours it without this
     * module needing to invalidate anything.
     *
     * @return the title
     */
    public @NotNull String title() {
        return title;
    }

    /**
     * The steps, in declaration order.
     *
     * <p>Includes branches as single entries; how many steps a given player
     * actually answers depends on what they answer, and is only knowable while
     * they are answering.
     *
     * @return the steps, read-only
     */
    public @NotNull List<WizardStep> steps() {
        return steps;
    }

    /**
     * Whether the player reviews and confirms before anything is applied.
     *
     * @return {@code true} when a summary was declared
     */
    public boolean hasSummary() {
        return summary;
    }

    /**
     * Whether a progress bar is shown while the flow runs.
     *
     * @return {@code true} when the bar is on
     */
    public boolean showsProgress() {
        return progress;
    }

    /**
     * How many steps a player would answer if every branch applied.
     *
     * <p>The upper bound, used for the progress bar's denominator before it is
     * known which branches will run. A bar that only ever counts up is worth
     * more than a bar whose total moves under the player.
     *
     * @return the deepest possible step count
     */
    public int stepCount() {
        return count(steps);
    }

    private static int count(List<WizardStep> list) {
        int total = 0;
        for (WizardStep step : list) {
            total += step instanceof WizardStep.Branch<?> branch ? count(branch.steps()) : 1;
        }
        return total;
    }

    /** What to run once, on a completed and confirmed run. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public @Nullable Consumer<WizardValues> onFinish() {
        return onFinish;
    }

    /** What to run when the flow ends any other way. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public @Nullable Consumer<WizardOutcome> onCancel() {
        return onCancel;
    }

    @Override
    public String toString() {
        return "Wizard[" + id + ", " + steps.size() + " steps]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Wizard that && id.equals(that.id) && steps.equals(that.steps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, steps);
    }
}
