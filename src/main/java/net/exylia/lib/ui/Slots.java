package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which inventory positions something occupies.
 *
 * <p>Parsing lives here rather than in the loader because every part of a menu
 * asks the same question — an item's {@code slot}, a filler's {@code slots}, a
 * pagination area, an input region — and ExyliaCommons answered it in two
 * different places that disagreed: one threw on a malformed range and the
 * other silently dropped it, so the same typo behaved differently depending on
 * where it was written.
 *
 * <p>Accepts every form found in existing configuration:
 *
 * <pre>
 * slot: 13
 * slots: "10-16,19-25,28-34"
 * slots: [10, 11, 12]
 * slots: ["10-16", 22]
 * </pre>
 *
 * @since 1.22.0
 */
public final class Slots {

    private Slots() {
    }

    /**
     * Parses a slot expression.
     *
     * <p>Order is preserved and duplicates are dropped, because a pagination
     * area is filled in the order its slots are written.
     *
     * @param expression the value as written, such as {@code "10-16,19-25"}
     * @return the slots, in the order given
     * @throws IllegalArgumentException if a part is not a number or range
     */
    public static @NotNull List<Integer> parse(@NotNull String expression) {
        Set<Integer> ordered = new LinkedHashSet<>();
        for (String part : expression.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int dash = trimmed.indexOf('-', 1);
            if (dash > 0) {
                ordered.addAll(range(trimmed, dash));
            } else {
                ordered.add(number(trimmed, expression));
            }
        }
        return List.copyOf(ordered);
    }

    private static List<Integer> range(String part, int dash) {
        int from = number(part.substring(0, dash).trim(), part);
        int to = number(part.substring(dash + 1).trim(), part);
        if (to < from) {
            // Written backwards. Reading it as an empty range would silently
            // lose a row of a menu; saying so is cheaper than debugging it.
            throw new IllegalArgumentException(
                    "Slot range " + part + " ends before it starts");
        }
        List<Integer> slots = new ArrayList<>(to - from + 1);
        for (int slot = from; slot <= to; slot++) {
            slots.add(slot);
        }
        return slots;
    }

    private static int number(String text, String whole) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("Invalid slot in \"" + whole + "\": " + text);
        }
    }

    /**
     * Returns whether every slot fits in an inventory of the given size.
     *
     * @param slots the slots
     * @param size  how many slots the inventory has
     * @return whether all of them are inside it
     */
    public static boolean fit(@NotNull List<Integer> slots, int size) {
        for (int slot : slots) {
            if (slot < 0 || slot >= size) {
                return false;
            }
        }
        return true;
    }
}
