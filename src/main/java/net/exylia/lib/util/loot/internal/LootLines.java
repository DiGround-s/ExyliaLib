package net.exylia.lib.util.loot.internal;

import net.exylia.lib.util.loot.LootEntry;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * The compact grammar a loot table is written in inside a config file.
 *
 * <pre>{@code
 * MATERIAL MIN MAX WEIGHT [TIER]
 * DIAMOND_SWORD 1 1 5 RARE
 * SPLASH:HEALING 1 2 20
 * }</pre>
 *
 * <p>Everything here is decided before an item is built, so the whole grammar —
 * the token count, the numbers, the tier, what gets skipped — is tested against
 * a stand-in {@link LootItems} and never needs a server.
 *
 * <p>A bad line is skipped and reported, never fatal. One typo in a fifty-line
 * pool costs that line; refusing the file would cost the event.
 */
public final class LootLines {

    private LootLines() {
        throw new AssertionError("No instances.");
    }

    /** How many tokens a line must have before the optional tier. */
    private static final int REQUIRED_TOKENS = 4;

    /**
     * Reads one line.
     *
     * @param line     the line as written
     * @param items    how a token becomes an item
     * @param problems told what was wrong with the line, if anything
     * @return the entry, or {@code null} when the line could not be read
     */
    public static @Nullable LootEntry parse(@Nullable String line,
                                            @NotNull LootItems items,
                                            @NotNull BiConsumer<String, String> problems) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String written = line.trim();
        // Limit five so a tier written with spaces survives whole, as commons
        // allowed and some tables use.
        String[] parts = written.split("\\s+", REQUIRED_TOKENS + 1);
        if (parts.length < REQUIRED_TOKENS) {
            problems.accept(written, "expected at least " + REQUIRED_TOKENS + " tokens");
            return null;
        }

        int min;
        int max;
        double weight;
        try {
            min = Integer.parseInt(parts[1]);
            max = Integer.parseInt(parts[2]);
            weight = Double.parseDouble(parts[3]);
        } catch (NumberFormatException notANumber) {
            problems.accept(written, "amounts and weight must be numbers");
            return null;
        }

        if (min <= 0 || max < min || weight <= 0) {
            problems.accept(written, "amounts must be positive, the range in order, the weight above zero");
            return null;
        }

        String token = parts[0].toUpperCase(Locale.ROOT);
        ItemStack item = items.of(token);
        if (item == null) {
            problems.accept(written, "nothing named \"" + token + "\"");
            return null;
        }

        return LootEntry.item(items.snapshot(item))
                .minAmount(min)
                .maxAmount(max)
                .weight(weight)
                .tier(parts.length > REQUIRED_TOKENS ? parts[REQUIRED_TOKENS].toUpperCase(Locale.ROOT) : null)
                .build();
    }
}
