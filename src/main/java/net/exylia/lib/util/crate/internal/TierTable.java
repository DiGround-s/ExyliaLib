package net.exylia.lib.util.crate.internal;

import net.exylia.lib.util.crate.CrateTier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The rarities, as the settings declare them.
 *
 * <p>A thin ordered view over the configured map, built on every read rather
 * than cached: the map is a handful of entries, it is only walked when a menu is
 * drawn or a crate is opened, and a cache would be one more thing a reload could
 * leave stale.
 */
public final class TierTable {

    private final Map<String, CrateTier> byId;
    private final List<String> ordered;

    public TierTable(@NotNull Map<String, CrateTier> configured) {
        Map<String, CrateTier> normalised = new LinkedHashMap<>();
        configured.forEach((id, tier) -> normalised.put(normalise(id), tier));

        List<String> ids = new ArrayList<>(normalised.keySet());
        ids.sort(Comparator.comparingInt((String id) -> normalised.get(id).priority()).thenComparing(id -> id));

        this.byId = Map.copyOf(normalised);
        this.ordered = List.copyOf(ids);
    }

    /** How every id is compared: trimmed and lower-cased. */
    public static @NotNull String normalise(@NotNull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    /** Every rarity id, lowest priority first. */
    public @NotNull List<String> ids() {
        return ordered;
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /**
     * The id a reward's rarity actually names.
     *
     * <p>One that no longer exists resolves to the first rarity, which is the
     * cheapest one on a sane file: a reward whose rarity was deleted is worth
     * less than it was, never more.
     */
    public @NotNull String resolveId(@Nullable String id) {
        if (id != null) {
            String normalised = normalise(id);
            if (byId.containsKey(normalised)) return normalised;
        }
        return ordered.isEmpty() ? "" : ordered.get(0);
    }

    /** The rarity an id names, resolved; a blank stand-in when none are declared. */
    public @NotNull CrateTier get(@Nullable String id) {
        CrateTier tier = byId.get(resolveId(id));
        return tier != null ? tier : new CrateTier("", "", 0, 0);
    }

    /**
     * How likely a crate is to land on a rarity, as a share of one.
     *
     * @param among which rarities are actually in the pool
     */
    public double shareOf(@Nullable String id, @NotNull Predicate<String> among) {
        double total = 0;
        for (String candidate : ordered) {
            if (among.test(candidate)) total += Math.max(0, byId.get(candidate).chance());
        }
        if (total <= 0) return 0;
        String resolved = resolveId(id);
        if (!among.test(resolved)) return 0;
        return Math.max(0, byId.get(resolved).chance()) / total;
    }

    /**
     * Picks a rarity by weight.
     *
     * @param roll  a number from zero up to but not including one
     * @param among which rarities are in the pool: those with something in them
     * @return the rarity's id, or {@code null} when the pool is empty or every
     *         weight in it is zero
     */
    public @Nullable String roll(double roll, @NotNull Predicate<String> among) {
        double total = 0;
        for (String id : ordered) {
            if (among.test(id)) total += Math.max(0, byId.get(id).chance());
        }
        if (total <= 0) return null;

        double target = Math.min(Math.max(roll, 0), 0.999999) * total;
        double walked = 0;
        String last = null;
        for (String id : ordered) {
            if (!among.test(id)) continue;
            double weight = Math.max(0, byId.get(id).chance());
            if (weight <= 0) continue;
            last = id;
            walked += weight;
            if (target < walked) return id;
        }
        // Only reachable on a rounding edge at the very top of the range.
        return last;
    }
}
