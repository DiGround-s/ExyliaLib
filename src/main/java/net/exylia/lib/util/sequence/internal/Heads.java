package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.skull.Skulls;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

/**
 * The heads a display effect can wear.
 *
 * <p>Only the two forms that cost nothing to resolve. A texture written in the
 * file is turned into an item when the file is read; a face belonging to
 * somebody on the server is read from the profile that is already in memory.
 *
 * <p>Deliberately not player names: that is a network lookup, and an effect
 * that has to wait for Mojang before it can be drawn is an effect that plays
 * after the body has stopped falling.
 */
final class Heads {

    private Heads() {
    }

    /**
     * A head carrying a texture, built once when the file is read.
     *
     * @param texture the base64 texture value
     * @return the item
     */
    static @NotNull ItemStack textured(@NotNull String texture) {
        return Skulls.texture(texture).item();
    }

    /** A plain, faceless head, for a file that named neither a texture nor a player. */
    static @NotNull ItemStack blank() {
        return new ItemStack(Material.PLAYER_HEAD);
    }

    /**
     * The same model, wearing a player's face.
     *
     * <p>Their profile is already loaded because they are on the server, so
     * this resolves inline. A head that cannot be built is left as it was:
     * losing the face is a worse-looking effect, losing the effect is a bug.
     *
     * @param model  the head model the file described
     * @param wearer whose face
     * @return the model wearing it, or the original when it could not be built
     */
    static @NotNull DisplayModel wearing(@NotNull DisplayModel model, @NotNull Player wearer) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return model;
        }
        meta.setOwningPlayer(wearer);
        head.setItemMeta(meta);
        return model.showing(head);
    }
}
