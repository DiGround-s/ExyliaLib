package net.exylia.lib.progression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * An onboarding funnel for an admin screen: how many players reached each step, how many came back some time after
 * joining (out of those who joined long enough ago), and where those gone for a while stopped. Built from the
 * players a plugin already holds; it stores nothing.
 *
 * <pre>{@code
 * Funnel funnel = Funnel.<Profile>builder(p -> p.counter(JOINED), p -> p.counter(SEEN))
 *         .step("sold", p -> p.counter(FIRST_SALE) > 0)
 *         .returned("day", Funnel.DAY, p -> p.counter(BACK_DAY) > 0)
 *         .left(7 * Funnel.DAY, p -> furthestZone(p))
 *         .build(profiles, System.currentTimeMillis());
 * }</pre>
 *
 * @param players everyone counted
 * @param steps   step id to players who reached it, in the order added
 * @param returns return id to who was asked and who came back, in the order added
 * @param leftAt  where players gone for the {@code left} time stopped, most first
 */
public record Funnel(int players, Map<String, Integer> steps, Map<String, Return> returns,
                     Map<String, Integer> leftAt) {

    public static final long DAY = 86_400_000L;

    /** Players who joined at least the return's time ago, and how many of them came back. */
    public record Return(int asked, int back) {

        /** Who came back as a whole percent, -1 with nobody asked. */
        public int percent() {
            return Funnel.percent(back, asked);
        }
    }

    public int step(String id) {
        return steps.getOrDefault(id, 0);
    }

    public Return returned(String id) {
        return returns.getOrDefault(id, new Return(0, 0));
    }

    /** {@code part} of {@code whole} as a whole percent, or -1 when there is nobody to count. */
    public static int percent(int part, int whole) {
        return whole == 0 ? -1 : Math.round(100f * part / whole);
    }

    /**
     * @param joined when a player joined, epoch millis; 0 or less for unknown
     * @param seen   when a player was last seen, epoch millis; the join time counts when it is later
     */
    public static <T> Builder<T> builder(ToLongFunction<T> joined, ToLongFunction<T> seen) {
        return new Builder<>(joined, seen);
    }

    public static final class Builder<T> {

        private final ToLongFunction<T> joined;
        private final ToLongFunction<T> seen;
        private final Map<String, Predicate<T>> steps = new LinkedHashMap<>();
        private final Map<String, Long> returnAfter = new LinkedHashMap<>();
        private final Map<String, Predicate<T>> returnBack = new LinkedHashMap<>();
        private long gone = -1;
        private Function<T, String> where = player -> "";

        private Builder(ToLongFunction<T> joined, ToLongFunction<T> seen) {
            this.joined = joined;
            this.seen = seen;
        }

        /** A step a player reached when {@code reached} says so. */
        public Builder<T> step(String id, Predicate<T> reached) {
            steps.put(id, reached);
            return this;
        }

        /** Asks those who joined at least {@code afterMillis} ago whether they came back. */
        public Builder<T> returned(String id, long afterMillis, Predicate<T> back) {
            returnAfter.put(id, afterMillis);
            returnBack.put(id, back);
            return this;
        }

        /** Counts players unseen for {@code goneMillis} by where they stopped. */
        public Builder<T> left(long goneMillis, Function<T, String> where) {
            this.gone = goneMillis;
            this.where = where;
            return this;
        }

        public Funnel build(Collection<T> players, long now) {
            Map<String, Integer> reached = new LinkedHashMap<>();
            steps.keySet().forEach(id -> reached.put(id, 0));
            Map<String, int[]> asked = new LinkedHashMap<>();
            returnAfter.keySet().forEach(id -> asked.put(id, new int[2]));
            Map<String, Integer> left = new LinkedHashMap<>();
            for (T player : players) {
                steps.forEach((id, test) -> {
                    if (test.test(player)) reached.merge(id, 1, Integer::sum);
                });
                long join = joined.applyAsLong(player);
                returnAfter.forEach((id, after) -> {
                    if (join <= 0 || now - join < after) return;
                    int[] counts = asked.get(id);
                    counts[0]++;
                    if (returnBack.get(id).test(player)) counts[1]++;
                });
                long last = Math.max(seen.applyAsLong(player), join);
                if (gone >= 0 && last > 0 && now - last >= gone) left.merge(where.apply(player), 1, Integer::sum);
            }
            Map<String, Return> returns = new LinkedHashMap<>();
            asked.forEach((id, counts) -> returns.put(id, new Return(counts[0], counts[1])));
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(left.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            Map<String, Integer> leftAt = new LinkedHashMap<>();
            sorted.forEach(entry -> leftAt.put(entry.getKey(), entry.getValue()));
            return new Funnel(players.size(), reached, returns, leftAt);
        }
    }
}
