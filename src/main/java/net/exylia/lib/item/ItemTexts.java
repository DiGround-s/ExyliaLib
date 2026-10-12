package net.exylia.lib.item;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * An item as words: for a chat line, a log row or a lore entry about an item.
 *
 * <pre>{@code
 * values.putText("item", ItemTexts.describe(stack));    // "Diamond sword", or its anvil name
 * log("sold " + ItemTexts.summary(stack));              // 2x "Big Box" Purple shulker box{1x Diamond}
 * ItemTexts.enchantments(book);                          // a book's stored enchantments
 * }</pre>
 *
 * <p>A named stack answers with what an anvil was given, which is a player's
 * words: put it into a line as literal text ({@code Values.putText}), never as
 * parsed text.
 *
 * <p>Every method reads only the item, so it is safe on any thread.
 *
 * @since 1.276.0
 */
public final class ItemTexts {

    /** How many containers deep a {@link #summary} lists what is inside. */
    private static final int DEPTH = 2;

    private ItemTexts() {
        throw new AssertionError("No instances.");
    }

    /**
     * What a stack is called: its display name as plain text, or its material
     * read as a sentence, {@code DIAMOND_SWORD} as {@code Diamond sword}.
     */
    public static String describe(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(stack.getItemMeta().displayName());
        }
        String name = stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * What to call an item on screen: its own name when it has a non-blank one,
     * and the material as title-cased words when not, {@code Purple Shulker Box}.
     *
     * @param item the item; {@code null} is an empty string
     */
    public static String nameOf(ItemStack item) {
        return Containers.nameOf(item);
    }

    /**
     * The enchantments a stack carries: its own, or the ones a book of
     * enchantments stores.
     */
    public static Map<Enchantment, Integer> enchantments(ItemStack stack) {
        if (stack.getItemMeta() instanceof EnchantmentStorageMeta stored) return stored.getStoredEnchants();
        return stack.getEnchantments();
    }

    /** An enchantment as a line says it: {@code fire_aspect} is {@code Fire aspect}. */
    public static String enchantmentName(Enchantment enchantment) {
        String key = enchantment.getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(key.charAt(0)) + key.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * One stack as a record of what it was: amount, anvil name, material,
     * enchantments and, two containers deep, what it carries:
     * {@code 1x "Loot" Shulker Box{3x Diamond, 1x Diamond Sword[Sharpness 5]}}.
     */
    public static String summary(ItemStack stack) {
        return stack == null ? "" : summary(stack, stack.getAmount(), DEPTH);
    }

    /** {@link #summary(ItemStack)} for an amount other than the stack's own. */
    public static String summary(ItemStack template, int amount) {
        return template == null ? "" : summary(template, amount, DEPTH);
    }

    /** Several stacks, joined with commas; air and {@code null} are skipped. */
    public static String summary(Collection<ItemStack> stacks) {
        return summary(stacks, DEPTH);
    }

    private static String summary(Collection<ItemStack> stacks, int depth) {
        if (stacks == null) return "";
        StringJoiner joined = new StringJoiner(", ");
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir()) continue;
            joined.add(summary(stack, stack.getAmount(), depth));
        }
        return joined.toString();
    }

    private static String summary(ItemStack stack, int amount, int depth) {
        StringBuilder text = new StringBuilder().append(amount).append("x ");
        ItemMeta meta = stack.getItemMeta();
        String material = Containers.nameOf(new ItemStack(stack.getType()));
        if (meta != null && meta.hasDisplayName()) {
            text.append('"').append(Containers.nameOf(stack)).append("\" ");
        }
        text.append(material);
        Map<Enchantment, Integer> enchantments = enchantments(stack);
        if (!enchantments.isEmpty()) {
            StringJoiner list = new StringJoiner(", ", "[", "]");
            enchantments.forEach((enchantment, level) -> list.add(enchantmentName(enchantment) + " " + level));
            text.append(list);
        }
        if (depth > 0 && Containers.isContainer(stack)) {
            String inside = summary(Containers.contentsOf(stack), depth - 1);
            if (!inside.isEmpty()) text.append('{').append(inside).append('}');
        }
        return text.toString();
    }
}
