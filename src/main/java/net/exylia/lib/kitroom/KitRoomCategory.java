package net.exylia.lib.kitroom;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One tab of a kit room: a name and the items players may take from it.
 *
 * <p>Immutable, and stored in the owning plugin's own database. The items live
 * in one column rather than a row each, because a category is only ever read
 * and written whole: the editor hands back the full list.
 *
 * @param id       stable key, what plugins store to allow a category
 * @param name     what players see, in Exylia text notation
 * @param priority the tab order, lowest first
 * @param items    what can be taken, in order
 * @since 1.511.0
 */
@Table(value = "exylia_kitroom", data = DataKind.SETUP)
public record KitRoomCategory(
        @Id(length = 64) String id,
        @Column(length = 128) String name,
        @Column int priority,
        @Column(length = Column.UNBOUNDED) ItemStack[] items) {

    public KitRoomCategory {
        Objects.requireNonNull(id, "id");
        name = name == null || name.isBlank() ? id : name;
        items = items == null ? new ItemStack[0] : Arrays.stream(items)
                .filter(item -> item != null && !item.getType().isAir())
                .toArray(ItemStack[]::new);
    }

    /** The items, as a list of copies. */
    public @NotNull List<ItemStack> itemList() {
        return Arrays.stream(items).map(ItemStack::clone).toList();
    }

    /** What the tab is drawn as: its first item, or a chest while it is empty. */
    public @NotNull String icon() {
        return items.length == 0 ? "CHEST" : items[0].getType().name();
    }

    /** Whether an item is one of this category's, enchantments and all; the amount is ignored. */
    public boolean holds(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        for (ItemStack own : items) {
            if (own.isSimilar(item)) return true;
        }
        return false;
    }

    public @NotNull KitRoomCategory with(@NotNull String name, int priority, @NotNull List<ItemStack> items) {
        return new KitRoomCategory(id, name, priority, items.toArray(ItemStack[]::new));
    }
}
