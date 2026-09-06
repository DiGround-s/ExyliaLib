package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.Loadout;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A saved loadout was put on.
 *
 * <p>Fired last, after every cosmetic in it fired its own equip event, so a
 * listener that wants to know what the player ended up wearing should read it
 * here rather than count equips.
 *
 * @since 1.0.0
 */
public final class LoadoutAppliedEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Loadout loadout;
    private final int skipped;

    /**
     * @param player  who put it on
     * @param loadout what they put on
     * @param skipped how many of its cosmetics were left off
     */
    public LoadoutAppliedEvent(@NotNull Player player, @NotNull Loadout loadout, int skipped) {
        super(player);
        this.loadout = loadout;
        this.skipped = skipped;
    }

    /**
     * The loadout that was applied.
     *
     * @return the loadout
     */
    public @NotNull Loadout loadout() {
        return loadout;
    }

    /**
     * How many of its cosmetics the player no longer owns and were left off.
     *
     * @return the number skipped
     */
    public int skipped() {
        return skipped;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return the handler list Bukkit registers against
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
