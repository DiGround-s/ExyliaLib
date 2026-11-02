package net.exylia.lib.web.internal;

import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiGroup;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sizes a settings screen to what it shows. The screens are written for a double chest with
 * their controls on the bottom row; a screen with three buttons is cut to the rows it needs,
 * its buttons centred the way the hand-made admin screens centre theirs (3 → 11/13/15,
 * 4 → 10/12/14/16), the controls moved to its new bottom row. Past what fits, the screen stays
 * a double chest and pages.
 */
final class ConfigLayout {

    private static final int WIDTH = 7;
    private static final String COUNTER = " {muted}%current_page%/%total_pages%";

    /**
     * Where the buttons go and how big the screen is.
     *
     * @param slots     where each button is drawn, in order
     * @param size      the screen's size
     * @param paginated whether there are more buttons than slots on one page
     */
    record Fit(List<Integer> slots, int size, boolean paginated) {
    }

    private ConfigLayout() {
    }

    /**
     * The layout for some buttons under a header.
     *
     * @param count how many buttons
     * @param top   rows kept above them: 1 for a header, 2 for a header and a gap
     */
    static Fit fit(int count, int top) {
        int rows = Math.max(1, (count + WIDTH - 1) / WIDTH);
        int most = 6 - top - 2;
        if (rows > most) {
            List<Integer> slots = new ArrayList<>();
            for (int row = top; row <= 4; row++) {
                for (int column = 1; column <= WIDTH; column++) slots.add(row * 9 + column);
            }
            return new Fit(slots, 54, count > slots.size());
        }
        List<Integer> slots = new ArrayList<>(count);
        int base = count / rows;
        int extra = count % rows;
        for (int row = 0; row < rows; row++) {
            int here = base + (row < extra ? 1 : 0);
            for (int index : UiGroup.layout(here, WIDTH, UiGroup.AUTO)) slots.add((top + row) * 9 + 1 + index);
        }
        return new Fit(slots, (top + rows + 2) * 9, false);
    }

    /**
     * A screen written as a double chest, cut to a layout: its list drawn in the layout's slots,
     * anything on its bottom row moved to the new bottom row, the page counter dropped from the
     * title when there is one page.
     */
    static UiDefinition apply(UiDefinition base, Fit fit) {
        UiSection list = base.section();
        if (list == null) return base;
        int shift = fit.size() - base.size();
        Map<Integer, UiItem> items = new LinkedHashMap<>();
        base.items().forEach((slot, item) -> items.put(slot >= base.size() - 9 ? slot + shift : slot, item));
        UiSection section = new UiSection(list.id(), fit.slots(), list.templates(),
                moved(list.previous(), base.size(), shift), moved(list.next(), base.size(), shift), list.filler());
        String title = fit.paginated() ? base.title() : base.title().replace(COUNTER, "");
        return new UiDefinition(base.id(), title, base.kind(), fit.size(), items, base.fillers(),
                Map.of(section.id(), section), base.inputSlots(), base.sounds(), base.refresh(),
                base.openActions(), base.closeActions(), base.parent(), base.groups(), base.clickCooldownMillis());
    }

    private static UiSection.Placed moved(UiSection.Placed placed, int size, int shift) {
        if (placed == null || placed.slot() < size - 9) return placed;
        return new UiSection.Placed(placed.slot() + shift, placed.item());
    }
}
