package net.exylia.lib.util.reward;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.internal.Conditions;
import net.exylia.lib.util.reward.internal.ItemGiver;
import net.exylia.lib.util.reward.internal.Providers;
import net.exylia.lib.util.reward.internal.Rolls;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One plugin's view of the reward module.
 *
 * <pre>{@code
 * private PluginRewards rewards;
 *
 * public void onEnable() {
 *     rewards = Rewards.of(this);
 *     rewards.pending(myPendingRewardTable);
 * }
 *
 * // when somebody wins
 * rewards.give(winner, event.rewards());
 * }</pre>
 *
 * <h2>Per plugin, not static</h2>
 * The scheduler that puts a delivery on the right thread, the console that hears
 * about a broken reward, and the table that keeps what an offline player is owed
 * all belong to the plugin that asked. A shared static would hand one plugin's
 * pending rewards to another and would keep delivering after a classloader was
 * gone.
 *
 * <h2>Threading</h2>
 * {@link #give} hands the rewards over on the calling thread, which must be the
 * one that owns the player. From anywhere else &mdash; a database callback, a
 * web request, any async path &mdash; call {@link #giveOnPlayerThread}, which
 * moves the work there itself. That is what makes it correct on Folia.
 *
 * <p>{@link #claim} arranges both halves on its own: the store is read off the
 * main thread and the delivery comes back onto the player's.
 *
 * @since 1.34.0
 */
public final class PluginRewards {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;

    private final AtomicLong given = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    /** Reported once per broken reward rather than once per delivery. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private volatile OverflowPolicy overflow = OverflowPolicy.DROP;
    private volatile PendingRewards pending;
    private volatile Rolls.Dice dice = Rolls.RANDOM;
    private volatile ItemGiver items = ItemGiver.BUKKIT;

    /**
     * Returns whether this view belongs to the given load of its plugin.
     *
     * <p>Identity, not {@code equals}: that one is final on {@code Plugin} and
     * compares names, which is what two loads of the same plugin share.
     */
    boolean ownedBy(@NotNull Plugin other) {
        return plugin == other;
    }

    PluginRewards(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    // --------------------------------------------------------------- settings

    /**
     * What happens to an item a player has no room for.
     *
     * <p>{@link OverflowPolicy#DROP} unless told otherwise.
     *
     * @param policy the policy
     * @return this
     */
    public @NotNull PluginRewards overflow(@NotNull OverflowPolicy policy) {
        this.overflow = policy;
        return this;
    }

    /**
     * Where rewards wait for a player who is not here.
     *
     * <p>Required by {@link #giveLater} and by {@link OverflowPolicy#QUEUE};
     * without it both report a failure rather than losing the reward quietly.
     *
     * @param store the plugin's own table
     * @return this
     */
    public @NotNull PluginRewards pending(@NotNull PendingRewards store) {
        this.pending = store;
        return this;
    }

    // ---------------------------------------------------------------- giving

    /**
     * Gives a player their rewards.
     *
     * <p>Each reward is checked, rolled and handed over on its own: one that
     * fails costs the player nothing else on the list.
     *
     * <p>Must be called on the thread that owns the player. From anywhere else,
     * use {@link #giveOnPlayerThread}.
     *
     * @param player  who gets them, on this thread
     * @param rewards what they get
     * @return what became of each
     */
    /**
     * A screen for editing a list of rewards.
     *
     * <pre>{@code
     * rewards.editor(zone.rewards())
     *        .title("{primary}&lPOWER-UP REWARDS")
     *        .onSave(edited -> manager.save(zone, edited))
     *        .onCancel(() -> setupMenu.open(player))
     *        .open(player);
     * }</pre>
     *
     * <p>Pagination, add, edit, delete, copy, paste, save and cancel, over the
     * one screen every list editor in the library shares. A reward copied here
     * pastes into any other reward editor, in this plugin or another.
     *
     * @param rewards what is being edited; copied, never held
     * @return the editor, ready to open
     * @since 1.56.0
     */
    public @NotNull ListEditor<RewardEntry> editor(@NotNull List<RewardEntry> rewards) {
        return Editors.of(plugin()).list(new RewardDescriptor(plugin()), RewardEntry.class, rewards);
    }

    public @NotNull RewardDelivery give(@NotNull Player player,
                                        @NotNull List<RewardEntry> rewards) {
        if (rewards.isEmpty()) {
            return RewardDelivery.EMPTY;
        }
        List<RewardResult> results = new ArrayList<>(rewards.size());
        for (RewardEntry entry : Rolls.ordered(rewards)) {
            results.add(deliver(entry, player));
        }
        return new RewardDelivery(results);
    }

    /**
     * Gives a player their rewards, wherever the caller happens to be.
     *
     * <p>The work is moved onto the thread that owns the player, so this is the
     * one to call from a database callback or any other async path.
     *
     * @param player  who gets them
     * @param rewards what they get
     * @param then    told what became of them, on the player's thread
     */
    public void giveOnPlayerThread(@NotNull Player player,
                                   @NotNull List<RewardEntry> rewards,
                                   @NotNull java.util.function.Consumer<RewardDelivery> then) {
        tasks.runAtEntity(player, () -> then.accept(give(player, rewards)));
    }

    /**
     * Gives one reward.
     *
     * @param player who gets it
     * @param reward what they get
     * @return what became of it
     */
    public @NotNull RewardResult give(@NotNull Player player, @NotNull RewardEntry reward) {
        return deliver(reward, player);
    }

    /**
     * Gives whichever of these the weights choose, and only that one.
     *
     * <p>A loot table: exactly one reward happens. Its own
     * {@link RewardEntry#chance()} still applies afterwards, so a rare winner can
     * still miss.
     *
     * @param player  who gets it
     * @param rewards the group to pick from
     * @return what became of the winner, or an empty delivery if there was none
     */
    public @NotNull RewardDelivery roll(@NotNull Player player,
                                        @NotNull List<RewardEntry> rewards) {
        RewardEntry winner = Rolls.pick(rewards, dice);
        if (winner == null) {
            return RewardDelivery.EMPTY;
        }
        return new RewardDelivery(List.of(deliver(winner, player)));
    }

    /**
     * Picks a winner without giving it.
     *
     * <p>For showing a player what they are about to win, or for a plugin that
     * wants to store the result and hand it over later.
     *
     * @param rewards the group to pick from
     * @return the winner, or {@code null} if every weight was zero
     */
    public @Nullable RewardEntry pick(@NotNull List<RewardEntry> rewards) {
        return Rolls.pick(rewards, dice);
    }

    /**
     * Keeps rewards for a player who is not here.
     *
     * <p>They are handed over by {@link #claim} the next time the player is
     * around. Needs a {@link #pending(PendingRewards) store}.
     *
     * @param player who is owed them
     * @param owed   what they are owed
     * @return whether they could be kept
     */
    public boolean giveLater(@NotNull UUID player, @NotNull List<RewardEntry> owed) {
        if (owed.isEmpty()) {
            return true;
        }
        PendingRewards store = pending;
        if (store == null) {
            debug.error("Rewards were owed to " + player
                    + " but this plugin has no pending-reward store; they are lost."
                    + " Call PluginRewards.pending(...) during onEnable.");
            return false;
        }
        try {
            store.keep(player, List.copyOf(owed));
            return true;
        } catch (RuntimeException unwritable) {
            debug.error("Could not keep " + owed.size() + " reward(s) owed to " + player + ".",
                    unwritable);
            return false;
        }
    }

    /**
     * Hands a player everything they were owed.
     *
     * <p>What a join listener calls. Reading the store is done off the main
     * thread and the delivery comes back onto the player's, so a plugin does not
     * arrange either.
     *
     * @param player who is claiming
     * @param then   told what became of them, on the player's thread; not called
     *               at all when nothing was owed, or when the store could not be
     *               read
     */
    public void claim(@NotNull Player player,
                      @NotNull java.util.function.Consumer<RewardDelivery> then) {
        PendingRewards store = pending;
        if (store == null) {
            then.accept(RewardDelivery.EMPTY);
            return;
        }
        UUID id = player.getUniqueId();
        tasks.runAsync(() -> {
            List<RewardEntry> owed;
            try {
                owed = store.claim(id);
            } catch (RuntimeException unreadable) {
                debug.error("Could not read the rewards owed to " + id + ".", unreadable);
                return;
            }
            if (owed.isEmpty()) {
                return;
            }
            tasks.runAtEntity(player, () -> then.accept(give(player, owed)));
        });
    }

    // ------------------------------------------------------------------ inside

    private RewardResult deliver(RewardEntry entry, Player player) {
        // Before the roll, unlike commons, which rolled first. Who may receive a
        // reward does not depend on chance, and asking in that order made a rare
        // reward report a lost roll when the real answer was a misspelled
        // permission or a broken condition — the two things a server owner has
        // to be told about, hidden behind the one thing they expect.
        String permission = entry.permission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            return RewardResult.skipped(entry, RewardOutcome.NO_PERMISSION);
        }
        String condition = entry.condition();
        if (condition != null && !condition.isBlank()
                && !Conditions.holds(condition, player,
                        (subject, problem) -> report(entry, subject, problem))) {
            return RewardResult.skipped(entry, RewardOutcome.CONDITION_FAILED);
        }
        if (!Rolls.rolled(entry, dice)) {
            return RewardResult.skipped(entry, RewardOutcome.NOT_ROLLED);
        }

        int amount = Rolls.amount(entry, dice);
        RewardResult result = Providers.give(entry, player, amount, this::leftOver, items, tasks);
        record(entry, result, player);
        return result;
    }

    private RewardOutcome leftOver(Player player, RewardEntry entry, int remaining) {
        String snapshot = entry.itemSnapshot();
        return switch (overflow) {
            case DROP -> {
                items.drop(player, snapshot, remaining);
                yield RewardOutcome.GIVEN;
            }
            case QUEUE -> {
                // What is left over, not the original: a player who received
                // four of six is owed two, and owed them without rolling again,
                // because the roll that earned them already happened. Their
                // permission and condition are kept: a reward owed to somebody
                // who has since lost the rank it required is not owed any more.
                RewardEntry rest = entry.toBuilder()
                        .fixedAmount(remaining)
                        .chance(RewardEntry.ALWAYS)
                        .build();
                if (giveLater(player.getUniqueId(), List.of(rest))) {
                    yield RewardOutcome.QUEUED;
                }
                // The store would not take it. Dropping is the one thing left
                // that is not destroying it, which is the whole point of this
                // enum: a plugin asking to queue asked not to lose the item, and
                // an unreachable database does not change what they asked for.
                items.drop(player, snapshot, remaining);
                debug.warn("Reward \"" + entry.displayName() + "\" could not be kept for "
                        + player.getName() + "; it was dropped at their feet instead.");
                yield RewardOutcome.GIVEN;
            }
            case FAIL -> RewardOutcome.NO_ROOM;
        };
    }

    private void record(RewardEntry entry, RewardResult result, Player player) {
        if (result.isGiven()) {
            given.incrementAndGet();
            String message = entry.deliveryMessage();
            if (message != null && !message.isBlank()) {
                Text.of(message).forPlayer(player).send(player);
            }
            return;
        }
        if (result.outcome().isFailure()) {
            failed.incrementAndGet();
            report(entry, result.outcome().name(),
                    result.detail() != null ? result.detail() : "could not be given");
            if (result.failure() != null) {
                debug.error("A reward failed for " + player.getName() + ".", result.failure());
            }
        }
    }

    /**
     * Says something about a reward once and then stops.
     *
     * <p>A broken reward on a busy event would otherwise print a line per player
     * per win, which is how a real problem becomes invisible.
     *
     * <p>What is remembered and what is printed are deliberately different. A
     * failure detail often carries the player's own resolved values &mdash;
     * {@code eco give Steve 500} &mdash; which is exactly what an operator needs
     * to see and exactly what must not go in the key: keyed on that, the same
     * broken reward reports once per player and the set grows without bound.
     *
     * @param entry   the reward
     * @param subject what makes this problem the same problem, across players
     * @param problem what to print
     */
    private void report(RewardEntry entry, String subject, String problem) {
        if (reported.add(entry.id() + "|" + subject)) {
            debug.warn("Reward \"" + entry.displayName() + "\" " + problem);
        }
    }

    // ------------------------------------------------------------- statistics

    /** How many rewards this plugin has handed over since it started. */
    public long givenCount() {
        return given.get();
    }

    /**
     * How many went wrong.
     *
     * <p>Rewards that lost a roll or failed a condition are not counted: they are
     * outcomes, not faults, and counting them made commons' success rate describe
     * the dice rather than the configuration.
     */
    public long failedCount() {
        return failed.get();
    }

    /** Forgets what has already been reported, so a reload complains afresh. */
    public void forgetProblems() {
        reported.clear();
    }

    /** Replaces the dice. Tests only. */
    void dice(@NotNull Rolls.Dice replacement) {
        this.dice = replacement;
    }

    /** Replaces how an item reaches a player. Tests only. */
    void items(@NotNull ItemGiver replacement) {
        this.items = replacement;
    }

    @Override
    public String toString() {
        return "PluginRewards[" + plugin.getName() + ", " + given.get() + " given]";
    }
}
