package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A row of buttons that packs itself.
 *
 * <p>Items that name a {@code group} instead of a {@code slot} are laid out on
 * every render: the ones whose condition passes, in file order, centred in the
 * group's slots. A card hidden by a switched-off feature leaves no hole; the
 * rest close ranks around the middle.
 *
 * <pre>
 * groups:
 *   sections:
 *     slots: "19-25"
 *     spacing: auto      # or a fixed gap such as 0 or 1
 * items:
 *   land:
 *     group: sections
 *     condition: "%land_enabled% == true"
 * </pre>
 *
 * @param id      what the file calls it
 * @param slots   where its visible items may go, in order
 * @param items   its members, in file order
 * @param spacing empty slots between neighbours, {@link #AUTO} or {@link #SPLIT}
 * @since 1.282.0
 */
public record UiGroup(@NotNull String id,
                      @NotNull List<Integer> slots,
                      @NotNull List<UiItem> items,
                      int spacing) {

    /** One gap between items when they all fit with it, none otherwise. */
    public static final int AUTO = -1;

    /**
     * An odd number of items sits together in the middle of a row; an even
     * number splits into two equal halves around the free centre slot.
     *
     * @since 1.487.0
     */
    public static final int SPLIT = -2;

    public UiGroup {
        slots = List.copyOf(slots);
        items = List.copyOf(items);
    }

    /**
     * Where each of {@code count} visible items goes, as indexes into a row
     * of {@code length} slots.
     *
     * <p>With {@link #AUTO}, items keep one empty slot between them while
     * {@code 2n - 1} fits, and sit side by side otherwise; a fixed gap that
     * does not fit falls back to side by side. Either way the block is
     * centred, leaning left when the leftover is odd. Items past the row's
     * length are not placed.
     *
     * @param count   how many items are visible
     * @param length  how many slots the row has
     * @param spacing the gap, or {@link #AUTO}
     * @return the index of each item's slot, in item order
     */
    public static @NotNull List<Integer> layout(int count, int length, int spacing) {
        int shown = Math.min(Math.max(count, 0), Math.max(length, 0));
        if (shown == 0) {
            return List.of();
        }
        int gap = spacing < 0 ? 1 : spacing;
        if (shown + (shown - 1) * gap > length) {
            gap = 0;
        }
        int start = (length - (shown + (shown - 1) * gap)) / 2;
        List<Integer> indexes = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            indexes.add(start + i * (gap + 1));
        }
        return indexes;
    }

    /**
     * The {@link #SPLIT} rule for one row: odd counts sit together in the
     * middle, even counts split into two halves around the free centre slot
     * (a row of even length has no centre, so it centres the block).
     *
     * @param count  how many items go in the row
     * @param length how many slots the row has
     * @return the index of each item's slot, in item order
     */
    public static @NotNull List<Integer> layoutSplit(int count, int length) {
        int shown = Math.min(Math.max(count, 0), Math.max(length, 0));
        if (shown % 2 == 0 && length % 2 == 1 && shown > length - 1) {
            shown = length;
        }
        List<Integer> indexes = new ArrayList<>(shown);
        if (shown % 2 == 1 || length % 2 == 0) {
            int start = (length - shown) / 2;
            for (int i = 0; i < shown; i++) {
                indexes.add(start + i);
            }
            return indexes;
        }
        int centre = length / 2;
        int half = shown / 2;
        for (int i = 0; i < shown; i++) {
            indexes.add(i < half ? centre - half + i : centre + 1 + i - half);
        }
        return indexes;
    }

    /** The group's slots cut into runs of consecutive slots within one row. */
    private List<List<Integer>> rows() {
        List<List<Integer>> rows = new ArrayList<>();
        for (int slot : slots) {
            List<Integer> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null && last.get(last.size() - 1) + 1 == slot && slot % 9 != 0) {
                last.add(slot);
            } else {
                rows.add(new ArrayList<>(List.of(slot)));
            }
        }
        return rows;
    }

    private <T> Map<Integer, T> placeSplit(List<T> visible) {
        List<List<Integer>> rows = rows();
        Map<Integer, T> placed = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return placed;
        }
        int widest = rows.stream().mapToInt(List::size).max().orElse(1);
        int used = Math.min(rows.size(), Math.max(1, (visible.size() + widest - 1) / widest));
        int next = 0;
        for (int r = 0; r < used; r++) {
            List<Integer> row = rows.get(r);
            int share = visible.size() / used + (r < visible.size() % used ? 1 : 0);
            for (int index : layoutSplit(Math.min(share, row.size()), row.size())) {
                if (next < visible.size()) {
                    placed.put(row.get(index), visible.get(next++));
                }
            }
        }
        return placed;
    }

    /**
     * Places the given visible items in this group's slots.
     *
     * @param visible the members to draw, in order
     * @return slot to item; a group slot missing from it is empty
     */
    public <T> @NotNull Map<Integer, T> place(@NotNull List<T> visible) {
        if (spacing == SPLIT) {
            return placeSplit(visible);
        }
        List<Integer> indexes = layout(visible.size(), slots.size(), spacing);
        Map<Integer, T> placed = new LinkedHashMap<>();
        for (int i = 0; i < indexes.size(); i++) {
            placed.put(slots.get(indexes.get(i)), visible.get(i));
        }
        return placed;
    }
}
