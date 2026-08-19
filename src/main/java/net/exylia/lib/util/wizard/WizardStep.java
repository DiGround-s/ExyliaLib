package net.exylia.lib.util.wizard;

import net.exylia.lib.input.AmountInput;
import net.exylia.lib.input.ChoiceInput;
import net.exylia.lib.input.ConfirmInput;
import net.exylia.lib.input.DurationInput;
import net.exylia.lib.input.FlagInput;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.input.NumberInput;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.input.TextInput;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.SelectionResult;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One step of a wizard definition.
 *
 * <h2>Why sealed</h2>
 * The set is closed on purpose. A wizard step is not an extension point: every
 * kind of step needs its own handling inside the session &mdash; a question
 * waits on an input future, a pick waits on a click, a region waits on a
 * selector, a branch is not waited on at all &mdash; and a step the session does
 * not recognise is a player standing still forever. Sealing turns "somebody
 * added a step kind and forgot the runtime" from a silent hang into a
 * compile error on the switch that dispatches them.
 *
 * <p>Each kind is a record because a step is a value: it is compiled once when
 * the plugin reads its configuration and then shared, unchanged, by every player
 * who ever runs that wizard. Nothing about one player's progress lives here.
 *
 * @since 1.34.0
 */
public sealed interface WizardStep {

    /**
     * The name of the answer this step collects.
     *
     * @return the answer's name, or {@code null} for a step that collects
     *         nothing of its own, such as a branch
     */
    default @Nullable String keyName() {
        return null;
    }

    /**
     * A question, asked through the {@code input} module.
     *
     * <p>The request is built when the step runs, not when it is declared,
     * because every request is bound to the player it asks. That is what lets
     * one compiled definition serve every player at once: the lambda is the
     * recipe, and the recipe is followed per run.
     *
     * @param key      where the answer is stored
     * @param question how to build the request, given a player-bound prompt
     *                 factory
     * @param <T>      the answer's type
     */
    record Question<T>(@NotNull WizardKey<T> key,
                       @NotNull Function<Prompt, InputRequest<T, ?>> question) implements WizardStep {

        /** Validates the step. */
        public Question {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(question, "question");
        }

        @Override
        public @NotNull String keyName() {
            return key.name();
        }
    }

    /**
     * A place in the world, answered by left-clicking a block.
     *
     * <p>Not a region selection with one corner. {@code Regions}' selector wants
     * two corners and completes only when it has both, so using it for a single
     * point would leave a session that never finishes. This is its own step, and
     * the module's one listener is what makes it possible.
     *
     * @param key    where the answer is stored
     * @param prompt what the player is told to click
     */
    record Pick(@NotNull WizardKey<Location> key, @NotNull String prompt) implements WizardStep {

        /** Validates the step. */
        public Pick {
            Objects.requireNonNull(key, "key");
            requirePrompt(prompt);
        }

        @Override
        public @NotNull String keyName() {
            return key.name();
        }
    }

    /**
     * A volume, answered with the shared block selector.
     *
     * <p>Wraps {@code PluginRegions.beginSelection}, which is globally exclusive
     * per player: if another plugin already owns that player's selector the run
     * ends as {@link WizardOutcome#REPLACED} rather than fighting over the
     * clicks, because from this run's point of view somebody else has the
     * player.
     *
     * @param key     where the answer is stored
     * @param prompt  what the player is told to select
     * @param options the selector material and interaction rules
     */
    record Region(@NotNull WizardKey<SelectionResult> key, @NotNull String prompt,
                  @NotNull SelectionOptions options) implements WizardStep {

        /** Validates the step. */
        public Region {
            Objects.requireNonNull(key, "key");
            requirePrompt(prompt);
            Objects.requireNonNull(options, "options");
        }

        @Override
        public @NotNull String keyName() {
            return key.name();
        }
    }

    /**
     * An item, answered by holding it and confirming.
     *
     * <p>What is stored is a clone taken at the moment of the confirmation.
     * Holding the live stack would mean the answer changed every time the player
     * moved their hotbar, and would usually be air by the time the summary was
     * accepted.
     *
     * @param key    where the answer is stored
     * @param prompt what the player is told to hold
     */
    record Hand(@NotNull WizardKey<ItemStack> key, @NotNull String prompt) implements WizardStep {

        /** Validates the step. */
        public Hand {
            Objects.requireNonNull(key, "key");
            requirePrompt(prompt);
        }

        @Override
        public @NotNull String keyName() {
            return key.name();
        }
    }

    /**
     * Steps that only apply when an earlier answer says so.
     *
     * <p>The predicate is evaluated when the branch is reached, against the
     * answers collected so far &mdash; never at build time, where there are no
     * answers to test. That is the whole reason a branch is a step rather than
     * an {@code if} around the builder call.
     *
     * @param key       the answer the decision is made on
     * @param predicate what makes the branch apply
     * @param steps     what to do when it does
     * @param <T>       the deciding answer's type
     */
    record Branch<T>(@NotNull WizardKey<T> key, @NotNull Predicate<T> predicate,
                     @NotNull List<WizardStep> steps) implements WizardStep {

        /** Validates the branch and defensively copies its steps. */
        public Branch {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(predicate, "predicate");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            if (steps.isEmpty()) {
                throw new WizardException("A branch on '" + key.name() + "' declares no steps.");
            }
        }
    }

    /**
     * The player-bound factory a question step is built from.
     *
     * <pre>{@code
     * .ask(SLOTS, step -> step.integer("How many players?").range(2L, 64L))
     * }</pre>
     *
     * <p>A thin pass-through to {@code PluginInputs}, existing only so a step
     * declaration does not have to name the player: the player is not known when
     * the wizard is compiled, and a definition that captured one would be a
     * definition that could serve exactly one person.
     *
     * <h2>What is missing, on purpose</h2>
     * There is no {@code form}. A form asks several things in one window, which
     * is what a wizard already is; nesting one inside a step would give the
     * player two review screens for one flow and would collect a
     * {@code FormValues} that the summary could neither list nor send back for
     * editing.
     *
     * @since 1.34.0
     */
    final class Prompt {

        private final PluginInputs inputs;
        private final Player player;

        private Prompt(PluginInputs inputs, Player player) {
            this.inputs = inputs;
            this.player = player;
        }

        /**
         * Binds a factory to the player a run belongs to.
         *
         * @param inputs the owning plugin's request factory
         * @param player who is being asked
         * @return the prompt handed to a step's lambda
         */
        @org.jetbrains.annotations.ApiStatus.Internal
        public static @NotNull Prompt bind(@NotNull PluginInputs inputs, @NotNull Player player) {
            return new Prompt(Objects.requireNonNull(inputs, "inputs"),
                    Objects.requireNonNull(player, "player"));
        }

        /**
         * Who is being asked.
         *
         * <p>For a request that needs the player for something other than being
         * shown to them, such as a choice over what they own.
         *
         * @return the player
         */
        public @NotNull Player player() {
            return player;
        }

        /**
         * Asks for free-form text.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull TextInput text(@NotNull String prompt) {
            return inputs.text(player, prompt);
        }

        /**
         * Asks for a strict lowercase identifier.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull TextInput id(@NotNull String prompt) {
            return inputs.id(player, prompt);
        }

        /**
         * Asks for text and turns it into an identifier.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull TextInput slug(@NotNull String prompt) {
            return inputs.slug(player, prompt);
        }

        /**
         * Asks for a whole number.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull NumberInput<Long> integer(@NotNull String prompt) {
            return inputs.integer(player, prompt);
        }

        /**
         * Asks for an exact decimal.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull NumberInput<BigDecimal> decimal(@NotNull String prompt) {
            return inputs.decimal(player, prompt);
        }

        /**
         * Asks for a player-formatted amount.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull AmountInput amount(@NotNull String prompt) {
            return inputs.amount(player, prompt);
        }

        /**
         * Asks for a length of time.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull DurationInput duration(@NotNull String prompt) {
            return inputs.duration(player, prompt);
        }

        /**
         * Asks for a yes or no.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull FlagInput flag(@NotNull String prompt) {
            return inputs.flag(player, prompt);
        }

        /**
         * Asks for an explicit confirmation.
         *
         * @param prompt what the player is asked
         * @return the request, to configure further
         */
        public @NotNull ConfirmInput confirm(@NotNull String prompt) {
            return inputs.confirm(player, prompt);
        }

        /**
         * Asks the player to choose one of a few things.
         *
         * @param prompt  what the player is asked
         * @param choices what they may choose from; never empty
         * @param <T>     the option type
         * @return the request, to configure further
         */
        public <T> @NotNull ChoiceInput<T> choice(@NotNull String prompt,
                                                  @NotNull Collection<T> choices) {
            return inputs.choice(player, prompt, choices);
        }

        /**
         * Asks the player to find one of many things.
         *
         * @param prompt  what the player is asked
         * @param choices what they may choose from; never empty
         * @param <T>     the option type
         * @return the request, to configure further
         */
        public <T> @NotNull SearchInput<T> search(@NotNull String prompt,
                                                   @NotNull Collection<T> choices) {
            return inputs.search(player, prompt, choices);
        }
    }

    /** Shared prompt validation, so every step rejects a blank prompt the same way. */
    private static void requirePrompt(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.isBlank()) {
            throw new WizardException("A wizard step needs a prompt: the player has to be told"
                    + " what to do, and an empty line tells them nothing.");
        }
    }
}
