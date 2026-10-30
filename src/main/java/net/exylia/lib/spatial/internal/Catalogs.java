package net.exylia.lib.spatial.internal;

import net.exylia.lib.cosmetic.Price;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The arithmetic of a cosmetic catalogue, kept apart from Bukkit so it can be tested: a basket's total,
 * the order a list is shown in, and the filters a carousel steps through.
 *
 * <p>Internal: free to change without notice.
 */
public final class Catalogs {

    private Catalogs() {
    }

    /** What a basket costs in one price, or {@code null} when it is empty or mixes currencies. */
    public static @Nullable Price total(List<Price> prices) {
        if (prices.isEmpty()) return null;
        String currency = prices.get(0).currency();
        BigDecimal sum = BigDecimal.ZERO;
        for (Price price : prices) {
            if (!Objects.equals(price.currency(), currency)) return null;
            sum = sum.add(price.amount());
        }
        return new Price(sum, currency);
    }

    /** {@code items} with the ones {@code first} accepts moved to the front, each half in its own order. */
    public static <T> List<T> firstWhere(List<T> items, Predicate<T> first) {
        List<T> front = new ArrayList<>(items.size());
        List<T> back = new ArrayList<>();
        for (T item : items) (first.test(item) ? front : back).add(item);
        front.addAll(back);
        return front;
    }

    /**
     * The filter after {@code current}: every entry first when {@code all} is set (as {@code allId}),
     * then each category, then the favourites when {@code favouritesId} is not {@code null}. A filter
     * that is gone starts the cycle again.
     */
    public static String nextFilter(String current, List<String> categories, boolean all, String allId,
                                    @Nullable String favouritesId) {
        List<String> cycle = filters(categories, all, allId, favouritesId);
        if (cycle.isEmpty()) return allId;
        int at = cycle.indexOf(current);
        return cycle.get((at + 1) % cycle.size());
    }

    /** Every filter the cycle holds, in order. */
    public static List<String> filters(List<String> categories, boolean all, String allId, @Nullable String favouritesId) {
        List<String> cycle = new ArrayList<>();
        if (all) cycle.add(allId);
        cycle.addAll(categories);
        if (favouritesId != null) cycle.add(favouritesId);
        return cycle;
    }

    /** The page an entry at {@code index} is on, {@code perPage} to a page; 0 for one not shown. */
    public static int pageOf(int index, int perPage) {
        return index < 0 ? 0 : index / Math.max(1, perPage);
    }
}
