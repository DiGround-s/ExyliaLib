package net.exylia.lib.util.crate.internal;

import net.exylia.lib.util.crate.CrateCatalogue;
import net.exylia.lib.util.crate.CrateReward;
import net.exylia.lib.util.crate.CrateSettings;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One key in, one reward out.
 *
 * <p>Two rolls rather than one: a rarity first, by the weights the owner wrote,
 * over the rarities that have anything in them, and then a reward inside it
 * with every one equally likely. That is what keeps the odds honest — a
 * legendary at three percent stays at three percent whether the catalogue has
 * two legendaries or forty.
 *
 * <p>An opening is rolled first, on the player's thread, where nothing is spent
 * ({@link #rollAll}, {@link #ticket}); then written, off it, in one write that
 * spends the keys, unlocks what is new and refunds the duplicates
 * ({@link #open}). Only an opening the database took is shown, and the reel
 * reveals what was already written. Token items are the one thing handed over
 * afterwards, each claimed in the database before it is given
 * ({@link #claim}), so a token reaches the player once even with two servers
 * and a crash in between.
 *
 * @param <T> the plugin's reward type
 */
public final class Prizes<T> {

    /** Writes of one opening tried before it is given up on. */
    private static final int COMMIT_TRIES = 3;

    /** How one reel ended. */
    public enum Status {
        /** Something new. */
        WON,
        /** Something they already owned, so keys came back instead. */
        DUPLICATE
    }

    /**
     * What one reel came to.
     *
     * @param status   how it ended
     * @param prize    what came out
     * @param tierId   the rarity it came from
     * @param refunded keys handed back for a duplicate
     * @param token    whether a token item is owed for it, to be {@link #claim}ed
     */
    public record Outcome<T>(@NotNull Status status, @NotNull T prize, @NotNull String tierId, int refunded,
                             boolean token) {
    }

    /** How an opening's write ended. */
    public enum Result {
        /** Written: keys spent, prizes settled. */
        OPENED,
        /** Not enough keys on the account by the time it was written. Nothing was spent. */
        NO_KEYS,
        /** Given up on after the database did not answer. Nothing was spent, and never will be. */
        ABORTED
    }

    /**
     * @param result   how it ended
     * @param outcomes one per reel, in order, for {@link Result#OPENED}
     */
    public record Opened<T>(@NotNull Result result, @NotNull List<Outcome<T>> outcomes) {
    }

    /**
     * An opening rolled and not written yet.
     *
     * @param id     the operation's id: every retry writes under it, so it lands once
     * @param spend  keys it costs from the account; {@code 0} when key items paid for it
     * @param prizes what each reel landed on
     * @param tokens each reel's token item, or {@code null} for none
     */
    public record Ticket<T>(@NotNull String id, int spend, @NotNull List<T> prizes,
                            @NotNull List<@Nullable Token> tokens) {
    }

    /** A prize's token item, ready to be handed over. */
    public interface Token {

        /** Hands it to a player who is here. */
        void give(@NotNull Player player);

        /**
         * Keeps it for a player who is leaving, until their next join.
         *
         * @return whether it could be kept
         */
        boolean keep(@NotNull UUID player);

        /**
         * The token as the pending rewards store it ({@code RewardCodec}), written with
         * the opening so it survives a crash before it is handed over.
         */
        @NotNull String payload();
    }

    private final CrateCatalogue<T> catalogue;
    private final CrateStore store;
    private final Supplier<CrateSettings> settings;
    private final BiFunction<T, Player, @Nullable Token> tokens;

    /**
     * @param tokens a prize's token for a player, or {@code null} when the
     *               plugin has none
     */
    public Prizes(@NotNull CrateCatalogue<T> catalogue, @NotNull CrateStore store,
                  @NotNull Supplier<CrateSettings> settings,
                  @NotNull BiFunction<T, Player, @Nullable Token> tokens) {
        this.catalogue = catalogue;
        this.store = store;
        this.settings = settings;
        this.tokens = tokens;
    }

    public @NotNull CrateCatalogue<T> catalogue() {
        return catalogue;
    }

    public @NotNull TierTable tiers() {
        return new TierTable(settings.get().tiers());
    }

    public boolean enabled() {
        return settings.get().enabled();
    }

    /** The rarity a reward resolves to. */
    public @NotNull String tierOf(@NotNull T reward) {
        return tiers().resolveId(catalogue.tier(reward));
    }

    /** A reward's id, as it is stored. */
    public @NotNull String idOf(@NotNull T reward) {
        return TierTable.normalise(catalogue.id(reward));
    }

    /** The catalogue's reward under a stored id, or {@code null} when it is gone. */
    public @Nullable T byId(@NotNull String id) {
        for (T reward : catalogue.all()) {
            if (idOf(reward).equals(id)) return reward;
        }
        return null;
    }

    /**
     * Rolls one prize per key.
     *
     * @return the prizes, or {@code null} when the crate has nothing to land on:
     *         a crate with nothing in it must not charge for the disappointment
     */
    public @Nullable List<T> rollAll(int amount) {
        List<T> rolled = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            T prize = roll();
            if (prize == null) return null;
            rolled.add(prize);
        }
        return rolled;
    }

    /**
     * An opening of these prizes, with a fresh id and each reel's token made for
     * the player. On the player's thread: a token is an item drawn for them.
     */
    public @NotNull Ticket<T> ticket(@NotNull Player player, int spend, @NotNull List<T> prizes) {
        boolean items = settings.get().reward().givesItem();
        List<Token> made = new ArrayList<>(prizes.size());
        for (T prize : prizes) made.add(items ? tokens.apply(prize, player) : null);
        return new Ticket<>(UUID.randomUUID().toString(), Math.max(0, spend), List.copyOf(prizes),
                Collections.unmodifiableList(made));
    }

    /** A prize's token for a player, whatever the reward mode says now: what a recovered opening still owes. */
    public @Nullable Token tokenFor(@NotNull T prize, @NotNull Player player) {
        return tokens.apply(prize, player);
    }

    /**
     * Writes an opening, once.
     *
     * <p>A write the database does not answer is tried again under the same id,
     * which cannot land twice. When it still does not answer, {@code late} runs
     * and the opening is given up on ({@link CrateStore#abort}), tried until the
     * database answers: then it is either written for good (it had landed after
     * all, and that is what this answers) or aborted for good. Never both, and
     * never neither once this completes.
     *
     * @param late run once, off the player's thread, when the answer is going to be late
     * @return completes exceptionally only when the plugin stopped before the database answered
     */
    public @NotNull CompletableFuture<Opened<T>> open(@NotNull UUID uuid, @NotNull Ticket<T> ticket,
                                                     @NotNull Runnable late) {
        return attempt(uuid, ticket, late, 0);
    }

    private CompletableFuture<Opened<T>> attempt(UUID uuid, Ticket<T> ticket, Runnable late, int tries) {
        return commit(uuid, ticket).exceptionallyCompose(failure -> {
            if (tries + 1 < COMMIT_TRIES) {
                return store.after(10L << tries).thenCompose(ignored -> attempt(uuid, ticket, late, tries + 1));
            }
            late.run();
            return resolve(uuid, ticket, 1);
        });
    }

    private CompletableFuture<Opened<T>> resolve(UUID uuid, Ticket<T> ticket, int tries) {
        return store.abort(uuid, ticket.id()).thenApply(commit -> opened(ticket, commit))
                .exceptionallyCompose(failure -> store.after(Math.min(600L, 40L * tries))
                        .thenCompose(ignored -> resolve(uuid, ticket, tries + 1)));
    }

    /** The write alone, once: what {@link #open} retries. */
    public @NotNull CompletableFuture<Opened<T>> commit(@NotNull UUID uuid, @NotNull Ticket<T> ticket) {
        List<String> payloads = new ArrayList<>(ticket.tokens().size());
        for (Token token : ticket.tokens()) payloads.add(token == null ? null : token.payload());
        return store.commit(uuid, ticket.id(), ticket.spend(), payloads,
                        (paid, elsewhere) -> settle(ticket, paid, elsewhere))
                .thenApply(commit -> opened(ticket, commit));
    }

    /**
     * What the opening does to the row its keys were taken off, reel by reel, so
     * two reels of the same reward in one opening are a win and a duplicate.
     */
    private CrateStore.Settled settle(Ticket<T> ticket, CrateRow paid, Predicate<String> elsewhere) {
        CrateSettings crate = settings.get();
        CrateReward reward = crate.reward();
        int refund = Math.max(0, crate.duplicateRefund());
        CrateRow row = paid;
        List<CrateJournal.Reel> reels = new ArrayList<>(ticket.prizes().size());
        for (int i = 0; i < ticket.prizes().size(); i++) {
            String id = idOf(ticket.prizes().get(i));
            boolean token = ticket.tokens().get(i) != null;
            // A plugin with no token for it cannot hand the item over, so the prize
            // is unlocked instead: a key must never buy nothing.
            boolean unlocks = reward.unlocks() || !token;
            // Nothing is unlocked under ITEM, so every opening is a win: the prize
            // is the token itself, and a player may pull the same one forever.
            boolean isNew = !unlocks || !(row.isUnlocked(id) || elsewhere.test(id));
            if (isNew) {
                if (unlocks) row = row.withUnlocked(id);
                reels.add(new CrateJournal.Reel(true, id, 0, token));
            } else {
                row = row.withKeys(row.keys() + refund);
                reels.add(new CrateJournal.Reel(false, id, refund, false));
            }
        }
        return new CrateStore.Settled(row, reels);
    }

    private Opened<T> opened(Ticket<T> ticket, CrateStore.Commit commit) {
        return switch (commit.result()) {
            case NO_KEYS -> new Opened<>(Result.NO_KEYS, List.of());
            case ABORTED -> new Opened<>(Result.ABORTED, List.of());
            case APPLIED -> {
                List<CrateJournal.Reel> reels = commit.entry() == null ? List.of() : commit.entry().reels();
                List<Outcome<T>> outcomes = new ArrayList<>(reels.size());
                for (int i = 0; i < reels.size() && i < ticket.prizes().size(); i++) {
                    outcomes.add(outcome(ticket.prizes().get(i), reels.get(i)));
                }
                yield new Opened<>(Result.OPENED, outcomes);
            }
        };
    }

    /** A reel as the journal wrote it, about a prize of the catalogue. */
    public @NotNull Outcome<T> outcome(@NotNull T prize, @NotNull CrateJournal.Reel reel) {
        return new Outcome<>(reel.won() ? Status.WON : Status.DUPLICATE, prize, tierOf(prize), reel.refunded(),
                reel.tokenOwed());
    }

    /**
     * Takes one reel's token off the pending rewards the opening wrote it to, so
     * the caller who gets {@code true} is the one who hands it over.
     */
    public @NotNull CompletableFuture<Boolean> takeDelivery(@NotNull String operation, int index) {
        return store.takeDelivery(operation, index);
    }

    /**
     * Picks a rarity by weight among those with something in them, and then a
     * reward inside it.
     *
     * @return the prize, or {@code null} when there is nothing to land on
     */
    public @Nullable T roll() {
        Map<String, List<T>> pools = pools();
        String tier = tiers().roll(ThreadLocalRandom.current().nextDouble(), pools::containsKey);
        if (tier == null) return null;
        List<T> pool = pools.get(tier);
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Every reward, by the rarity it resolves to; a rarity with nothing in it is absent. */
    public @NotNull Map<String, List<T>> pools() {
        TierTable tiers = tiers();
        Map<String, List<T>> pools = new HashMap<>();
        if (tiers.isEmpty()) return pools;
        for (T reward : catalogue.all()) {
            pools.computeIfAbsent(tiers.resolveId(catalogue.tier(reward)), ignored -> new ArrayList<>()).add(reward);
        }
        return pools;
    }
}
