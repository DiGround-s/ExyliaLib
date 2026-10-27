package net.exylia.lib.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.random.RandomGenerator;

/**
 * A {@link Weighted} roll that remembers the player: it avoids what they got
 * recently and guarantees a good result after enough bad ones.
 *
 * <pre>{@code
 * FairRoll<Kit> roll = FairRoll.of(kits, Kit::weight, Kit::id, Kit::rank)
 *         .avoidRecent(3)
 *         .rules(List.of(new FairRoll.Rule("epic", 3, 10)));   // 10 rolls below rank 3 force one
 *
 * FairRoll.Result<Kit> result = roll.roll(profile.luck());
 * profile = profile.withLuck(result.luck());                   // store it with the player
 * }</pre>
 *
 * <p>Two memories, both in {@link Luck}, which the caller stores per player:
 * <ul>
 *   <li><b>Anti-repeat.</b> The keys of the last {@code avoidRecent} results are
 *   left out of the draw. When that would leave nothing, they are put back:
 *   a small pool still rolls.</li>
 *   <li><b>Pity.</b> Each {@link Rule} counts the rolls in a row whose tier was
 *   below its own. Once the count reaches {@code after}, the next roll draws only
 *   among entries of that tier or higher, and the count starts again. With
 *   several rules due, the highest tier wins. A rule nothing in the list can
 *   satisfy is ignored rather than rolling nothing.</li>
 * </ul>
 *
 * <p>Tiers are plain numbers where higher is rarer, such as a rarity's position
 * in its file. Weights follow {@link Weighted}: zero or less is never rolled,
 * all zero picks evenly. Immutable and thread-safe; a roll changes nothing, the
 * new {@link Luck} is in the result.
 *
 * @param <T> what is rolled
 * @since 1.379.0
 */
public final class FairRoll<T> {

    /**
     * A guarantee: after {@code after} rolls in a row below {@code tier}, the
     * next one is at least {@code tier}.
     *
     * @param name  the key its counter is stored under in {@link Luck#misses()},
     *              so reordering rules keeps every player's count
     * @param tier  the lowest tier that resets it
     * @param after how many rolls below it are allowed in a row; {@code 0} or less turns it off
     */
    public record Rule(@NotNull String name, int tier, int after) {

        public Rule {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * What one player's rolls remember.
     *
     * @param recent the keys of their latest results, oldest first
     * @param misses rolls in a row below each rule's tier, by rule name
     */
    public record Luck(@NotNull List<String> recent, @NotNull Map<String, Integer> misses) {

        /** A player who never rolled. */
        public static final Luck NONE = new Luck(List.of(), Map.of());

        public Luck {
            recent = recent == null ? List.of() : List.copyOf(recent);
            misses = misses == null ? Map.of() : Map.copyOf(misses);
        }

        /** The rolls in a row below the named rule's tier. */
        public int misses(@NotNull String rule) {
            return misses.getOrDefault(rule, 0);
        }
    }

    /**
     * One roll.
     *
     * @param picked what was rolled, {@code null} only for an empty list
     * @param forced the rule that decided the draw, or {@code null} for a plain roll
     * @param luck   the player's luck after it, to store
     */
    public record Result<T>(@Nullable T picked, @Nullable Rule forced, @NotNull Luck luck) {
    }

    private final List<T> entries;
    private final ToDoubleFunction<? super T> weight;
    private final Function<? super T, String> key;
    private final ToIntFunction<? super T> tier;
    private final int avoidRecent;
    private final List<Rule> rules;

    private FairRoll(List<T> entries, ToDoubleFunction<? super T> weight, Function<? super T, String> key,
                     ToIntFunction<? super T> tier, int avoidRecent, List<Rule> rules) {
        this.entries = entries;
        this.weight = weight;
        this.key = key;
        this.tier = tier;
        this.avoidRecent = Math.max(0, avoidRecent);
        this.rules = rules;
    }

    /**
     * A roll over these entries, with no anti-repeat and no rules yet.
     *
     * @param entries what to choose from; copied
     * @param weight  how heavy each entry is
     * @param key     what identifies an entry in {@link Luck#recent()}
     * @param tier    how rare each entry is, higher is rarer
     */
    public static <T> @NotNull FairRoll<T> of(@NotNull Collection<? extends T> entries,
                                              @NotNull ToDoubleFunction<? super T> weight,
                                              @NotNull Function<? super T, String> key,
                                              @NotNull ToIntFunction<? super T> tier) {
        return new FairRoll<>(List.copyOf(entries), weight, key, tier, 0, List.of());
    }

    /** The same roll, leaving out the keys of the last {@code count} results. */
    public @NotNull FairRoll<T> avoidRecent(int count) {
        return new FairRoll<>(entries, weight, key, tier, count, rules);
    }

    /** The same roll, with these guarantees. */
    public @NotNull FairRoll<T> rules(@NotNull List<Rule> rules) {
        return new FairRoll<>(entries, weight, key, tier, avoidRecent, List.copyOf(rules));
    }

    /** Rolls one entry. */
    public @NotNull Result<T> roll(@NotNull Luck luck) {
        return roll(luck, ThreadLocalRandom.current());
    }

    /** Rolls one entry with a given random source, for tests and seeded rolls. */
    public @NotNull Result<T> roll(@NotNull Luck luck, @NotNull RandomGenerator random) {
        Rule forced = due(luck);
        T picked = draw(luck, forced, Set.of(), random);
        if (picked == null && forced != null) {
            picked = draw(luck, null, Set.of(), random);
            forced = null;
        }
        return new Result<>(picked, forced, picked == null ? luck : record(luck, picked));
    }

    /**
     * Rolls up to {@code count} different entries to choose from, as a draft
     * offers them. The first is rolled exactly as {@link #roll} rolls, pity
     * included, so a guarantee that is due is always among them; the rest are
     * plain rolls. Nothing is recorded: store {@link #record} of the one chosen.
     */
    public @NotNull List<T> rollDistinct(@NotNull Luck luck, int count, @NotNull RandomGenerator random) {
        List<T> offered = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        Result<T> first = roll(luck, random);
        if (first.picked() == null || count <= 0) return offered;
        offered.add(first.picked());
        taken.add(key.apply(first.picked()));
        while (offered.size() < count) {
            T next = draw(luck, null, taken, random);
            if (next == null) break;
            offered.add(next);
            taken.add(key.apply(next));
        }
        return offered;
    }

    /** The player's luck once they received {@code entry}. */
    public @NotNull Luck record(@NotNull Luck luck, @NotNull T entry) {
        List<String> recent = new ArrayList<>(luck.recent());
        recent.add(key.apply(entry));
        int keep = Math.max(avoidRecent, 0);
        List<String> kept = recent.subList(Math.max(0, recent.size() - keep), recent.size());
        int rank = tier.applyAsInt(entry);
        Map<String, Integer> misses = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.after() <= 0) continue;
            misses.put(rule.name(), rank >= rule.tier() ? 0 : luck.misses(rule.name()) + 1);
        }
        return new Luck(kept, misses);
    }

    /** The highest-tier rule whose count has run out, or {@code null}. */
    private @Nullable Rule due(Luck luck) {
        Rule due = null;
        for (Rule rule : rules) {
            if (rule.after() <= 0 || luck.misses(rule.name()) < rule.after()) continue;
            if (due == null || rule.tier() > due.tier()) due = rule;
        }
        return due;
    }

    /**
     * One weighted pick among the entries not excluded, the recent ones left out
     * while that leaves anything, and at least the forced tier when one is given.
     */
    private @Nullable T draw(Luck luck, @Nullable Rule forced, Set<String> excluded, RandomGenerator random) {
        Set<String> recent = new HashSet<>(luck.recent().subList(
                Math.max(0, luck.recent().size() - avoidRecent), luck.recent().size()));
        List<T> fresh = new ArrayList<>();
        List<T> any = new ArrayList<>();
        for (T entry : entries) {
            String id = key.apply(entry);
            if (excluded.contains(id)) continue;
            if (forced != null && tier.applyAsInt(entry) < forced.tier()) continue;
            any.add(entry);
            if (!recent.contains(id)) fresh.add(entry);
        }
        return Weighted.pick(fresh.isEmpty() ? any : fresh, weight, random);
    }
}
