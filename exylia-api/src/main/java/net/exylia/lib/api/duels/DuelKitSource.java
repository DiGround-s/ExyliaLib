package net.exylia.lib.api.duels;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Kits another plugin offers for ExyliaDuels' kit duels: listed on the
 * challenge screen next to the server's own kits, and put on both players when
 * the duel starts.
 *
 * <pre>{@code
 * getServer().getServicesManager().register(DuelKitSource.class, new RandomKitSource(),
 *         plugin, ServicePriority.Normal);
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} by the
 * plugin that owns the kits, never unregistered by hand: Bukkit drops it when
 * that plugin disables. ExyliaDuels reads every registration, so any number of
 * sources can sit side by side, each under its own {@link #id()}. A network
 * duel is fought on the arena server: a source that is not installed there
 * simply is not offered for it.
 *
 * <p>Both players of a duel are equipped with the same {@code seed}, one call
 * each, so a source that rolls something random rolls it from the seed and
 * both get the same kit, even on two different calls or servers.
 *
 * @since 3.2.0
 */
public interface DuelKitSource {

    /**
     * The source's id: lower case letters, digits and {@code _}, unique on the
     * server. A duel's kit is written {@code <source>:<kit>}.
     *
     * @return the id
     */
    @NotNull
    String id();

    /**
     * The kits a challenge may pick from this source now. Asked when the
     * challenge screen draws and when a duel is accepted: read memory.
     *
     * @param viewer who is choosing, for a source that translates its names
     * @return the kits, possibly none
     */
    @NotNull
    List<Kit> kits(@Nullable Player viewer);

    /**
     * Puts the kit on one player of a duel, replacing nothing: the player's
     * inventory has just been emptied. Called on the player's own thread,
     * once per player, with the same seed for both.
     *
     * @param player who to dress
     * @param kit    one of {@link #kits}' ids
     * @param seed   the duel's seed, the same for both players
     * @return whether the player is dressed; {@code false} calls the duel off
     */
    boolean equip(@NotNull Player player, @NotNull String kit, long seed);

    /**
     * A duel fought with one of this source's kits ended with a winner. On
     * the global thread; the default does nothing.
     *
     * @param kit    the kit's id
     * @param seed   the duel's seed
     * @param winner who won
     * @param loser  who lost
     */
    default void finished(@NotNull String kit, long seed, @NotNull UUID winner, @NotNull UUID loser) {
    }

    /**
     * A kit a challenge can pick.
     *
     * @param id          unique within the source
     * @param name        what the challenge screen shows, palette tokens allowed
     * @param icon        anything a menu's {@code material:} accepts
     * @param description body lines for the kit's card, palette tokens allowed
     */
    record Kit(@NotNull String id, @NotNull String name, @NotNull String icon, @NotNull List<String> description) {

        public Kit {
            description = List.copyOf(description);
        }
    }
}
