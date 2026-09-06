package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.CosmeticKey;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A cosmetic that came off — by the player, by an admin, by a loadout being
 * put on over it, or because it stopped existing.
 *
 * <p>Not cancellable: by the time this runs the change is written. The key
 * rather than the cosmetic, because one reason for firing is that the
 * catalogue no longer has an entry to hand you.
 *
 * @since 1.0.0
 */
public final class CosmeticUnequipEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CosmeticKey key;

    /**
     * @param player who took it off
     * @param key    what came off
     */
    public CosmeticUnequipEvent(@NotNull Player player, @NotNull CosmeticKey key) {
        super(player);
        this.key = key;
    }

    /**
     * What came off.
     *
     * @return the cosmetic key
     */
    public @NotNull CosmeticKey key() {
        return key;
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
