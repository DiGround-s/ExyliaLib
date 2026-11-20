package net.exylia.lib.shop.restock;

import net.exylia.lib.util.Weighted;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The draw of one window: pure, so every server computes the same stock.
 *
 * @since 1.632.0
 */
public final class Rotation {

    private Rotation() {
    }

    /** The window a moment falls in. */
    public static long window(long epochMillis, long periodMillis) {
        return Math.floorDiv(epochMillis, periodMillis);
    }

    /**
     * Draws {@code slots} offers by weight without replacement. The same
     * arguments give the same list on any machine, whatever the pool's order.
     */
    public static @NotNull List<Offer> draw(@NotNull String shop, long window, int slots, @NotNull List<Offer> pool) {
        List<Offer> left = new ArrayList<>(pool.stream().filter(o -> o.weight() > 0).toList());
        left.sort(Comparator.comparing(Offer::id));
        SplittableRandom random = new SplittableRandom(shop.hashCode() * 0x9E3779B97F4A7C15L ^ window);
        List<Offer> out = new ArrayList<>();
        while (out.size() < slots && !left.isEmpty()) {
            Offer picked = Weighted.pick(left, Offer::weight, random);
            left.remove(picked);
            out.add(picked);
        }
        return List.copyOf(out);
    }
}
