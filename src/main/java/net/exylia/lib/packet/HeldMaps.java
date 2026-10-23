package net.exylia.lib.packet;

import net.exylia.lib.packet.internal.HeldMapPackets;
import net.exylia.lib.packet.internal.PacketRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A map in a player's hand that the server does not have: a QR code to scan,
 * a picture, a one-off chart.
 *
 * <pre>{@code
 * HeldMaps.show(player, QrCodes.mapPixels(uri));  // the map replaces what they hold, on their screen only
 * ...
 * HeldMaps.hide(player);                          // their real item is drawn again
 * }</pre>
 *
 * <p>Nothing is written to the inventory or the world: no map file, no item a
 * crash or a drop could leave behind. The client is sent the picture and told
 * the held slot holds a filled map; the next time the server sends that slot
 * (an item used, a hotbar scroll and back, {@link #hide}) the real item comes
 * back. Show it to somebody who stands still, such as a player being asked
 * for a code.
 *
 * <p>Needs PacketEvents: without it both methods do nothing. Safe from any
 * thread for {@link #show}; {@link #hide} must run on the player's thread
 * (it calls {@link Player#updateInventory()}).
 *
 * @since 1.352.0
 */
public final class HeldMaps {

    /** The map id the client files the picture under, far above any real map. */
    static final int MAP_ID = 2_000_000_000;

    private HeldMaps() {
    }

    /**
     * Draws a 128×128 picture on a map in the player's main hand.
     *
     * @param pixels map colour ids, row by row, 16384 of them (see {@code QrCodes.mapPixels})
     */
    public static void show(@NotNull Player player, byte @NotNull [] pixels) {
        if (pixels.length != 128 * 128) {
            throw new IllegalArgumentException("A map is 128x128 pixels, got " + pixels.length);
        }
        if (PacketRuntime.isAvailable()) {
            HeldMapPackets.show(player, MAP_ID, player.getInventory().getHeldItemSlot(), pixels);
        }
    }

    /** Draws the player's real inventory again. */
    public static void hide(@NotNull Player player) {
        player.updateInventory();
    }
}
