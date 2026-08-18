package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What fills the slots a menu does not otherwise use.
 *
 * <p>Three different jobs, which is why this is a record rather than a list:
 *
 * <pre>
 * filler:
 *   global:                      # everything left over
 *     material: BLACK_STAINED_GLASS_PANE
 *     hide_tooltip: true
 *   pagination:                  # a list's empty slots, when it is short
 *     material: LIGHT_GRAY_STAINED_GLASS_PANE
 *     name: "{muted}No kits available"
 *   custom:                      # named panels, each with its own slots
 *     header:
 *       material: GRAY_STAINED_GLASS_PANE
 *       slots: "0-8"
 * </pre>
 *
 * <p>The {@code pagination} filler is the one worth being careful about: it is
 * what a player sees when a list has fewer rows than slots, and it usually says
 * something — "no kits available" rather than a grey pane. Treating it as
 * another background would tell somebody with an empty list nothing at all.
 *
 * <p>Eight hundred and twenty-six menus write {@code global}, four hundred and
 * ninety-nine write {@code pagination}, and six write {@code custom}.
 *
 * @param global     what fills leftover slots, or {@code null}
 * @param pagination what fills a short list's empty slots, or {@code null}
 * @param custom     named panels with their own slots
 * @since 1.22.0
 */
public record UiFillers(@Nullable UiItem global, @Nullable UiItem pagination,
                        @NotNull List<Panel> custom) {

    /** A menu that fills nothing. */
    public static final UiFillers NONE = new UiFillers(null, null, List.of());

    public UiFillers {
        custom = List.copyOf(custom);
    }

    /**
     * A named panel of filler.
     *
     * @param id    what the file called it
     * @param item  what to draw
     * @param slots where
     */
    public record Panel(@NotNull String id, @NotNull UiItem item,
                        @NotNull List<Integer> slots) {

        public Panel {
            slots = List.copyOf(slots);
        }
    }

    /** Returns whether there is anything to fill with. */
    public boolean isEmpty() {
        return global == null && pagination == null && custom.isEmpty();
    }

    /**
     * What covers a slot nothing else claims.
     *
     * <p>A named panel wins over the background, and the first panel to claim a
     * slot keeps it — the order a menu draws them in. Asked whenever a slot has
     * to go back to looking like the rest of the menu: a page button that has
     * nowhere to go, for instance.
     *
     * @param slot the slot
     * @return what to draw there, or {@code null} when nothing fills it
     * @since 1.27.0
     */
    public @Nullable UiItem backgroundAt(int slot) {
        for (Panel panel : custom) {
            if (panel.slots().contains(slot)) {
                return panel.item();
            }
        }
        return global;
    }
}
