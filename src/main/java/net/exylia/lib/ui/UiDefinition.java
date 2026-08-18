package net.exylia.lib.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu as configuration describes it, compiled once.
 *
 * <p>Shared by every player who opens it. Nothing here is per-viewer: the
 * title still contains its placeholders, and the slots are definitions rather
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
        @NotNull UiFillers fillers,
        @NotNull Map<String, UiSection> sections,
        @NotNull List<Integer> inputSlots,
        @NotNull UiSounds sounds,
        @NotNull UiRefresh refresh,
        @Nullable UiAnimationSpec openAnimation,
        @NotNull List<String> openActions,
        @NotNull List<String> closeActions,
        @Nullable String parent) {

    public UiDefinition {
        items = Map.copyOf(items);
        sections = Map.copyOf(sections);
        inputSlots = List.copyOf(inputSlots);
        openActions = List.copyOf(openActions);
        closeActions = List.copyOf(closeActions);
    }

    /**
     * What kind of window this is.
     *
     * <p>The names ExyliaCommons used are accepted so existing files keep
     * working, but they described two different things at once — the container
     * and whether it paginated. Here the container is the kind and pagination
     * is simply whether there are any sections.
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

        /**
         * The Bukkit container this kind opens.
         *
         * <p>A chest has no fixed type here because its size is configured; it
         * is created by slot count instead.
         *
         * <p>Only safe on a running server: {@code InventoryType} reaches for
         * {@code MenuType} and so for the registry. Call it when opening a
         * window, never when reading a file — {@link #sizeOf} exists so that
         * loading a menu needs no server at all.
         *
         * @return the type, or {@code null} for a chest
         */
        public org.bukkit.event.inventory.InventoryType type() {
            return switch (this) {
                case CHEST -> null;
                case BARREL -> org.bukkit.event.inventory.InventoryType.BARREL;
                case HOPPER -> org.bukkit.event.inventory.InventoryType.HOPPER;
                case DROPPER -> org.bukkit.event.inventory.InventoryType.DROPPER;
                case DISPENSER -> org.bukkit.event.inventory.InventoryType.DISPENSER;
                case ANVIL -> org.bukkit.event.inventory.InventoryType.ANVIL;
                case ENCHANTING -> org.bukkit.event.inventory.InventoryType.ENCHANTING;
                case FURNACE -> org.bukkit.event.inventory.InventoryType.FURNACE;
                case BREWING -> org.bukkit.event.inventory.InventoryType.BREWING;
                case BEACON -> org.bukkit.event.inventory.InventoryType.BEACON;
                case CRAFTING -> org.bukkit.event.inventory.InventoryType.WORKBENCH;
                case MERCHANT -> org.bukkit.event.inventory.InventoryType.MERCHANT;
                case SMITHING -> org.bukkit.event.inventory.InventoryType.SMITHING;
                case GRINDSTONE -> org.bukkit.event.inventory.InventoryType.GRINDSTONE;
                case CARTOGRAPHY -> org.bukkit.event.inventory.InventoryType.CARTOGRAPHY;
                case LOOM -> org.bukkit.event.inventory.InventoryType.LOOM;
                case STONECUTTER -> org.bukkit.event.inventory.InventoryType.STONECUTTER;
            };
        }

        /**
         * How many slots this container has, given a configured size.
         *
         * <p>Written out rather than asked of {@code InventoryType}, because
         * that enum reaches for {@code MenuType} and so for the server's
         * registry: touching it would mean a menu file could only be read on a
         * running server, and reading configuration is meant to be pure.
         *
         * <p>The numbers are Bukkit's own, taken from the enum's declarations.
         * Guessing them got three wrong — a smithing table has four slots and
         * not three, a crafting window ten and not nine, and a barrel is a
         * fixed twenty-seven rather than whatever the file says — and each
         * would have been an inventory of the wrong size, which throws on open.
         * {@code UiKindTest} checks every one against the real enum.
         *
         * @param configured what the file asked for, used only by a chest
         * @return the real slot count
         */
        public int sizeOf(int configured) {
            return switch (this) {
                case CHEST -> configured;
                case BARREL -> 27;
                case HOPPER, BREWING -> 5;
                case DROPPER, DISPENSER -> 9;
                case CRAFTING -> 10;
                case ANVIL, GRINDSTONE, CARTOGRAPHY, FURNACE, MERCHANT -> 3;
                case SMITHING, LOOM -> 4;
                case ENCHANTING, STONECUTTER -> 2;
                case BEACON -> 1;
            };
        }

        /**
         * Returns whether the size in configuration means anything.
         *
         * <p>Only a chest is resizable. A barrel looks like one and is not: it
         * is always twenty-seven slots.
         */
        public boolean isSizeConfigurable() {
            return this == CHEST;
        }
    }

    /** Returns whether this menu has any paginated list. */
    public boolean isPaginated() {
        return !sections.isEmpty();
    }

    /**
     * The only list this menu has, when it has exactly one.
     *
     * <p>For the ordinary case, which is a hundred and fifty of the files in
     * the wild: a menu written with a {@code pagination} block has one section
     * and its name is not interesting.
     *
     * @return the section, or {@code null} when there is not exactly one
     */
    public @Nullable UiSection section() {
        return sections.size() == 1 ? sections.values().iterator().next() : null;
    }

    /**
     * A list by name.
     *
     * @param id what it is called
     * @return the section, or {@code null} when there is none
     */
    public @Nullable UiSection section(@NotNull String id) {
        return sections.get(id);
    }

    /**
     * Which section owns a slot, if any.
     *
     * <p>Used when a click arrives: the slot number is all the client sends, so
     * the menu decides what it was.
     *
     * @param slot the slot
     * @return the section drawing there, or {@code null}
     */
    public @Nullable UiSection sectionAt(int slot) {
        for (UiSection section : sections.values()) {
            if (section.slots().contains(slot)) {
                return section;
            }
        }
        return null;
    }

    /**
     * Every slot a section draws a navigation button in, mapped to what it does.
     *
     * <p>Built once here rather than searched on every click.
     *
     * @return slot to the page step it applies, {@code -1} back and {@code 1} forward
     */
    public @NotNull Map<Integer, Navigation> navigation() {
        Map<Integer, Navigation> navigation = new LinkedHashMap<>();
        for (UiSection section : sections.values()) {
            if (section.previous() != null) {
                navigation.put(section.previous().slot(), new Navigation(section.id(), -1));
            }
            if (section.next() != null) {
                navigation.put(section.next().slot(), new Navigation(section.id(), 1));
            }
        }
        return navigation;
    }

    /**
     * A page button.
     *
     * @param section which list it pages
     * @param step    which way, {@code -1} or {@code 1}
     */
    public record Navigation(@NotNull String section, int step) {
    }

    /**
     * What covers a slot when nothing that could be drawn over it is.
     *
     * <p>Asked when a page button has nowhere to go, which is most menus most
     * of the time. A fixed item wins over any filler: several deployed menus
     * put a button under a page arrow — {@code spectator.yml} has its
     * "toggle spectators" under {@code previous} — and painting glass over it
     * would take away a button that works.
     *
     * @param slot the slot
     * @return what to draw there, or {@code null} when nothing covers it
     * @since 1.27.0
     */
    public @Nullable UiItem beneath(int slot) {
        UiItem fixed = items.get(slot);
        return fixed != null ? fixed : fillers.backgroundAt(slot);
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
