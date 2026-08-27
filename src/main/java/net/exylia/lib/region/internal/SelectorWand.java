package net.exylia.lib.region.internal;

import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.text.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handing a player the tool, and taking it back.
 *
 * <p>The one part of starting a selection that needs a running server, so it is
 * a seam: everything the session decides — corners, confirmation, what it tells
 * the player — is tested without one.
 *
 * <h2>The main hand, always</h2>
 * The selector goes where the player is already looking, so nobody has to hunt
 * their hotbar for it. Whatever was in that hand is moved to a free slot; an
 * inventory with no free slot loses it, which is the trade this was asked for —
 * a selection that silently did not arrive is worse than a stack an admin can
 * get back, and admins are who selects.
 *
 * <h2>Which item is ours is written on it</h2>
 * The wand carries the owning plugin's name in its persistent data, so taking it
 * back removes the one we handed over and never the golden axe the player
 * already had.
 */
public interface SelectorWand {

    /**
     * Builds the selector for a session.
     *
     * @param owner   the plugin the selection belongs to
     * @param options what it should look like
     * @return the item
     */
    @NotNull ItemStack build(@NotNull Plugin owner, @NotNull SelectionOptions options);

    /**
     * Puts the selector in the player's main hand.
     *
     * @param player  who gets it
     * @param wand    the item
     * @return the slot it went into
     */
    int give(@NotNull Player player, @NotNull ItemStack wand);

    /**
     * Takes back every selection axe the player is carrying.
     *
     * <p>Every one, not only the one this session handed over. A selector that
     * outlived its session — a crash, a restart, a plugin that went away with a
     * screen open — is rubbish in an admin's inventory, and the player has at
     * most one selection at a time across the whole server, so nothing anybody
     * is still using can be swept up by this.
     *
     * @param player who has them
     * @return how many stacks were removed
     */
    int take(@NotNull Player player);

    /** The real one. */
    SelectorWand BUKKIT = new SelectorWand() {

        @Override
        public @NotNull ItemStack build(@NotNull Plugin owner, @NotNull SelectionOptions options) {
            ItemStack wand = new ItemStack(options.selectorMaterial());
            ItemMeta meta = wand.getItemMeta();
            if (meta == null) {
                return wand;
            }
            meta.displayName(Text.from(owner, options.selectorName()).build());
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(options.selectorLore().size());
            for (String line : options.selectorLore()) {
                lore.add(Text.from(owner, line).build());
            }
            meta.lore(lore);

            // An admin tool should not wear out, and should not look enchanted
            // either. The glint is the only thing the enchantment is for.
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            Enchantment glint = glint();
            if (glint != null) {
                meta.addEnchant(glint, 1, true);
            }
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            wand.setItemMeta(meta);
            return wand;
        }

        @Override
        public int give(@NotNull Player player, @NotNull ItemStack wand) {
            var inventory = player.getInventory();
            int held = inventory.getHeldItemSlot();
            ItemStack displaced = inventory.getItem(held);
            inventory.setItem(held, wand);
            if (isEmpty(displaced)) {
                return held;
            }
            int free = inventory.firstEmpty();
            if (free >= 0) {
                inventory.setItem(free, displaced);
            }
            // free < 0 destroys it. Asked for deliberately: the selector has to
            // be in the hand, and an inventory with no room has nowhere else to
            // put what was there.
            return held;
        }

        @Override
        public int take(@NotNull Player player) {
            Inventory inventory = player.getInventory();
            int removed = 0;
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (isWand(inventory.getItem(slot))) {
                    inventory.setItem(slot, null);
                    removed++;
                }
            }
            return removed;
        }
    };

    /**
     * Whether an item is one of the library's selection axes.
     *
     * @param item the item, possibly nothing
     * @return whether the library handed it out
     */
    static boolean isWand(@Nullable ItemStack item) {
        if (isEmpty(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
    }

    /**
     * The mark every selection axe carries.
     *
     * <p>The library's own key rather than the owning plugin's, deliberately: a
     * selector left behind by a plugin that is no longer running still has to be
     * recognisable as rubbish by whichever plugin sweeps next. A player has one
     * selection at a time across the whole server, so a single key cannot
     * confuse two live sessions.
     */
    NamespacedKey KEY = Objects.requireNonNull(
            NamespacedKey.fromString("exylialib:region_selector"), "selector key");

    /**
     * Whether a slot holds nothing.
     *
     * <p>Compared against the three air constants rather than asked
     * {@code isAir()}: that answer comes from the block registry, which only a
     * running server has, and an inventory helper that cannot be tested without
     * one is how the slot logic went unchecked in the first place. The same
     * trap {@link net.exylia.lib.item.Source} documents.
     */
    private static boolean isEmpty(@Nullable ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }
        Material type = item.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    /**
     * The enchantment that only makes the item shine.
     *
     * <p>Looked up through the registry rather than the constant: enchantments
     * stopped being an enum in 1.21 and the old field is gone on some builds.
     */
    @SuppressWarnings("deprecation")
    private static @Nullable Enchantment glint() {
        try {
            return org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }
}
