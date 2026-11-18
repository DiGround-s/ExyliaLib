package net.exylia.lib.session;

import net.exylia.lib.session.internal.TransitHolds;
import net.exylia.lib.util.snapshot.internal.PlayerState;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Keeps a player's hands off their items while they change from one place or
 * mode to another.
 *
 * <p>Between leaving one mode and arriving in the next, a player is protected
 * by neither: the lobby has let go of them and the arena has not dressed them
 * yet, or the arena has let go and the lobby has not given its hotbar back.
 * Anything dropped, picked up, parked on the cursor or in the crafting grid, or
 * used in that window ends up in the wrong mode. While a player is in transit
 * the library refuses drops, pickups, item use and clicks in their own
 * inventory (library menus keep working).
 *
 * <p>A transit lasts one second, stretched by every library teleport that
 * starts during it until one second after that teleport lands, and never
 * longer than ten seconds. Saving or restoring a {@code Snapshots} snapshot
 * begins one on its own; a plugin swapping a player's things without a
 * snapshot calls {@link #begin} where its protection stops. Since 1.622.0.
 */
public final class Transits {

    private Transits() {
    }

    /**
     * Starts or stretches a player's transit.
     *
     * @param player the player
     */
    public static void begin(@NotNull Player player) {
        TransitHolds.begin(player.getUniqueId());
    }

    /**
     * Starts or stretches a player's transit.
     *
     * @param player the player's id
     */
    public static void begin(@NotNull UUID player) {
        TransitHolds.begin(player);
    }

    /**
     * Empties what a player holds outside their inventory slots: the cursor
     * and the 2x2 crafting grid. {@code PlayerInventory#clear} reaches
     * neither, so a plugin dressing a player for a new mode without a snapshot
     * calls this too, or what was parked there comes along. Call it on the
     * player's own thread.
     *
     * @param player the player
     * @since 1.622.0
     */
    public static void clearLoose(@NotNull Player player) {
        player.setItemOnCursor(null);
        PlayerState.emptyCraftingGrid(player, false);
    }

    /**
     * Whether a player is in transit now.
     *
     * @param player the player's id
     * @return whether their items are held
     */
    public static boolean isHeld(@NotNull UUID player) {
        return TransitHolds.isHeld(player);
    }
}
