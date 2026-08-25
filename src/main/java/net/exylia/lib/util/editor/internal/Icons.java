package net.exylia.lib.util.editor.internal;

import net.exylia.lib.item.Items;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Turning a name, an icon string and some lore into something clickable.
 *
 * <p>Every button in every editor is built here, so the look of the editors is
 * one class rather than a paragraph of {@code ItemMeta} in each screen.
 *
 * <h2>Italics are switched off, every time</h2>
 * Vanilla italicises any name or lore a plugin sets, so a palette's intent would
 * otherwise be rendered in a style nobody asked for.
 */
public final class Icons {

    private Icons() {
        throw new AssertionError("No instances.");
    }

    /**
     * A button with a fixed material.
     *
     * @param material what it is
     * @param name     the name, in Exylia text notation
     * @param lore     the lore lines, in Exylia text notation
     * @return the item
     */
    public static @NotNull ItemStack button(@NotNull Material material, @NotNull String name,
                                            @NotNull List<String> lore) {
        return write(new ItemStack(material), name, lore, false);
    }

    /**
     * A button that stands out: same thing, with the enchantment glint.
     *
     * @param material what it is
     * @param name     the name
     * @param lore     the lore lines
     * @return the item
     */
    public static @NotNull ItemStack glowing(@NotNull Material material, @NotNull String name,
                                             @NotNull List<String> lore) {
        return write(new ItemStack(material), name, lore, true);
    }

    /**
     * A row, drawn from whatever a descriptor said its icon was.
     *
     * <p>The grammar is the item module's: a material name, a head string, or a
     * {@code bytes:} snapshot. An icon that names nothing draws as paper rather
     * than as a hole in the page — a row nobody can see is a row nobody can
     * delete.
     *
     * @param icon the icon source
     * @param name the name
     * @param lore the lore lines
     * @return the item
     */
    public static @NotNull ItemStack row(@NotNull String icon, @NotNull String name,
                                         @NotNull List<String> lore) {
        return row(icon, name, lore, false);
    }

    /**
     * The same, drawn with the glint when it is the one that matters.
     *
     * @param icon the icon source
     * @param name the name
     * @param lore the lore lines
     * @param glow whether to add the enchantment glint
     * @return the item
     */
    public static @NotNull ItemStack row(@NotNull String icon, @NotNull String name,
                                         @NotNull List<String> lore, boolean glow) {
        return write(base(icon), name, lore, glow);
    }

    /**
     * Builds the object an icon string names, before anything is written on it.
     *
     * @param icon the icon source
     * @return the item; never {@code null}
     */
    public static @NotNull ItemStack base(@NotNull String icon) {
        return Items.icon(icon);
    }

    private static ItemStack write(ItemStack item, String name, List<String> lore, boolean glow) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plain(name));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>(lore.size());
            for (String line : lore) {
                lines.add(plain(line));
            }
            meta.lore(lines);
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(@Nullable String text) {
        return Text.of(text == null ? "" : text).build()
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
