package net.exylia.lib.item.internal;

import net.exylia.lib.schematic.internal.Nbt;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads an item's stored bytes, including bytes a newer server wrote.
 *
 * <p>{@code ItemStack.deserializeBytes} upgrades an item an older server wrote,
 * but refuses one with a newer data version outright: "server downgrades are not
 * supported". A network shares its database across servers, and the day one of
 * them runs a newer game, every item it stores — a backup, a snapshot, a queued
 * reward — reads as nothing on the others. That emptied every slot of every
 * staff restore written on a 26.x server and read on a 1.21.11 one.
 *
 * <p>The bytes are read again claiming this server's data version. An item whose
 * components this version also has comes back whole, which between neighbouring
 * versions is nearly all of them; one that uses something this version lacks is
 * refused by the game as before. Said once per server.
 */
@ApiStatus.Internal
public final class ItemBytes {

    private static final String DATA_VERSION = "DataVersion";
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private ItemBytes() {
        throw new AssertionError("No instances.");
    }

    /**
     * {@code ItemStack.deserializeBytes}, reading bytes from a newer server too.
     *
     * @param bytes what {@code serializeAsBytes} wrote
     * @return the item
     * @throws IllegalArgumentException when the bytes cannot be read, newer or not
     */
    public static @NotNull ItemStack read(byte @NotNull [] bytes) {
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (IllegalArgumentException refused) {
            int ours = dataVersion();
            byte[] lowered = lowered(bytes, ours);
            if (lowered == null) {
                throw refused;
            }
            ItemStack item = ItemStack.deserializeBytes(lowered);
            if (REPORTED.compareAndSet(false, true)) {
                java.util.logging.Logger.getLogger("ExyliaLib").warning("Items stored by a server on a newer"
                        + " game version are being read as this one's (data version " + ours + "). Whatever this version cannot read is still refused."
                        + " This is reported once.");
            }
            return item;
        }
    }

    @SuppressWarnings("deprecation") // UnsafeValues is the only place Bukkit says it.
    private static int dataVersion() {
        return Bukkit.getUnsafe().getDataVersion();
    }

    /**
     * The same item bytes claiming {@code version}, or {@code null} when they
     * do not claim a newer one or are not item bytes at all.
     */
    static byte @Nullable [] lowered(byte @NotNull [] bytes, int version) {
        try {
            Map<String, Object> root = Nbt.gunzip(bytes);
            if (!(root.get(DATA_VERSION) instanceof Integer written) || written <= version) {
                return null;
            }
            root.put(DATA_VERSION, version);
            return Nbt.gzip(root);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
