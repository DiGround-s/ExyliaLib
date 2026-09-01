package net.exylia.lib.util.head;

import net.exylia.lib.skull.Skulls;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * One decorative head from the catalogue.
 *
 * <p>What is kept is the least that can draw it and store it: a name to search
 * by, the texture hash to render, and the category it came from. The base64
 * texture value the API also answers with is deliberately dropped — it is the
 * same skin written the long way, and holding a few hundred of those is the
 * difference between a page of heads and a page of heads plus a hundred
 * kilobytes nobody reads.
 *
 * @param id       the catalogue id
 * @param name     what the head is called
 * @param texture  the texture hash, as {@code textures.minecraft.net} names it
 * @param category the catalogue section it belongs to
 * @since 1.82.0
 */
public record Head(int id, @NotNull String name, @NotNull String texture, @NotNull String category) {

    /**
     * This head as a {@code material} value.
     *
     * <p>The {@code urlhead-} form rather than {@code basehead-}: it is the same
     * skin in seventy characters instead of four hundred, it fits any column an
     * icon is stored in, and it is wrapped into a texture locally, so a head
     * saved today still draws when the catalogue is unreachable.
     *
     * @return the icon string
     */
    public @NotNull String icon() {
        return "urlhead-" + texture;
    }

    /**
     * This head, drawn.
     *
     * <p>Free: a texture hash needs no lookup, so a page of these costs one item
     * allocation each and no network at all.
     *
     * @return the item
     */
    public @NotNull ItemStack item() {
        return Skulls.url(texture).item();
    }
}
