package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A menu as configuration describes it, compiled once.
 *
 * <p>Shared by every player who opens it. Nothing here is per-viewer: the
 * title still contains its placeholders, and the items are definitions rather
 * than item stacks. That is what makes opening a menu cheap — the expensive
 * part happened when the file was read.
 *
 * @since 1.22.0
 */
public record UiDefinition(
        @NotNull String id,
        @NotNull String title,
        @NotNull UiKind kind,
        int size,
        @NotNull Map<Integer, UiItem> items,
        @NotNull List<UiItem> fillers,
        @Nullable Pagination pagination,
        @NotNull List<Integer> inputSlots,
        @NotNull UiSounds sounds,
        @Nullable UiAnimationSpec openAnimation,
        @NotNull List<String> openActions,
        @NotNull List<String> closeActions,
        @Nullable String parent) {

    public UiDefinition {
        items = Map.copyOf(items);
        fillers = List.copyOf(fillers);
        inputSlots = List.copyOf(inputSlots);
        openActions = List.copyOf(openActions);
        closeActions = List.copyOf(closeActions);
    }

    /**
     * What kind of window this is.
     *
     * <p>The names ExyliaCommons used are accepted so existing files keep
     * working, but they described two different things at once — the container
     * and whether it paginates. Here the container is the kind and pagination
     * is simply whether a {@link Pagination} is present.
     */
    public enum UiKind {
        CHEST,
        HOPPER,
        DROPPER,
        DISPENSER,
        BARREL,
        ANVIL,
        ENCHANTING,
        FURNACE,
        BREWING,
        BEACON,
        CRAFTING,
        MERCHANT,
        SMITHING,
        GRINDSTONE,
        CARTOGRAPHY,
        LOOM,
        STONECUTTER;

        /** How many slots this container has, given a configured size. */
        public int sizeOf(int configured) {
            return switch (this) {
                case CHEST, BARREL -> configured;
                case HOPPER -> 5;
                case DROPPER, DISPENSER, CRAFTING -> 9;
                case ANVIL, GRINDSTONE, CARTOGRAPHY, SMITHING -> 3;
                case ENCHANTING, FURNACE, BREWING, STONECUTTER, LOOM -> switch (this) {
                    case ENCHANTING -> 2;
                    case FURNACE -> 3;
                    case BREWING -> 5;
                    case STONECUTTER -> 2;
                    default -> 4;
                };
                case BEACON -> 1;
                case MERCHANT -> 3;
            };
        }

        /** Returns whether the size in configuration means anything for this container. */
        public boolean isSizeConfigurable() {
            return this == CHEST || this == BARREL;
        }
    }

    /**
     * The list part of a menu, if it has one.
     *
     * @param slots    where entries are drawn, in order
     * @param template what one entry looks like
     * @param previous the previous-page button, if any
     * @param next     the next-page button, if any
     * @param filler   what fills empty entry slots, if anything
     */
    public record Pagination(@NotNull List<Integer> slots, @NotNull UiItem template,
                             @Nullable Placed previous, @Nullable Placed next,
                             @Nullable UiItem filler) {
        public Pagination {
            slots = List.copyOf(slots);
        }

        /** How many entries fit on one page. */
        public int perPage() {
            return slots.size();
        }
    }

    /** An item and where it goes. */
    public record Placed(int slot, @NotNull UiItem item) {
    }

    /** Returns whether this menu paginates. */
    public boolean isPaginated() {
        return pagination != null;
    }

    /** Returns whether anything in this menu can change while it is open. */
    public boolean isDynamic() {
        if (isPaginated() || openAnimation != null || title.indexOf('%') >= 0) {
            return true;
        }
        for (UiItem item : items.values()) {
            if (item.isDynamic()) {
                return true;
            }
        }
        return false;
    }
}
