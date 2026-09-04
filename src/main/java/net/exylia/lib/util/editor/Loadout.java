package net.exylia.lib.util.editor;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a player's items go, and what position five means.
 *
 * <p>A kit, an arena loadout or a class's gear is stored as one flat list, and
 * every screen that shows it has to agree on what each position is for. Written
 * out once per screen it stops agreeing: in ExyliaSurvivalCore the preview drew
 * the twenty-eighth item under a pane labelled "hotbar" and the sixth in the
 * middle of the armour row, because the editor and the preview had each decided
 * for themselves. This is the one place that mapping lives.
 *
 * <p>The order is the player's own inventory, read the way a player reads it:
 *
 * <pre>
 * 0..3    helmet, chestplate, leggings, boots
 * 4       offhand
 * 5..31   the twenty-seven storage slots
 * 32..40  the nine hotbar slots
 * </pre>
 *
 * <p>It is deliberately not Bukkit's own {@code getContents()} order, which
 * starts at the hotbar and ends with the armour upside down (boots first). A
 * loadout is read and edited by people far more often than it is handed to
 * {@code setContents}, and the conversion costs one loop in the one place that
 * gives it out.
 *
 * @since 1.110.0
 */
public final class Loadout {

    /** How many positions a loadout has in total. */
    public static final int SIZE = 41;

    /** How many armour positions there are, and so the offhand's own index. */
    public static final int ARMOR_COUNT = 4;

    /** The offhand's position. */
    public static final int OFFHAND = 4;

    /** The first storage position. */
    public static final int STORAGE_START = 5;

    /** How many storage positions there are. */
    public static final int STORAGE_COUNT = 27;

    /** The first hotbar position. */
    public static final int HOTBAR_START = 32;

    /** How many hotbar positions there are. */
    public static final int HOTBAR_COUNT = 9;

    private Loadout() {
        throw new AssertionError("No instances.");
    }

    /** What a position is for. */
    public enum Part {
        HELMET,
        CHESTPLATE,
        LEGGINGS,
        BOOTS,
        OFFHAND,
        STORAGE,
        HOTBAR;

        /** Whether this part is a piece of armour, and so what auto-equip is about. */
        public boolean isArmor() {
            return ordinal() <= BOOTS.ordinal();
        }

        /** The name a menu names its template with, such as {@code helmet}. */
        public @NotNull String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What the position at this index is for.
     *
     * @param index the position, {@code 0} to {@link #SIZE} exclusive
     * @return the part, or {@code null} when the index is outside the layout
     */
    public static @Nullable Part partOf(int index) {
        if (index < 0 || index >= SIZE) return null;
        if (index < ARMOR_COUNT) return Part.values()[index];
        if (index == OFFHAND) return Part.OFFHAND;
        if (index < HOTBAR_START) return Part.STORAGE;
        return Part.HOTBAR;
    }

    /**
     * Which storage or hotbar slot an index is, counted from that row's start.
     *
     * @param index the position
     * @return the offset, or {@code -1} for a position that is not in a row
     */
    public static int offsetIn(int index) {
        Part part = partOf(index);
        if (part == Part.STORAGE) return index - STORAGE_START;
        if (part == Part.HOTBAR) return index - HOTBAR_START;
        return -1;
    }

    /** The index of the nth storage position. */
    public static int storage(int offset) {
        return STORAGE_START + offset;
    }

    /** The index of the nth hotbar position. */
    public static int hotbar(int offset) {
        return HOTBAR_START + offset;
    }

    /**
     * The item at a position, or {@code null} when the loadout has none there.
     *
     * <p>A loadout is as long as its last item and no longer, so reading a
     * position past the end is an ordinary "nothing there" rather than a
     * mistake.
     *
     * @param items the loadout
     * @param index the position
     * @return the item, or {@code null}
     */
    public static @Nullable ItemStack at(@Nullable List<ItemStack> items, int index) {
        if (items == null || index < 0 || index >= items.size()) return null;
        ItemStack item = items.get(index);
        return item == null || item.getType().isAir() ? null : item;
    }

    /**
     * The slots the editor lays a loadout out in, in position order.
     *
     * <p>The first five are the top-left corner of the window and the rest are
     * the four rows below it, which is what makes the editor look like an
     * inventory: {@code 0-4}, then {@code 9-44}.
     *
     * @return the slots, one per position
     */
    public static @NotNull List<Integer> editorSlots() {
        List<Integer> slots = new ArrayList<>(SIZE);
        for (int slot = 0; slot <= 4; slot++) slots.add(slot);
        for (int slot = 9; slot <= 44; slot++) slots.add(slot);
        return List.copyOf(slots);
    }

    /**
     * What a player is wearing and carrying, as a loadout.
     *
     * <p>Cloned, so what the caller keeps cannot change under it the next time
     * the player moves an item.
     *
     * @param player whose inventory to read
     * @return the loadout, trimmed
     */
    public static @NotNull List<ItemStack> capture(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>(SIZE);
        items.add(clone(inventory.getHelmet()));
        items.add(clone(inventory.getChestplate()));
        items.add(clone(inventory.getLeggings()));
        items.add(clone(inventory.getBoots()));
        items.add(clone(inventory.getItemInOffHand()));

        // A player's contents are the hotbar first and the three rows after it,
        // which is the other way round from how a loadout is read.
        ItemStack[] contents = inventory.getContents();
        for (int slot = 9; slot <= 35; slot++) items.add(clone(contents[slot]));
        for (int slot = 0; slot <= 8; slot++) items.add(clone(contents[slot]));
        return trim(items);
    }

    /**
     * Drops the empty tail of a loadout.
     *
     * <p>Trailing empties are not part of it: somebody who clears the last row
     * means the loadout is shorter, not that it ends in nulls. Air is an empty
     * position too, because a client sends air for a slot it emptied.
     *
     * @param items the loadout
     * @return a new list, as long as its last item
     */
    public static @NotNull List<ItemStack> trim(@NotNull List<ItemStack> items) {
        List<ItemStack> trimmed = new ArrayList<>(items);
        for (int index = 0; index < trimmed.size(); index++) {
            ItemStack item = trimmed.get(index);
            if (item != null && item.getType().isAir()) trimmed.set(index, null);
        }
        while (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1) == null) {
            trimmed.remove(trimmed.size() - 1);
        }
        return trimmed;
    }

    private static @Nullable ItemStack clone(@Nullable ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }
}
