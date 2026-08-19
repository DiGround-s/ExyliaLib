package net.exylia.lib.util.reward.internal;

import net.exylia.lib.item.Source;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.Map;

/**
 * Putting an item in a player's hands.
 *
 * <p>The one seam in the module that needs a running server. Behind it,
 * everything the reward runtime does &mdash; rolling, ordering, overflow,
 * queueing &mdash; is decided in terms of a snapshot string and a count, which is
 * why all of it can be tested without one.
 *
 * <p>Lives in {@code internal} rather than the public API: a plugin has no
 * business replacing how an item reaches a player, and a test does.
 */
public interface ItemGiver {

    /** What {@link #give} returns when the snapshot names nothing it can build. */
    int UNREADABLE = -1;

    /**
     * Gives a player some of an item.
     *
     * @param player   who gets it
     * @param snapshot the item, as a reward stores it
     * @param amount   how many
     * @return how many did not fit, {@code 0} if they all did, or
     *         {@link #UNREADABLE} if the snapshot names nothing
     */
    int give(@NotNull Player player, @NotNull String snapshot, int amount);

    /**
     * Drops some of an item at a player's feet.
     *
     * @param player   whose feet
     * @param snapshot the item
     * @param amount   how many
     */
    void drop(@NotNull Player player, @NotNull String snapshot, int amount);

    /** The real one. */
    ItemGiver BUKKIT = new ItemGiver() {

        @Override
        public int give(@NotNull Player player, @NotNull String snapshot, int amount) {
            ItemStack item = build(snapshot);
            if (item == null) {
                return UNREADABLE;
            }
            item.setAmount(amount);
            return hand(player, item);
        }

        @Override
        public void drop(@NotNull Player player, @NotNull String snapshot, int amount) {
            ItemStack item = build(snapshot);
            if (item == null) {
                return;
            }
            item.setAmount(amount);
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    };

    /**
     * Puts an item in an inventory and says what did not fit.
     *
     * <p>The single line ExyliaCommons got wrong: {@code addItem} hands back
     * what would not go in, and commons discarded that map, so an item a player
     * had no room for was destroyed with nobody told. Its own method so a test
     * can prove the leftovers are read without needing a real material.
     *
     * @param player who is receiving it
     * @param item   the item, already the right size
     * @return how many did not fit, {@code 0} if they all did
     */
    static int hand(@NotNull Player player, @NotNull ItemStack item) {
        Map<Integer, ItemStack> leftOver = player.getInventory().addItem(item);
        int remaining = 0;
        for (ItemStack rest : leftOver.values()) {
            remaining += rest.getAmount();
        }
        return remaining;
    }

    /**
     * Rebuilds an item from what a reward stored.
     *
     * <p>Reads the same grammar the item module reads, which is the same one
     * ExyliaCommons wrote: a {@code bytes:} snapshot, a head, or a material.
     *
     * @param snapshot the stored form
     * @return the item, or {@code null} if the string names nothing
     */
    static @Nullable ItemStack build(@NotNull String snapshot) {
        Source source = Source.of(snapshot);
        if (source instanceof Source.OfSnapshot bytes) {
            try {
                return ItemStack.deserializeBytes(Base64.getDecoder().decode(bytes.base64()));
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
        if (source instanceof Source.OfMaterial material) {
            Material type = Material.matchMaterial(material.raw());
            return type == null ? null : new ItemStack(type);
        }
        // A head. The skull module owns turning one of those into an item and
        // the item module is how a caller gets there; a reward that stores a
        // head is drawn far more often than it is given.
        return new ItemStack(Material.PLAYER_HEAD);
    }

    /**
     * Serialises an item the way a reward stores one.
     *
     * <p>Byte-identical to ExyliaCommons' {@code ItemSnapshot.from(ItemStack)}:
     * the same {@code serializeAsBytes} under the same standard Base64 under the
     * same {@code bytes:} prefix, down to the {@code "AIR"} it wrote for nothing.
     *
     * @param item the item
     * @return the stored form
     */
    static @NotNull String snapshot(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "AIR";
        }
        try {
            return "bytes:" + Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (RuntimeException unwritable) {
            return item.getType().name();
        }
    }
}
