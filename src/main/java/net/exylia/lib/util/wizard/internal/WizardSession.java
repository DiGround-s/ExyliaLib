package net.exylia.lib.util.wizard.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.PluginEffects;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.input.InputResult;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.region.PluginRegions;
import net.exylia.lib.region.SelectionResult;
import net.exylia.lib.region.SelectionSession;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.wizard.Wizard;
import net.exylia.lib.util.wizard.WizardOutcome;
import net.exylia.lib.util.wizard.WizardResult;
import net.exylia.lib.util.wizard.WizardRun;
import net.exylia.lib.util.wizard.WizardSettings;
import net.exylia.lib.util.wizard.WizardStep;
import net.exylia.lib.util.wizard.WizardValues;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One player's pass through a wizard, from the first question to the way out.
 *
 * <h2>The only thing that really matters</h2>
 * That the run ends, once, and lets go of everything it took. A flow holds four
 * things a player can feel: an open question, the server's one block selector
 * for that player, a boss bar, and the single wizard slot that stops a second
 * flow from starting. Leaving any of them behind is worse than never starting:
 * a stuck selector means the next plugin to ask for one is refused, and a stuck
 * slot means the player can never run a wizard again until they reconnect.
 *
 * <p>So there is exactly one cleanup path. {@link #finish} claims the terminal
 * slot atomically, and every ending &mdash; confirm, cancel, timeout, quit,
 * plugin disable, replacement, a callback that threw &mdash; goes through it.
 * Whoever gets there first wins and the rest do nothing, which is what makes
 * {@code cancel()} safe to call from a quit handler and a menu close in the same
 * tick.
 *
 * <p>{@code EventConfigWizard} had the opposite shape: the cancel branch was
 * copy-pasted into each step, so each step could forget one of the four things,
 * and several did.
 *
 * <h2>Threading</h2>
 * Every player-facing action hops to the thread that owns the player with
 * {@code runAtEntity}. Input futures and selection stages complete on
 * unspecified threads, so nothing that arrives from them touches Bukkit before
 * that hop. The terminal slot, the answers and the step queue are guarded so an
 * ending arriving from a packet thread cannot tear a step running on the entity
 * thread.
 */
final class WizardSession implements WizardRun {

    private final Plugin plugin;
    private final Player player;
    private final UUID playerId;
    private final Wizard wizard;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final PluginInputs inputs;
    private final PluginEffects effects;
    private final Supplier<PluginRegions> regions;
    private final WizardSettings settings;
    private final Runnable afterwards;
    private final Runnable onRelease;

    private final CompletableFuture<WizardResult> future = new CompletableFuture<>();
    private final AtomicReference<WizardOutcome> terminal = new AtomicReference<>();
    private final AtomicBoolean finishInvoked = new AtomicBoolean();

    /** Guards the queue, the answers and the record of what has been asked. */
    private final Object state = new Object();

    private final Deque<WizardStep> remaining = new ArrayDeque<>();
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, WizardStep> asked = new LinkedHashMap<>();

    /**
     * Every answer some branch of this definition is guarded on.
     *
     * <p>Collected once per run rather than per redo, and kept here rather than
     * on the {@link Wizard} because the definition is shared by every player and
     * this is only ever read while walking one of them through it. Redoing a key
     * that is not in this set cannot change which steps apply, which is what
     * lets that case keep the cheap path straight back to the review.
     */
    private final java.util.Set<String> guards;

    /**
     * Which step a late callback belongs to. A cancelled input still completes
     * its future, and a redone step means two callbacks exist for one key; the
     * generation is what tells the stale one to do nothing.
     */
    private final AtomicReference<Long> generation = new AtomicReference<>(0L);

    private volatile int answered;
    private volatile Waiting waiting = Waiting.NOTHING;

    /**
     * The step being answered right now.
     *
     * <p>Held rather than looked up, because a step is taken off the queue
     * before it runs: by the time a block click arrives, the queue no longer
     * knows which pick it belongs to.
     */
    private volatile WizardStep current;

    private volatile String redoing;
    private volatile int redos;
    private volatile Display display;
    private volatile TaskHandle timeout;
    private volatile SelectionSession selection;

    /** What the run is currently blocked on, so cleanup knows what to release. */
    private enum Waiting { NOTHING, INPUT, PICK, REGION }

    WizardSession(Plugin plugin, Player player, Wizard wizard, TaskScheduler tasks, Debug debug,
                  PluginInputs inputs, PluginEffects effects, Supplier<PluginRegions> regions,
                  WizardSettings settings, @Nullable Runnable afterwards, Runnable onRelease) {
        this.plugin = plugin;
        this.player = player;
        this.playerId = player.getUniqueId();
        this.wizard = wizard;
        this.tasks = tasks;
        this.debug = debug;
        this.inputs = inputs;
        this.effects = effects;
        this.regions = regions;
        this.settings = settings;
        this.afterwards = afterwards;
        this.onRelease = onRelease;
        this.guards = guardKeys(wizard.steps());
        remaining.addAll(wizard.steps());
    }

    /** Every key a subtree could collect, however its branches turn out. */
    private static void collectKeys(List<WizardStep> steps, java.util.Set<String> into) {
        for (WizardStep step : steps) {
            if (step instanceof WizardStep.Branch<?> branch) {
                collectKeys(branch.steps(), into);
                continue;
            }
            String key = step.keyName();
            if (key != null) {
                into.add(key);
            }
        }
    }

    /** Every key a branch anywhere in the definition decides on. */
    private static java.util.Set<String> guardKeys(List<WizardStep> steps) {
        java.util.Set<String> found = new java.util.HashSet<>();
        collectGuards(steps, found);
        return java.util.Set.copyOf(found);
    }

    private static void collectGuards(List<WizardStep> steps, java.util.Set<String> into) {
        for (WizardStep step : steps) {
            if (step instanceof WizardStep.Branch<?> branch) {
                into.add(branch.key().name());
                collectGuards(branch.steps(), into);
            }
        }
    }

    // ----------------------------------------------------------------- handle

    @Override
    public @NotNull Player player() {
        return player;
    }

    @Override
    public @NotNull Wizard wizard() {
        return wizard;
    }

    @Override
    public int stepIndex() {
        return answered;
    }

    @Override
    public int stepCount() {
        synchronized (state) {
            // The step being answered right now left the queue before it ran, so
            // it has to be counted on its own. Without it the total rises by one
            // the moment the first answer arrives — a three-question flow opens
            // reading "1/2" — and the contract this bar exists for is that the
            // total only ever falls, as a branch is skipped. Counted only while
            // it has produced no answer, so re-asking an answered step during a
            // redo does not count it twice.
            WizardStep running = current;
            String name = running == null ? null : running.keyName();
            int inFlight = name != null && !values.containsKey(name) ? 1 : 0;
            return answered + inFlight + upperBound(remaining);
        }
    }

    @Override
    public boolean isFinished() {
        return terminal.get() != null;
    }

    @Override
    public boolean cancel() {
        return end(WizardOutcome.CANCELLED);
    }

    @Override
    public @NotNull CompletionStage<WizardResult> result() {
        return future;
    }

    /** Who this belongs to. */
    @NotNull UUID playerId() {
        return playerId;
    }

    // ------------------------------------------------------------------ start

    /**
     * Arms the safety net and asks the first question.
     *
     * <p>Called from wherever the plugin started the flow, which may be any
     * thread, so the first thing it does is hop onto the player's.
     */
    void start() {
        try {
            tasks.runAtEntity(player, this::begin, () -> end(WizardOutcome.DISCONNECTED));
        } catch (RuntimeException rejected) {
            // The plugin's scheduler is closed: it is being disabled between
            // building the wizard and starting it. Nothing has been shown yet.
            debug.warn("A wizard could not start for " + player.getName()
                    + " because its plugin is shutting down.");
            end(WizardOutcome.SHUT_DOWN);
        }
    }

    private void begin() {
        if (terminal.get() != null) {
            return;
        }
        if (!player.isOnline()) {
            end(WizardOutcome.DISCONNECTED);
            return;
        }
        // Armed before the first prompt, not after: a failure in between would
        // otherwise leave the player holding a flow with no timer to end it.
        arm();
        if (wizard.showsProgress()) {
            display = effects.bossBar(progressText()).colour("PURPLE").show(player);
        }
        advance();
    }

    /**
     * The safety net.
     *
     * <p>Each question already times out on its own, which is not enough: a
     * player who answers one every fifty seconds trips no single timeout and
     * holds the flow forever. This bounds the whole run.
     */
    private void arm() {
        long ticks = Math.max(1L, Ticks.fromSeconds(settings.timeoutSeconds()));
        timeout = tasks.runAtEntityLater(player, ticks, () -> {
            if (terminal.get() == null) {
                debug.debug("A wizard for " + player.getName() + " ran past its limit.");
                end(WizardOutcome.TIMED_OUT);
            }
        });
    }

    // ------------------------------------------------------------------- flow

    /**
     * Runs the next step, resolving branches on the way.
     *
     * <p>A loop rather than recursion: a run of several consecutive branches
     * that all fail would otherwise grow the stack for steps that never happen.
     *
     * <p>Runs on the player's thread.
     */
    private void advance() {
        while (true) {
            if (terminal.get() != null) {
                return;
            }
            if (!player.isOnline()) {
                end(WizardOutcome.DISCONNECTED);
                return;
            }
            WizardStep next;
            synchronized (state) {
                next = remaining.poll();
            }
            if (next == null) {
                conclude();
                return;
            }
            if (next instanceof WizardStep.Branch<?> branch) {
                if (!applies(branch)) {
                    // The predicate said no, or threw and was reported. Either
                    // way the branch's steps never enter the queue, so the
                    // upper bound this run reports falls by exactly that many.
                    if (terminal.get() != null) {
                        return;
                    }
                    continue;
                }
                synchronized (state) {
                    List<WizardStep> nested = branch.steps();
                    for (int i = nested.size() - 1; i >= 0; i--) {
                        remaining.addFirst(nested.get(i));
                    }
                }
                continue;
            }
            run(next);
            return;
        }
    }

    private <T> boolean applies(WizardStep.Branch<T> branch) {
        Object raw;
        synchronized (state) {
            raw = values.get(branch.key().name());
        }
        if (raw == null) {
            // The key was declared before this branch — the builder refuses
            // anything else — so the only way to get here is a branch nested
            // inside one that did not apply. It simply does not apply either.
            return false;
        }
        try {
            return branch.predicate().test(branch.key().cast(raw));
        } catch (RuntimeException broken) {
            debug.error("A branch of wizard '" + wizard.id() + "' on '" + branch.key().name()
                    + "' threw while deciding whether it applied.", broken);
            end(WizardOutcome.FAILED);
            return false;
        }
    }

    /** Dispatches one step. Sealed, so a new kind cannot silently go unhandled. */
    private void run(WizardStep step) {
        current = step;
        showProgress();
        switch (step) {
            case WizardStep.Question<?> question -> ask(question);
            case WizardStep.Pick pick -> pick(pick);
            case WizardStep.Region region -> select(region);
            case WizardStep.Hand hand -> hold(hand);
            case WizardStep.Branch<?> ignored ->
                    throw new IllegalStateException("A branch reached run(); advance() resolves them.");
        }
    }

    // --------------------------------------------------------------- question

    private <T> void ask(WizardStep.Question<T> step) {
        long mine = nextGeneration();
        waiting = Waiting.INPUT;

        CompletionStage<InputResult<T>> stage;
        try {
            InputRequest<T, ?> request =
                    step.question().apply(WizardStep.Prompt.bind(inputs, player));
            if (request == null) {
                throw new IllegalStateException("The step built no request.");
            }
            stage = request.open();
        } catch (RuntimeException broken) {
            debug.error("Step '" + step.key().name() + "' of wizard '" + wizard.id()
                    + "' could not be asked.", broken);
            end(WizardOutcome.FAILED);
            return;
        }

        stage.whenComplete((result, failure) -> onPlayerThread(mine, () -> {
            if (failure != null) {
                debug.error("Step '" + step.key().name() + "' of wizard '" + wizard.id()
                        + "' failed while waiting for an answer.", unwrap(failure));
                end(WizardOutcome.FAILED);
                return;
            }
            if (!result.completed()) {
                end(translate(result.outcome()));
                return;
            }
            record(step.key().name(), result.value(), step);
        }));
    }

    /**
     * Maps an input's ending onto the run's.
     *
     * <p>Straight across for everything a player did, because the run ended for
     * exactly that reason. {@code UNAVAILABLE} is the one that has to be
     * reinterpreted: nothing could ask the question, which is not something the
     * player chose and not a bug in the wizard, so it is reported as a failure
     * rather than silently looking like a cancel.
     */
    private WizardOutcome translate(net.exylia.lib.input.InputOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> WizardOutcome.CANCELLED;
            case TIMED_OUT -> WizardOutcome.TIMED_OUT;
            case DISCONNECTED -> WizardOutcome.DISCONNECTED;
            case REPLACED -> WizardOutcome.REPLACED;
            case SHUT_DOWN -> WizardOutcome.SHUT_DOWN;
            case UNAVAILABLE -> {
                debug.warn("A step of wizard '" + wizard.id() + "' could not be shown to "
                        + player.getName() + " by any transport.");
                yield WizardOutcome.FAILED;
            }
            case COMPLETED -> WizardOutcome.COMPLETED;
        };
    }

    // ------------------------------------------------------------------- pick

    private void pick(WizardStep.Pick step) {
        nextGeneration();
        waiting = Waiting.PICK;
        Text.from(plugin, step.prompt()).forPlayer(player).send(player);
    }

    /**
     * Answers a pick step with an interaction.
     *
     * <p>Called from the module's listener, on the thread that owns the player,
     * because that is the thread a player interaction fires on. The gesture the
     * step wants is decided here rather than in the listener: the listener has
     * no idea which step a run is on, and a run on a question step must not eat
     * a click at all.
     *
     * @param block    the block clicked, or {@code null} for a click at air
     * @param sneaking whether the player was sneaking
     * @return {@code true} when this run consumed the click, so the caller
     *         cancels the event rather than letting it break or open something
     */
    boolean interacted(@Nullable Block block, boolean sneaking) {
        if (waiting != Waiting.PICK || !(current instanceof WizardStep.Pick pick)) {
            return false;
        }
        if (!pick.standing()) {
            return block != null && locationPicked(block.getLocation());
        }
        // Sneak, because this gesture also answers at air: without a modifier
        // the first swing of the arm would end the step.
        return sneaking && locationPicked(player.getLocation());
    }

    /**
     * Answers a pick step with a place.
     *
     * @return {@code true} when a run was waiting for one
     */
    boolean locationPicked(@NotNull Location where) {
        if (waiting != Waiting.PICK || terminal.get() != null) {
            return false;
        }
        if (!(current instanceof WizardStep.Pick pick)) {
            return false;
        }
        waiting = Waiting.NOTHING;
        // A copy: the location a Bukkit event hands out is live enough that
        // holding it would make the answer follow the block's chunk around.
        record(pick.key().name(), where.clone(), pick);
        return true;
    }

    // ----------------------------------------------------------------- region

    private void select(WizardStep.Region step) {
        long mine = nextGeneration();
        waiting = Waiting.REGION;
        Text.from(plugin, step.prompt()).forPlayer(player).send(player);

        SelectionSession session;
        try {
            session = regions.get().beginSelection(player, step.options());
        } catch (IllegalStateException taken) {
            // The selector is one per player across the whole server. Somebody
            // else owns it, so this run does not get to fight for the clicks —
            // from here it looks exactly like being replaced.
            debug.warn("Wizard '" + wizard.id() + "' could not claim the block selector for "
                    + player.getName() + ": " + taken.getMessage());
            end(WizardOutcome.REPLACED);
            return;
        } catch (RuntimeException broken) {
            debug.error("Wizard '" + wizard.id() + "' could not start a selection for "
                    + player.getName() + '.', broken);
            end(WizardOutcome.FAILED);
            return;
        }
        selection = session;

        session.result().whenComplete((result, failure) -> onPlayerThread(mine, () -> {
            selection = null;
            if (failure != null) {
                Throwable cause = unwrap(failure);
                if (cause instanceof CancellationException) {
                    // The documented way a selection ends when the player or
                    // another plugin stops it. Not a fault; the player simply
                    // did not finish selecting.
                    end(WizardOutcome.CANCELLED);
                    return;
                }
                debug.error("A selection for wizard '" + wizard.id() + "' failed.", cause);
                end(WizardOutcome.FAILED);
                return;
            }
            record(step.key().name(), result, step);
        }));
    }

    // ------------------------------------------------------------------- hand

    private void hold(WizardStep.Hand step) {
        long mine = nextGeneration();
        waiting = Waiting.INPUT;

        CompletionStage<InputResult<Boolean>> stage;
        try {
            stage = inputs.confirm(player, step.prompt())
                    .confirmLabel("Use this item")
                    .open();
        } catch (RuntimeException broken) {
            debug.error("Step '" + step.key().name() + "' of wizard '" + wizard.id()
                    + "' could not ask for an item.", broken);
            end(WizardOutcome.FAILED);
            return;
        }

        stage.whenComplete((result, failure) -> onPlayerThread(mine, () -> {
            if (failure != null) {
                debug.error("Step '" + step.key().name() + "' of wizard '" + wizard.id()
                        + "' failed while waiting for an item.", unwrap(failure));
                end(WizardOutcome.FAILED);
                return;
            }
            if (!result.completed()) {
                end(translate(result.outcome()));
                return;
            }
            if (!result.value()) {
                end(WizardOutcome.CANCELLED);
                return;
            }
            ItemStack held = WizardRuntime.heldBy(player);
            if (held == null || held.getType() == Material.AIR) {
                Text.of("{warning}You are not holding anything. Take the item and try again.")
                        .send(player);
                // Asked again rather than failed: an empty hand is a mistake a
                // player makes and fixes in a second, and the run timeout still
                // bounds how long they may keep making it.
                hold(step);
                return;
            }
            // A clone, always. The live stack changes the moment they move it,
            // and is usually air by the time the summary is confirmed.
            record(step.key().name(), held.clone(), step);
        }));
    }

    // ---------------------------------------------------------------- answers

    /**
     * Stores an answer and moves on.
     *
     * <p>Runs on the player's thread.
     */
    private void record(String name, Object value, WizardStep step) {
        waiting = Waiting.NOTHING;
        boolean wasRedo = redoing != null;
        synchronized (state) {
            values.put(name, value);
            asked.put(name, step);
        }
        if (wasRedo) {
            redoing = null;
            // Straight back to the review only when the new answer cannot have
            // changed which steps apply. A key nothing branches on is by far the
            // common redo — a mistyped display name — and re-walking the whole
            // definition to discover that nothing moved would be work for
            // nothing.
            if (!guards.contains(name)) {
                showSummary();
                return;
            }
            reresolve();
            return;
        }
        answered++;
        showProgress();
        advance();
    }

    /**
     * Works out what the flow consists of now that a guard answer has changed.
     *
     * <p>The whole definition is walked again from the top against the current
     * answers, rather than trying to undo one branch in place. That is the only
     * version of this that is actually correct: branches nest, so a branch that
     * stops applying takes its children — and their children's branches — with
     * it, and the reverse case reopens a subtree whose steps have never been
     * asked. Deciding that incrementally means reimplementing
     * {@link #advance()}'s resolution a second time, differently.
     *
     * <p>Going straight back to the review instead, which is what this used to
     * do, is wrong in both directions and silently: a player who changes KOTH to
     * CONQUEST hands the finish callback a {@code points} that kind of event does
     * not have, and one who changes CONQUEST to KOTH hands it a set of answers
     * with a required one missing. Neither shows up until the plugin's own
     * creation code reads a field.
     *
     * <p>Answers survive by name, so everything still reachable keeps what the
     * player already typed: this re-asks the steps a branch just introduced, not
     * the flow. It cannot loop, because it adds no round of its own — the redo
     * counter and the run timeout are untouched.
     *
     * <p>Runs on the player's thread.
     */
    private void reresolve() {
        List<WizardStep> reachable = new ArrayList<>();
        java.util.Set<String> live = new java.util.LinkedHashSet<>();
        if (!walk(wizard.steps(), reachable, live)) {
            // A predicate threw; the run has already been ended and reported.
            return;
        }
        List<WizardStep> pending = new ArrayList<>();
        synchronized (state) {
            // An answer to a step that no longer applies is worse than no
            // answer: it reaches onFinish looking exactly like one the player
            // was asked for.
            values.keySet().retainAll(live);
            asked.keySet().retainAll(live);
            for (WizardStep step : reachable) {
                String key = step.keyName();
                if (key == null || !values.containsKey(key)) {
                    pending.add(step);
                }
            }
            remaining.clear();
            remaining.addAll(pending);
            // Recomputed rather than adjusted: the count of answers is what the
            // progress bar means, and a branch that just went away may have
            // taken several of them at once.
            answered = values.size();
        }
        showProgress();
        if (pending.isEmpty()) {
            showSummary();
            return;
        }
        // Ask what the new branch introduced first; advance() falls through to
        // conclude(), which brings the review back once they are all answered.
        advance();
    }

    /**
     * Collects the steps that apply, resolving branches against current answers.
     *
     * <p>{@code live} is built as the walk goes rather than read from
     * {@code values} wholesale, and that is the point: a branch is decided
     * against what has been asked <em>before</em> it, so a {@code when} guarded
     * by a key that a now-skipped sibling branch collected must see nothing,
     * exactly as it would on a first pass. Reading the map directly would let a
     * stale answer keep a branch alive after the branch that produced it died.
     *
     * <p>A branch whose guard is reachable but not yet answered is left whole in
     * the output instead of being decided or dropped. That happens when a branch
     * has just started applying and one of its own steps guards a nested one:
     * there is no answer to test yet, and {@link #advance()} will test it for
     * real once the player provides one.
     *
     * @param steps what to walk
     * @param into  where the applicable steps land, in order
     * @param live  the keys reachable so far, filled in as they are found
     * @return {@code false} when a predicate threw and the run has ended
     */
    private boolean walk(List<WizardStep> steps, List<WizardStep> into,
                         java.util.Set<String> live) {
        for (WizardStep step : steps) {
            if (terminal.get() != null) {
                return false;
            }
            if (step instanceof WizardStep.Branch<?> branch) {
                String guard = branch.key().name();
                if (!live.contains(guard)) {
                    // Nothing reachable answers it, so the branch cannot apply —
                    // the same conclusion advance() reaches from a null value.
                    continue;
                }
                boolean answered;
                synchronized (state) {
                    answered = values.containsKey(guard);
                }
                if (!answered) {
                    // Undecidable until the player answers the guard. Kept whole
                    // rather than guessed: dropping it would lose the branch for
                    // good, and taking it would ask for steps that may not apply.
                    into.add(branch);
                    // Its subtree counts as reachable even though nothing in it
                    // has been asked, so a later `when` guarded on one of those
                    // keys is deferred the same way instead of being dropped as
                    // unreachable. Safe for the pruning below because a key
                    // belongs to exactly one step: an unanswered guard means the
                    // branch never ran, so nothing under it holds a stale answer
                    // that retaining could smuggle through to onFinish.
                    collectKeys(branch.steps(), live);
                    continue;
                }
                if (!applies(branch)) {
                    // Either the predicate said no, or it threw and ended the
                    // run; the terminal slot is what tells the two apart.
                    if (terminal.get() != null) {
                        return false;
                    }
                    continue;
                }
                if (!walk(branch.steps(), into, live)) {
                    return false;
                }
                continue;
            }
            String key = step.keyName();
            if (key == null) {
                // Unreachable today: every non-branch step names its answer.
                // Skipped rather than trusted, so a future step kind that
                // collects nothing cannot be silently mistaken for one that
                // does and dropped from the review.
                continue;
            }
            live.add(key);
            into.add(step);
        }
        return true;
    }

    private WizardValues snapshot() {
        synchronized (state) {
            return WizardValues.of(values);
        }
    }

    // ---------------------------------------------------------------- summary

    /** The last step is answered: review it, or apply it. */
    private void conclude() {
        if (wizard.hasSummary()) {
            showSummary();
            return;
        }
        complete();
    }

    /**
     * Shows everything answered and asks for a yes.
     *
     * <p>Built from a confirm and a choice, and nothing else. That is why the
     * review works identically in a native dialog, a Bedrock form, an anvil, a
     * menu and in chat: it asks for no control that some of them lack. A custom
     * review screen would have had to be written five times, and four of them
     * would have rotted.
     */
    private void showSummary() {
        long mine = nextGeneration();
        waiting = Waiting.INPUT;

        CompletionStage<InputResult<Boolean>> stage;
        try {
            stage = inputs.confirm(player, summaryText())
                    .confirmLabel("Confirm")
                    .denyLabel("Change something")
                    .open();
        } catch (RuntimeException broken) {
            debug.error("The summary of wizard '" + wizard.id() + "' could not be shown.", broken);
            end(WizardOutcome.FAILED);
            return;
        }

        stage.whenComplete((result, failure) -> onPlayerThread(mine, () -> {
            if (failure != null) {
                debug.error("The summary of wizard '" + wizard.id() + "' failed.",
                        unwrap(failure));
                end(WizardOutcome.FAILED);
                return;
            }
            if (!result.completed()) {
                end(translate(result.outcome()));
                return;
            }
            if (result.value()) {
                complete();
                return;
            }
            offerRedo();
        }));
    }

    /**
     * Lets the player pick one answer to redo, then reviews again.
     *
     * <p>Bounded on purpose. Without a cap a player could deny, change, deny,
     * change forever, holding the flow, the selector and the wizard slot; the
     * run timeout would eventually end it, but only after minutes of a flow
     * nobody is going to finish.
     */
    private void offerRedo() {
        if (redos >= settings.maxRedos()) {
            Text.of("{warning}That is enough changes for now; nothing was created.")
                    .send(player);
            end(WizardOutcome.CANCELLED);
            return;
        }
        redos++;

        List<String> names;
        synchronized (state) {
            names = List.copyOf(asked.keySet());
        }
        if (names.isEmpty()) {
            end(WizardOutcome.CANCELLED);
            return;
        }

        long mine = nextGeneration();
        waiting = Waiting.INPUT;

        CompletionStage<InputResult<String>> stage;
        try {
            stage = inputs.<String>choice(player, "Which one do you want to change?", names)
                    .label(name -> name)
                    .open();
        } catch (RuntimeException broken) {
            debug.error("The review of wizard '" + wizard.id()
                    + "' could not offer its answers.", broken);
            end(WizardOutcome.FAILED);
            return;
        }

        stage.whenComplete((result, failure) -> onPlayerThread(mine, () -> {
            if (failure != null) {
                debug.error("The review of wizard '" + wizard.id() + "' failed.",
                        unwrap(failure));
                end(WizardOutcome.FAILED);
                return;
            }
            if (!result.completed()) {
                end(translate(result.outcome()));
                return;
            }
            WizardStep step;
            synchronized (state) {
                step = asked.get(result.value());
            }
            if (step == null) {
                showSummary();
                return;
            }
            // Marked before the step runs: the step's own completion reads this
            // to decide whether to move forward or come back to the review.
            redoing = result.value();
            run(step);
        }));
    }

    private String summaryText() {
        StringBuilder text = new StringBuilder(wizard.title());
        synchronized (state) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                text.append('\n')
                        .append("{letters_black}▎ {secondary}")
                        .append(entry.getKey())
                        .append(" {letters_black}» {letters}")
                        .append(describe(entry.getValue()));
            }
        }
        return text.toString();
    }

    /**
     * How one answer reads in the review.
     *
     * <p>The three types that are not text get written out by hand, because
     * their {@code toString()} is a debug dump: a location prints its pitch and
     * yaw, an item prints its meta, and a player reading either learns nothing
     * about whether they picked the right block.
     */
    private static String describe(Object value) {
        if (value instanceof Location where) {
            return where.getWorld().getName() + ' ' + where.getBlockX() + ", "
                    + where.getBlockY() + ", " + where.getBlockZ();
        }
        if (value instanceof SelectionResult region) {
            return region.world().fallbackName() + ' '
                    + region.first().x() + ", " + region.first().y() + ", " + region.first().z()
                    + " → "
                    + region.second().x() + ", " + region.second().y() + ", " + region.second().z();
        }
        if (value instanceof ItemStack item) {
            return item.getAmount() + "× " + item.getType().name().toLowerCase(java.util.Locale.ROOT);
        }
        return String.valueOf(value);
    }

    // --------------------------------------------------------------- terminal

    /**
     * Applies the flow, once.
     *
     * <p>The finish callback runs before the terminal slot is claimed, so a
     * callback that throws can still be reported as {@link WizardOutcome#FAILED}
     * rather than as a success with an exception in the log. The guard makes it
     * unreachable twice even if two confirmations somehow arrive.
     */
    private void complete() {
        if (!finishInvoked.compareAndSet(false, true)) {
            return;
        }
        WizardValues collected = snapshot();
        Consumer<WizardValues> onFinish = wizard.onFinish();
        if (onFinish != null) {
            try {
                onFinish.accept(collected);
            } catch (RuntimeException broken) {
                debug.error("What wizard '" + wizard.id() + "' was meant to create failed.",
                        broken);
                end(WizardOutcome.FAILED);
                return;
            }
        }
        finish(WizardOutcome.COMPLETED, collected);
    }

    /**
     * Ends without answers.
     *
     * <p>Safe from any thread and safe to call repeatedly.
     *
     * @return {@code true} when this call is the one that ended it
     */
    boolean end(@NotNull WizardOutcome outcome) {
        return finish(outcome, null);
    }

    private boolean finish(WizardOutcome outcome, @Nullable WizardValues collected) {
        // Whoever gets here first wins. Every other path — quit, timeout,
        // plugin disable, the caller — then does nothing.
        if (!terminal.compareAndSet(null, outcome)) {
            return false;
        }
        try {
            release();
        } finally {
            onRelease.run();
            deliver(outcome, collected);
        }
        return true;
    }

    /**
     * Lets go of everything the run was holding.
     *
     * <p>Each part is guarded on its own: a boss bar that will not stop must not
     * stop the selector from being released, because the selector is the one
     * another plugin is waiting for.
     */
    private void release() {
        Waiting held = waiting;
        waiting = Waiting.NOTHING;
        current = null;

        TaskHandle armed = timeout;
        timeout = null;
        if (armed != null) {
            armed.cancel();
        }

        SelectionSession pending = selection;
        selection = null;
        if (pending != null) {
            try {
                pending.cancel();
            } catch (RuntimeException broken) {
                debug.error("A wizard could not release " + player.getName()
                        + "'s block selection.", broken);
            }
        }

        // Only when this run was the one waiting on a question. `hasActive`
        // answers "does this player have a request", not "is it ours" — and a
        // run being replaced has, by definition, already been overtaken by the
        // flow that displaced it. Cancelling on that answer alone would close
        // the winner's first question the instant it opened.
        if (held == Waiting.INPUT) {
            try {
                if (Inputs.hasActive(player)) {
                    Inputs.cancel(player);
                }
            } catch (RuntimeException broken) {
                debug.error("A wizard could not close " + player.getName() + "'s question.",
                        broken);
            }
        }

        Display bar = display;
        display = null;
        if (bar != null) {
            try {
                bar.stop();
            } catch (RuntimeException broken) {
                debug.error("A wizard could not stop its progress bar.", broken);
            }
        }
    }

    /**
     * Tells everybody waiting.
     *
     * <p>The stage is completed inline and unconditionally, because a caller
     * waiting on it must be released even for a player who has already left.
     * The player-facing callbacks are the ones that hop and the ones that are
     * skipped for somebody offline.
     */
    private void deliver(WizardOutcome outcome, @Nullable WizardValues collected) {
        WizardResult result = collected != null
                ? WizardResult.completed(collected)
                : WizardResult.ended(outcome);
        try {
            future.complete(result);
        } catch (RuntimeException broken) {
            debug.error("A wizard result callback failed.", broken);
        }

        if (!player.isOnline() || (afterwards == null && wizard.onCancel() == null)) {
            return;
        }
        try {
            // A tick later, on the player's own thread: a menu reopened in the
            // same tick as the window that asked the last question closes is a
            // menu the client never sees.
            tasks.runAtEntityLater(player, 1L, () -> {
                Consumer<WizardOutcome> onCancel = wizard.onCancel();
                if (onCancel != null && outcome != WizardOutcome.COMPLETED) {
                    try {
                        onCancel.accept(outcome);
                    } catch (RuntimeException broken) {
                        debug.error("A wizard's cancel handler failed.", broken);
                    }
                }
                if (afterwards != null) {
                    try {
                        afterwards.run();
                    } catch (RuntimeException broken) {
                        debug.error("What was meant to happen after a wizard failed.", broken);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            // The plugin is being disabled and its scheduler is closed. The
            // player has been released, which is what mattered.
            debug.warn("A wizard ended while its plugin was shutting down;"
                    + " what was to follow was skipped.");
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Runs a callback on the player's thread, unless it belongs to a step that
     * is no longer current.
     *
     * <p>Both checks matter and neither replaces the other. The terminal check
     * catches a callback that arrives after the run ended; the generation check
     * catches one that arrives after its own step was redone, which is a live
     * run receiving an answer to a question it already re-asked.
     */
    private void onPlayerThread(long mine, Runnable work) {
        if (terminal.get() != null || generation.get() != mine) {
            return;
        }
        try {
            tasks.runAtEntity(player, () -> {
                if (terminal.get() != null || generation.get() != mine) {
                    return;
                }
                work.run();
            }, () -> end(WizardOutcome.DISCONNECTED));
        } catch (RuntimeException rejected) {
            end(WizardOutcome.SHUT_DOWN);
        }
    }

    private long nextGeneration() {
        return generation.updateAndGet(previous -> previous + 1L);
    }

    private void showProgress() {
        Display bar = display;
        if (bar == null) {
            return;
        }
        int total = Math.max(1, stepCount());
        // Substituted into the raw template before the display parses it: the
        // title carries the server's own colour tokens and is meant to be read
        // as formatting, and Display takes a string rather than a component.
        String text = settings.progressText()
                .replace("%step%", String.valueOf(Math.min(answered + 1, total)))
                .replace("%steps%", String.valueOf(total))
                .replace("%title%", wizard.title());
        bar.text(text).progress(Math.clamp((float) answered / total, 0f, 1f));
    }

    private String progressText() {
        return settings.progressText()
                .replace("%step%", "1")
                .replace("%steps%", String.valueOf(Math.max(1, wizard.stepCount())))
                .replace("%title%", wizard.title());
    }

    /** The most steps the queue could still produce, counting every branch as taken. */
    private static int upperBound(Iterable<WizardStep> steps) {
        int total = 0;
        for (WizardStep step : steps) {
            total += step instanceof WizardStep.Branch<?> branch
                    ? upperBound(branch.steps())
                    : 1;
        }
        return total;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException wrapper && wrapper.getCause() != null
                ? wrapper.getCause()
                : failure;
    }

    @Override
    public String toString() {
        return "WizardRun[" + wizard.id() + ", " + player.getName() + ", step " + answered + ']';
    }

    /** Kept for diagnostics: which plugin's flow this is. */
    @NotNull String pluginName() {
        return plugin.getName();
    }

    /** Every answer collected so far, for a test that wants to look. */
    @NotNull List<String> answeredNames() {
        synchronized (state) {
            return new ArrayList<>(asked.keySet());
        }
    }
}
