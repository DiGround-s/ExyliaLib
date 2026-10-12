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
 * @param spacing empty slots between neighbours, or {@link #AUTO}
 * @since 1.282.0
 */
public record UiGroup(@NotNull String id,
                      @NotNull List<Integer> slots,
                      @NotNull List<UiItem> items,
                      int spacing) {

    /** One gap between items when they all fit with it, none otherwise. */
    public static final int AUTO = -1;

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
     * Places the given visible items in this group's slots.
     *
     * @param visible the members to draw, in order
     * @return slot to item; a group slot missing from it is empty
     */
    public <T> @NotNull Map<Integer, T> place(@NotNull List<T> visible) {
        List<Integer> indexes = layout(visible.size(), slots.size(), spacing);
        Map<Integer, T> placed = new LinkedHashMap<>();
        for (int i = 0; i < indexes.size(); i++) {
            placed.put(slots.get(indexes.get(i)), visible.get(i));
        }
        return placed;
    }
}
