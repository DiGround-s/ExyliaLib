package net.exylia.lib.util.wizard;

import net.exylia.lib.input.InputRequest;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.SelectionResult;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Declares a guided flow, once.
 *
 * <pre>{@code
 * Wizard arena = wizards.define("arena")
 *         .title("{primary}&lNEW ARENA")
 *         .ask(ID,    step -> step.id("Enter the arena id"))
 *         .ask(NAME,  step -> step.text("Display name"))
 *         .ask(SLOTS, step -> step.integer("Slots").range(2L, 64L))
 *         .pick(SPAWN, "Click the spawn block")
 *         .region(AREA, "Select the arena bounds")
 *         .hand(ICON, "Hold the icon and confirm")
 *         .when(KIND, kind -> kind == Kind.KOTH,
 *               branch -> branch.ask(POINTS, s -> s.integer("Capture points").range(1L, 5L)))
 *         .summary()
 *         .onFinish(this::createArena)
 *         .build();
 * }</pre>
 *
 * <h2>Wiring mistakes are caught here, not on the server</h2>
 * Two steps under one key, a branch guarded by a key nothing asked for, a flow
 * with no steps at all &mdash; each is a {@link WizardException} thrown while the
 * plugin is reading its configuration, naming the key that is wrong. The
 * alternative is a player halfway through a flow that cannot continue, which is
 * discovered by a player and reported as "it just stopped".
 *
 * <p>Not thread-safe, and not meant to be: a builder is used once, on the thread
 * that loads the configuration, and thrown away. The {@link Wizard} it produces
 * is immutable and shared.
 *
 * @since 1.34.0
 */
public final class WizardBuilder {

    private final String id;
    private final Collector collector;

    private String title;
    private boolean summary;
    private boolean progress;
    private Consumer<WizardValues> onFinish;
    private Consumer<WizardOutcome> onCancel;

    WizardBuilder(String id, boolean progressByDefault) {
        this.id = id;
        this.title = id;
        this.progress = progressByDefault;
        this.collector = new Collector(id, new LinkedHashSet<>());
    }

    /**
     * Names the flow for the player.
     *
     * <p>Raw text in the library's notation, so {@code {primary}} and
     * {@code &l} work. Shown in the progress bar and above the summary; without
     * one, the id is used, which is fine for an internal tool and wrong for
     * anything a player sees.
     *
     * @param title the title
     * @return this builder
     */
    public @NotNull WizardBuilder title(@NotNull String title) {
        Objects.requireNonNull(title, "title");
        if (title.isBlank()) {
            throw new WizardException("Wizard '" + id + "' was given a blank title.");
        }
        this.title = title;
        return this;
    }

    /**
     * Asks the player something.
     *
     * <p>The request is described, not built: the lambda receives a factory
     * bound to whichever player is running the flow, which is what lets one
     * definition serve everybody. Everything the {@code input} module offers is
     * available on it &mdash; ranges, validators, defaults, transports.
     *
     * @param key      where to store the answer
     * @param question how to build the request
     * @param <T>      the answer's type
     * @return this builder
     * @throws WizardException when that key is already answered by another step
     */
    public <T> @NotNull WizardBuilder ask(
            @NotNull WizardKey<T> key,
            @NotNull Function<WizardStep.Prompt, ? extends InputRequest<T, ?>> question) {
        collector.ask(key, question);
        return this;
    }

    /**
     * Asks the player to click a block.
     *
     * <p>The answer is where they clicked. A right-click on a block, a
     * left-click, or their own position on request &mdash; whatever the player's
     * client sends first for that block.
     *
     * @param key    where to store the answer
     * @param prompt what the player is told to click
     * @return this builder
     */
    public @NotNull WizardBuilder pick(@NotNull WizardKey<Location> key, @NotNull String prompt) {
        collector.pick(key, prompt);
        return this;
    }

    /**
     * Asks the player to select a volume, with the default selector.
     *
     * @param key    where to store the answer
     * @param prompt what the player is told to select
     * @return this builder
     */
    public @NotNull WizardBuilder region(@NotNull WizardKey<SelectionResult> key,
                                          @NotNull String prompt) {
        return region(key, prompt, SelectionOptions.DEFAULT);
    }

    /**
     * Asks the player to select a volume.
     *
     * <p>Uses the shared region selector, which is exclusive across every plugin
     * on the server. A run that cannot claim it ends as
     * {@link WizardOutcome#REPLACED} with the current owner named in the
     * console, rather than silently competing for the player's clicks.
     *
     * @param key     where to store the answer
     * @param prompt  what the player is told to select
     * @param options the selector material and interaction rules
     * @return this builder
     */
    public @NotNull WizardBuilder region(@NotNull WizardKey<SelectionResult> key,
                                          @NotNull String prompt,
                                          @NotNull SelectionOptions options) {
        collector.region(key, prompt, options);
        return this;
    }

    /**
     * Asks the player to hold an item and confirm.
     *
     * <p>The answer is a copy of what was in their main hand when they
     * confirmed, so nothing they do afterwards changes it.
     *
     * @param key    where to store the answer
     * @param prompt what the player is told to hold
     * @return this builder
     */
    public @NotNull WizardBuilder hand(@NotNull WizardKey<ItemStack> key, @NotNull String prompt) {
        collector.hand(key, prompt);
        return this;
    }

    /**
     * Includes some steps only when an earlier answer says so.
     *
     * <pre>{@code
     * .ask(KIND, step -> step.choice("Game type", List.of(Kind.values())))
     * .when(KIND, kind -> kind == Kind.KOTH,
     *       branch -> branch.ask(POINTS, s -> s.integer("Capture points")))
     * }</pre>
     *
     * <p>The predicate runs when the branch is reached, against what has been
     * answered by then. A branch guarded by a key that nothing has asked for yet
     * is a wiring bug and fails here, naming the key: at run time it would
     * simply never apply, and a step that quietly never happens is the hardest
     * kind of bug to see.
     *
     * @param key       the answer the decision is made on
     * @param predicate what makes the branch apply
     * @param branch    the steps to include
     * @param <T>       the deciding answer's type
     * @return this builder
     * @throws WizardException when the key is not answered before this point
     */
    public <T> @NotNull WizardBuilder when(@NotNull WizardKey<T> key,
                                            @NotNull Predicate<T> predicate,
                                            @NotNull Consumer<Branch> branch) {
        collector.when(key, predicate, branch);
        return this;
    }

    /**
     * Shows the player everything they answered and asks them to confirm.
     *
     * <p>This is what makes a wizard safe to abandon. Without it the flow
     * applies as soon as the last question is answered, which means a player who
     * mistyped step one has no way back and a player who walks away at step five
     * has already half-created something.
     *
     * <p>Denying the summary offers the list of answers to change; picking one
     * asks it again and returns to the summary. The number of rounds is capped
     * by {@code WizardSettings.maxRedos()} so the loop is bounded.
     *
     * @return this builder
     */
    public @NotNull WizardBuilder summary() {
        this.summary = true;
        return this;
    }

    /**
     * Turns the progress bar on or off for this flow.
     *
     * <p>Defaults to whatever the plugin's {@code WizardSettings} says. Turn it
     * off for a flow that runs while something else already owns the boss bar.
     *
     * @param progress whether to show it
     * @return this builder
     */
    public @NotNull WizardBuilder progress(boolean progress) {
        this.progress = progress;
        return this;
    }

    /**
     * What to do with the answers.
     *
     * <p>Runs <b>exactly once</b>, only on {@link WizardOutcome#COMPLETED}, and
     * only after the summary was confirmed when one was declared. A run that was
     * cancelled, timed out, disconnected, replaced or failed never reaches it,
     * which is the guarantee that lets a plugin do its creating here rather than
     * accumulating half-objects step by step.
     *
     * <p>Runs on the thread that owns the player, so it is safe to touch the
     * game from it. A callback that throws is reported against the owning plugin
     * and ends the run as {@link WizardOutcome#FAILED}.
     *
     * @param onFinish what to do
     * @return this builder
     */
    public @NotNull WizardBuilder onFinish(@NotNull Consumer<WizardValues> onFinish) {
        this.onFinish = Objects.requireNonNull(onFinish, "onFinish");
        return this;
    }

    /**
     * What to do when the flow ends without answers.
     *
     * <p>Optional, and rarely needed: the reopen callback passed to
     * {@code start} already runs however the flow ends, and the result stage
     * already carries the outcome. This is for a plugin that wants to say
     * something specific per outcome without holding on to the stage.
     *
     * <p>Runs on the thread that owns the player, and never for a player who has
     * already left.
     *
     * @param onCancel what to do, given why it ended
     * @return this builder
     */
    public @NotNull WizardBuilder onCancel(@NotNull Consumer<WizardOutcome> onCancel) {
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
        return this;
    }

    /**
     * Compiles the flow.
     *
     * <p>Everything the definition can be wrong about has already been checked
     * as it was declared; this is the last gate, for the one thing that can only
     * be known at the end.
     *
     * @return the immutable, shareable definition
     * @throws WizardException when the flow has no steps
     */
    public @NotNull Wizard build() {
        List<WizardStep> steps = collector.steps();
        if (steps.isEmpty()) {
            throw new WizardException("Wizard '" + id + "' declares no steps, so it would open"
                    + " and close in the same tick.");
        }
        return new Wizard(id, title, steps, summary, progress, onFinish, onCancel);
    }

    /**
     * The steps of one branch.
     *
     * <p>Everything a flow can declare except the parts that belong to the flow
     * as a whole: a branch has no title, no summary and no finish callback,
     * because a branch is part of one flow rather than a flow of its own.
     *
     * @since 1.34.0
     */
    public static final class Branch {

        private final Collector collector;

        private Branch(Collector collector) {
            this.collector = collector;
        }

        /**
         * Asks the player something.
         *
         * @param key      where to store the answer
         * @param question how to build the request
         * @param <T>      the answer's type
         * @return this branch
         * @see WizardBuilder#ask(WizardKey, Function)
         */
        public <T> @NotNull Branch ask(
                @NotNull WizardKey<T> key,
                @NotNull Function<WizardStep.Prompt, ? extends InputRequest<T, ?>> question) {
            collector.ask(key, question);
            return this;
        }

        /**
         * Asks the player to click a block.
         *
         * @param key    where to store the answer
         * @param prompt what the player is told to click
         * @return this branch
         * @see WizardBuilder#pick(WizardKey, String)
         */
        public @NotNull Branch pick(@NotNull WizardKey<Location> key, @NotNull String prompt) {
            collector.pick(key, prompt);
            return this;
        }

        /**
         * Asks the player to select a volume, with the default selector.
         *
         * @param key    where to store the answer
         * @param prompt what the player is told to select
         * @return this branch
         */
        public @NotNull Branch region(@NotNull WizardKey<SelectionResult> key,
                                       @NotNull String prompt) {
            return region(key, prompt, SelectionOptions.DEFAULT);
        }

        /**
         * Asks the player to select a volume.
         *
         * @param key     where to store the answer
         * @param prompt  what the player is told to select
         * @param options the selector material and interaction rules
         * @return this branch
         * @see WizardBuilder#region(WizardKey, String, SelectionOptions)
         */
        public @NotNull Branch region(@NotNull WizardKey<SelectionResult> key,
                                       @NotNull String prompt,
                                       @NotNull SelectionOptions options) {
            collector.region(key, prompt, options);
            return this;
        }

        /**
         * Asks the player to hold an item and confirm.
         *
         * @param key    where to store the answer
         * @param prompt what the player is told to hold
         * @return this branch
         * @see WizardBuilder#hand(WizardKey, String)
         */
        public @NotNull Branch hand(@NotNull WizardKey<ItemStack> key, @NotNull String prompt) {
            collector.hand(key, prompt);
            return this;
        }

        /**
         * Includes some steps only when an earlier answer says so.
         *
         * <p>Branches nest. The inner predicate sees everything answered before
         * it, including whatever the outer branch collected.
         *
         * @param key       the answer the decision is made on
         * @param predicate what makes the branch apply
         * @param branch    the steps to include
         * @param <T>       the deciding answer's type
         * @return this branch
         * @see WizardBuilder#when(WizardKey, Predicate, Consumer)
         */
        public <T> @NotNull Branch when(@NotNull WizardKey<T> key,
                                         @NotNull Predicate<T> predicate,
                                         @NotNull Consumer<Branch> branch) {
            collector.when(key, predicate, branch);
            return this;
        }
    }

    /**
     * The step list a builder and a branch both write into.
     *
     * <p>One implementation rather than two, because a branch's steps are
     * validated by exactly the same rules as a flow's: the same duplicate check,
     * the same "guarded by a key nobody asked for" check. Two copies of those
     * rules would drift, and the copy that drifted would be the branch one,
     * which is the harder path to test.
     */
    private static final class Collector {

        private final String wizardId;
        private final List<WizardStep> steps = new ArrayList<>();

        /**
         * Every key answered at or before this point, including the enclosing
         * scope's. A branch inherits it so a nested {@code when} can be checked
         * against what the flow already asked, not only against its own steps.
         */
        private final Set<String> declared;

        private Collector(String wizardId, Set<String> declared) {
            this.wizardId = wizardId;
            this.declared = declared;
        }

        <T> void ask(WizardKey<T> key,
                     Function<WizardStep.Prompt, ? extends InputRequest<T, ?>> question) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(question, "question");
            claim(key);
            steps.add(new WizardStep.Question<>(key, question::apply));
        }

        void pick(WizardKey<Location> key, String prompt) {
            claim(Objects.requireNonNull(key, "key"));
            steps.add(new WizardStep.Pick(key, prompt));
        }

        void region(WizardKey<SelectionResult> key, String prompt, SelectionOptions options) {
            claim(Objects.requireNonNull(key, "key"));
            steps.add(new WizardStep.Region(key, prompt, options));
        }

        void hand(WizardKey<ItemStack> key, String prompt) {
            claim(Objects.requireNonNull(key, "key"));
            steps.add(new WizardStep.Hand(key, prompt));
        }

        <T> void when(WizardKey<T> key, Predicate<T> predicate, Consumer<Branch> branch) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(branch, "branch");
            if (!declared.contains(key.name())) {
                throw new WizardException("Wizard '" + wizardId + "' branches on '" + key.name()
                        + "', but nothing asks for it before that point. A branch is decided"
                        + " against the answers collected so far, so this one could never"
                        + " apply.");
            }
            // The child shares the parent's set rather than copying it: a key
            // asked inside a branch is a key the flow may hold by the time a
            // later branch is reached, and forbidding a later `when` from
            // naming it would be forbidding a legitimate chain of questions.
            Collector nested = new Collector(wizardId, declared);
            branch.accept(new Branch(nested));
            steps.add(new WizardStep.Branch<>(key, predicate, nested.steps()));
        }

        private void claim(WizardKey<?> key) {
            if (!declared.add(key.name())) {
                throw new WizardException("Wizard '" + wizardId + "' asks for '" + key.name()
                        + "' twice. The second answer would overwrite the first, and the summary"
                        + " could only ever show one of them.");
            }
        }

        List<WizardStep> steps() {
            return List.copyOf(steps);
        }
    }
}
