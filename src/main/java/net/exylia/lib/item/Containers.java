package net.exylia.lib.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The items inside an item: a shulker box through the block state it carries, a
 * bundle through its own list, so a screen or a rule does not have to care which
 * one it is looking at.
 *
 * <pre>{@code
 * if (Containers.isContainer(stack)) rows = Containers.contentsOf(stack);
 * ItemStack inner = Containers.resolve(kitItems, "6.13");
 * if (Containers.isBlocked(stack, settings.blockedMaterials())) refuse();
 * }</pre>
 *
 * <h2>Positions are a path</h2>
 * A box inside a box inside a list is {@code "6.13"}: position six of the list,
 * position thirteen of what was in it. That is all the state a preview screen
 * needs to carry, and going back up is dropping the last step.
 *
 * <p>Everything is read from the item itself, never from a block in the world,
 * so every method is safe on any thread.
 *
 * @since 1.276.0
 */
public final class Containers {

    private Containers() {
        throw new AssertionError("No instances.");
    }

    /** Whether this item is one that can hold others: a bundle or a shulker box. */
    public static boolean isContainer(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta) return true;
        return meta instanceof BlockStateMeta state && state.getBlockState() instanceof ShulkerBox;
    }

    /**
     * What is inside an item.
     *
     * <p>Positions are kept: an empty slot in the middle of a shulker box comes
     * back as {@code null} rather than closing the gap, because a preview draws
     * the box as its owner filled it.
     *
     * @param item the item
     * @return the contents, or an empty list when it holds nothing and is not a
     *         container at all
     */
    public static List<ItemStack> contentsOf(ItemStack item) {
        if (item == null) return List.of();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            // A bundle has no fixed slots: what it holds is a list, and the
            // order is the order things went in.
            return new ArrayList<>(bundle.getItems());
        }
        if (meta instanceof BlockStateMeta state && state.getBlockState() instanceof ShulkerBox box) {
            List<ItemStack> contents = new ArrayList<>(27);
            for (ItemStack stored : box.getInventory().getContents()) {
                contents.add(stored);
            }
            return contents;
        }
        return List.of();
    }

    /**
     * A copy of an item holding other contents, the item itself left alone.
     *
     * <p>A shulker box takes its first 27 entries slot by slot, {@code null} for
     * an empty one; a bundle takes the non-empty ones in order. Anything that is
     * not a container comes back as an unchanged copy.
     *
     * @param item     the container
     * @param contents what it should hold now
     * @return the copy, or {@code null} for a {@code null} item
     * @since 1.320.0
     */
    public static ItemStack withContents(ItemStack item, List<ItemStack> contents) {
        if (item == null) return null;
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            bundle.setItems(contents.stream().filter(stored -> stored != null && !stored.getType().isAir()).toList());
        } else if (meta instanceof BlockStateMeta state && state.getBlockState() instanceof ShulkerBox box) {
            ItemStack[] slots = new ItemStack[box.getInventory().getSize()];
            for (int slot = 0; slot < slots.length && slot < contents.size(); slot++) slots[slot] = contents.get(slot);
            box.getInventory().setContents(slots);
            state.setBlockState(box);
        } else {
            return copy;
        }
        copy.setItemMeta(meta);
        return copy;
    }

    /** Whether anything is inside, so a preview knows there is something to open. */
    public static boolean isFilled(ItemStack item) {
        return contentsOf(item).stream().anyMatch(stored -> stored != null && !stored.getType().isAir());
    }

    /** How many items are inside. */
    public static int count(List<ItemStack> contents) {
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) count++;
        }
        return count;
    }

    /**
     * The item a path points at.
     *
     * @param items the top-level items
     * @param path  the path, such as {@code "6"} or {@code "6.13"}
     * @return the item, or {@code null} when the path leads nowhere
     */
    public static ItemStack resolve(List<ItemStack> items, String path) {
        List<ItemStack> level = items;
        ItemStack found = null;
        for (String step : steps(path)) {
            int index;
            try {
                index = Integer.parseInt(step);
            } catch (NumberFormatException malformed) {
                return null;
            }
            if (index < 0 || index >= level.size()) return null;
            found = level.get(index);
            if (found == null) return null;
            level = contentsOf(found);
        }
        return found;
    }

    /** The path of what holds this one, empty at the top level. */
    public static String parentOf(String path) {
        String trimmed = path == null ? "" : path.trim();
        int last = trimmed.lastIndexOf('.');
        return last < 0 ? "" : trimmed.substring(0, last);
    }

    /** The path of a position inside another. */
    public static String child(String path, int index) {
        return path == null || path.isBlank() ? String.valueOf(index) : path + "." + index;
    }

    /** How deep a path goes, which is one for a top-level item. */
    public static int depth(String path) {
        return steps(path).size();
    }

    /** How deep {@link #isBlocked(ItemStack, Collection)} looks before refusing unread. */
    private static final int DEPTH = 16;

    /** Whether a material is on a list of material names, compared ignoring case. */
    public static boolean isBlocked(Material material, Collection<String> blocked) {
        for (String name : blocked) {
            if (name != null && name.equalsIgnoreCase(material.name())) return true;
        }
        return false;
    }

    /**
     * Whether a stack is on a list of material names, itself or through what it
     * carries.
     *
     * <p>A shulker box and a bundle are one item holding others, so a list that
     * only looked at the material in hand has a box-shaped hole in it. Bundles
     * nest, so the whole depth is read; something nested deeper than any real
     * item is refused rather than read further.
     *
     * @param stack   the stack; {@code null} is never blocked
     * @param blocked material names, any case
     */
    public static boolean isBlocked(ItemStack stack, Collection<String> blocked) {
        return stack != null && isBlocked(stack, blocked, DEPTH);
    }

    private static boolean isBlocked(ItemStack stack, Collection<String> blocked, int depth) {
        if (isBlocked(stack.getType(), blocked)) return true;
        if (!stack.hasItemMeta()) return false;
        for (ItemStack inside : carried(stack.getItemMeta())) {
            if (inside == null || inside.getType().isAir()) continue;
            if (depth <= 0 || isBlocked(inside, blocked, depth - 1)) return true;
        }
        return false;
    }

    /** What an item carries: any container block's slots, a bundle's items, or nothing. */
    private static List<ItemStack> carried(ItemMeta meta) {
        if (meta instanceof BlockStateMeta state && state.hasBlockState()
                && state.getBlockState() instanceof Container container) {
            return Arrays.asList(container.getInventory().getContents());
        }
        if (meta instanceof BundleMeta bundle) {
            return bundle.getItems();
        }
        return List.of();
    }

    /**
     * What to call an item on screen.
     *
     * <p>Its own name when it has one, and the material read as words when it
     * does not: {@code "Purple Shulker Box"} rather than
     * {@code PURPLE_SHULKER_BOX}.
     */
    public static String nameOf(ItemStack item) {
        if (item == null) return "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(name);
                if (!plain.isBlank()) return plain;
            }
        }
        return words(item.getType().name());
    }

    /** {@code PURPLE_SHULKER_BOX} as {@code Purple Shulker Box}. */
    private static String words(String constant) {
        StringBuilder name = new StringBuilder(constant.length());
        for (String word : constant.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return name.toString();
    }

    private static List<String> steps(String path) {
        if (path == null || path.isBlank()) return List.of();
        List<String> steps = new ArrayList<>(2);
        for (String step : path.trim().split("\\.")) {
            if (!step.isBlank()) steps.add(step.trim());
        }
        return steps;
    }
}
