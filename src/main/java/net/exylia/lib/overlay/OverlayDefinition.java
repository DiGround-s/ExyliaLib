package net.exylia.lib.overlay;

import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiRefresh;
import net.exylia.lib.ui.UiSounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A compiled lower-inventory overlay.
 *
 * <p>The same shape as a {@link net.exylia.lib.ui.UiDefinition}, minus
 * everything that only means something on a screen: no size, no title, no
 * pagination, no lists. An overlay is a fixed set of places in a player's own
 * inventory, and the places are the ones {@link OverlaySlots} names.
 *
 * <p>Read once when configuration loads and shared by every player wearing it;
 * turning an item into an {@code ItemStack} is per-viewer work done when it is
 * drawn, exactly as a menu does it.
 *
 * @param id       what to call it, qualified with the owning plugin's namespace
 * @param items    what each slot draws and what pressing it does
 * @param refresh  when the overlay redraws itself
 * @param lock     how much of the player's inventory is frozen
 * @param pickup   whether the player may still pick items up into the real
 *                 inventory hidden underneath
 * @param hideRest whether slots the overlay does not draw look empty rather
 *                 than showing what the player really has
 * @param sounds    what pressing a slot sounds like
 * @param emptyHand what a slot the overlay owns but draws nothing in does
 * @since 1.79.0
 */
public record OverlayDefinition(
        @NotNull String id,
        @NotNull Map<Integer, UiItem> items,
        @NotNull UiRefresh refresh,
        @NotNull OverlayLock lock,
        boolean pickup,
        boolean hideRest,
        @NotNull UiSounds sounds,
        @NotNull ClickBindings emptyHand) {

    public OverlayDefinition {
        for (int slot : items.keySet()) {
            if (!OverlaySlots.isValid(slot)) {
                throw new IllegalArgumentException("Overlay \"" + id + "\" uses slot " + slot
                        + ", which is outside the player inventory (0-"
                        + (OverlaySlots.SIZE - 1) + ")");
            }
        }
        items = Map.copyOf(items);
    }

    /**
     * Returns whether anything about this overlay can change while it is worn.
     *
     * <p>A static overlay is drawn once and never looked at again, so its
     * redraw timer is never started. A staff hotbar of five fixed tools should
     * cost nothing per tick, and this is what makes that true.
     *
     * @return whether any slot can change
     */
    public boolean isDynamic() {
        for (UiItem item : items.values()) {
            if (item.isDynamic()) {
                return true;
            }
        }
        return false;
    }

    /**
     * What an empty-looking hand does.
     *
     * <p>Under {@code hide_rest} most of a wearer's slots draw nothing and
     * look empty, and a press with one of them is refused rather than passed
     * on: the player may really be holding something, and using an item the
     * client is not showing is the thing an overlay exists to prevent. That
     * leaves the press with nowhere to go, which is why it can be bound.
     *
     * <p>Binding it takes those presses away from the world for good: a slot
     * that answers here is never handed to the server, whether or not a real
     * item is under it, so what happens no longer depends on what the wearer
     * happens to be carrying.
     *
     * @return the bindings, empty when nothing is bound
     * @since 1.82.1
     */
    public @NotNull ClickBindings emptyHand() {
        return emptyHand;
    }

    /**
     * Returns whether this overlay owns a slot.
     *
     * <p>An overlay that hides the rest owns every slot, including the ones it
     * draws nothing in: drawing nothing there is the point.
     *
     * @param index the inventory index
     * @return whether the slot is the overlay's rather than the player's
     */
    public boolean owns(int index) {
        if (!OverlaySlots.isValid(index)) {
            return false;
        }
        return hideRest || items.containsKey(index);
    }

    /** Starts describing an overlay. */
    public static @NotNull Builder of(@NotNull String id) {
        return new Builder(id);
    }

    /** Builds an overlay in code, for one that no file describes. */
    public static final class Builder {

        private final String id;
        private final Map<Integer, UiItem> items = new LinkedHashMap<>();
        private UiRefresh refresh = UiRefresh.NEVER;
        private OverlayLock lock = OverlayLock.FULL;
        private boolean pickup = true;
        private boolean hideRest;
        private UiSounds sounds = UiSounds.DEFAULTS;
        private ClickBindings emptyHand = ClickBindings.none();

        private Builder(String id) {
            this.id = id;
        }

        /**
         * Puts an item in one slot.
         *
         * @param index the inventory index, as {@link OverlaySlots} numbers it
         * @param item  what to draw and what pressing it does
         * @return this
         */
        public @NotNull Builder slot(int index, @NotNull UiItem item) {
            items.put(index, item);
            return this;
        }

        /**
         * Puts the same item in several slots.
         *
         * @param expression a slot expression, such as {@code "0-8"} or {@code "helmet"}
         * @param item       what to draw
         * @return this
         */
        public @NotNull Builder slots(@NotNull String expression, @NotNull UiItem item) {
            for (int index : OverlaySlots.parse(expression)) {
                items.put(index, item);
            }
            return this;
        }

        public @NotNull Builder refresh(@NotNull UiRefresh refresh) {
            this.refresh = refresh;
            return this;
        }

        public @NotNull Builder lock(@NotNull OverlayLock lock) {
            this.lock = lock;
            return this;
        }

        /**
         * Sets whether the player may pick items up while wearing this.
         *
         * <p>They land in the real inventory, which the overlay is covering,
         * so the player is given something they cannot see. For a staff mode
         * that is worth refusing outright.
         *
         * @param pickup whether pickups are allowed
         * @return this
         */
        public @NotNull Builder pickup(boolean pickup) {
            this.pickup = pickup;
            return this;
        }

        /**
         * Blanks every slot this overlay does not draw.
         *
         * <p>Without it the player keeps seeing their real items around the
         * overlay's, which is right for a few added buttons and wrong for a
         * staff mode.
         *
         * @return this
         */
        public @NotNull Builder hideRest() {
            this.hideRest = true;
            return this;
        }

        public @NotNull Builder sounds(@Nullable UiSounds sounds) {
            this.sounds = sounds == null ? UiSounds.SILENT : sounds;
            return this;
        }

        /**
         * Binds what a slot the overlay owns but draws nothing in does.
         *
         * @param emptyHand the bindings, or {@code null} for none
         * @return this
         * @since 1.82.1
         */
        public @NotNull Builder emptyHand(@Nullable ClickBindings emptyHand) {
            this.emptyHand = emptyHand == null ? ClickBindings.none() : emptyHand;
            return this;
        }

        public @NotNull OverlayDefinition build() {
            return new OverlayDefinition(id, items, refresh, lock, pickup, hideRest, sounds, emptyHand);
        }
    }
}
