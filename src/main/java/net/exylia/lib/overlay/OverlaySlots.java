package net.exylia.lib.overlay;

import net.exylia.lib.ui.Slots;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where a slot of the lower inventory is, in every numbering that matters.
 *
 * <p>Three numbering schemes describe the same forty-one places, and mixing
 * them up is the whole difficulty of this module:
 *
 * <ul>
 *   <li><b>Inventory index</b> — what {@code player.getInventory().setItem}
 *       takes and what this module's API speaks: {@code 0-8} hotbar,
 *       {@code 9-35} storage, {@code 36-39} armour, {@code 40} off-hand.</li>
 *   <li><b>Player menu slot</b> — how the client numbers the player's own
 *       inventory screen, which is container zero: {@code 0} crafting result,
 *       {@code 1-4} crafting grid, {@code 5-8} armour, {@code 9-35} storage,
 *       {@code 36-44} hotbar, {@code 45} off-hand.</li>
 *   <li><b>Container slot</b> — how the client numbers a chest window: the
 *       top inventory first, then storage, then hotbar. No armour, no
 *       off-hand.</li>
 * </ul>
 *
 * <p>Every conversion is here rather than inline, because getting one of them
 * wrong shows up as a button in the wrong place rather than as an error.
 *
 * @since 1.79.0
 */
public final class OverlaySlots {

    /** First hotbar slot, the one a fresh player holds. */
    public static final int HOTBAR_FIRST = 0;
    /** Last hotbar slot. */
    public static final int HOTBAR_LAST = 8;
    /** First slot of the three storage rows. */
    public static final int STORAGE_FIRST = 9;
    /** Last slot of the three storage rows. */
    public static final int STORAGE_LAST = 35;

    public static final int BOOTS = 36;
    public static final int LEGGINGS = 37;
    public static final int CHESTPLATE = 38;
    public static final int HELMET = 39;
    public static final int OFFHAND = 40;

    /** How many places an overlay can occupy. */
    public static final int SIZE = 41;

    /** How many slots the player's own inventory screen has. */
    public static final int PLAYER_MENU_SIZE = 46;

    /** Player menu slot to inventory index, or {@code -1} for the craft grid. */
    private static final int[] FROM_PLAYER_MENU = new int[PLAYER_MENU_SIZE];

    /** Inventory index to player menu slot. */
    private static final int[] TO_PLAYER_MENU = new int[SIZE];

    static {
        Arrays.fill(FROM_PLAYER_MENU, -1);
        FROM_PLAYER_MENU[5] = HELMET;
        FROM_PLAYER_MENU[6] = CHESTPLATE;
        FROM_PLAYER_MENU[7] = LEGGINGS;
        FROM_PLAYER_MENU[8] = BOOTS;
        for (int slot = 9; slot <= 35; slot++) {
            FROM_PLAYER_MENU[slot] = slot;
        }
        for (int slot = 36; slot <= 44; slot++) {
            FROM_PLAYER_MENU[slot] = slot - 36;
        }
        FROM_PLAYER_MENU[45] = OFFHAND;

        Arrays.fill(TO_PLAYER_MENU, -1);
        for (int slot = 0; slot < PLAYER_MENU_SIZE; slot++) {
            int index = FROM_PLAYER_MENU[slot];
            if (index >= 0) {
                TO_PLAYER_MENU[index] = slot;
            }
        }
    }

    private OverlaySlots() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns whether an inventory index names a real place.
     *
     * @param index the index
     * @return whether it is one of the forty-one
     */
    public static boolean isValid(int index) {
        return index >= 0 && index < SIZE;
    }

    /**
     * Returns whether an index is a piece of armour or the off-hand.
     *
     * @param index the inventory index
     * @return whether it is worn rather than carried
     */
    public static boolean isWorn(int index) {
        return index >= BOOTS && index <= OFFHAND;
    }

    /**
     * Converts a slot of the player's own inventory screen.
     *
     * @param rawSlot the slot the client sent, container zero
     * @return the inventory index, or {@code -1} for the crafting area and
     *         anything out of range
     */
    public static int fromPlayerMenu(int rawSlot) {
        return rawSlot >= 0 && rawSlot < PLAYER_MENU_SIZE ? FROM_PLAYER_MENU[rawSlot] : -1;
    }

    /**
     * Converts an inventory index to a slot of the player's own screen.
     *
     * @param index the inventory index
     * @return the slot, or {@code -1} when the index names no place
     */
    public static int toPlayerMenu(int index) {
        return isValid(index) ? TO_PLAYER_MENU[index] : -1;
    }

    /**
     * Converts a slot of an open container window.
     *
     * <p>Below the container come the three storage rows and then the hotbar,
     * which is why the hotbar's high slot numbers map to the low indices. A
     * container window shows neither armour nor the off-hand, so a slot that
     * is not in the lower region has no index.
     *
     * @param rawSlot the slot the client sent
     * @param topSize how many slots the container itself has
     * @return the inventory index, or {@code -1} when the slot is in the
     *         container rather than below it
     */
    public static int fromContainer(int rawSlot, int topSize) {
        int below = rawSlot - topSize;
        if (below < 0 || below > 35) {
            return -1;
        }
        return below < 27 ? below + STORAGE_FIRST : below - 27;
    }

    /**
     * Reads a slot expression, accepting the names of the worn slots.
     *
     * <p>Numbers and ranges mean what they mean everywhere else in the UI
     * modules; the five names exist because {@code slot: 39} for a helmet is
     * a number nobody remembers.
     *
     * <pre>
     * slot: 0
     * slots: "0-8"
     * slot: helmet
     * slots: "0-8,offhand"
     * </pre>
     *
     * @param expression the value as written
     * @return the indices, in the order given
     * @throws IllegalArgumentException if a part is neither a name nor a number
     */
    public static @NotNull List<Integer> parse(@NotNull String expression) {
        Set<Integer> ordered = new LinkedHashSet<>();
        for (String part : expression.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int named = byName(trimmed);
            if (named >= 0) {
                ordered.add(named);
            } else {
                ordered.addAll(Slots.parse(trimmed));
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Reads the name of a worn slot.
     *
     * @param name the name, in any case
     * @return the inventory index, or {@code -1} when it is not one
     */
    public static int byName(@NotNull String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "boots" -> BOOTS;
            case "leggings", "legs" -> LEGGINGS;
            case "chestplate", "chest" -> CHESTPLATE;
            case "helmet", "head" -> HELMET;
            case "offhand", "off_hand", "off-hand" -> OFFHAND;
            default -> -1;
        };
    }

    /** Every index an overlay can occupy, in order. */
    public static @NotNull List<Integer> all() {
        List<Integer> every = new ArrayList<>(SIZE);
        for (int index = 0; index < SIZE; index++) {
            every.add(index);
        }
        return List.copyOf(every);
    }
}
