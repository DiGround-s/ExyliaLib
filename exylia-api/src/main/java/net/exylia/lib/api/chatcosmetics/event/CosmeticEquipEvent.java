package net.exylia.lib.api.chatcosmetics.event;

import net.exylia.lib.api.chatcosmetics.Cosmetic;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A cosmetic about to be worn.
 *
 * <p>Cancel to refuse it. The plugin tells the player nothing when you do —
 * the equip simply reports that it was cancelled — so say something yourself
 * if they need to know why.
 *
 * <p>Fired on the player's thread, after ownership was checked and before
 * anything is written.
 *
 * @since 1.0.0
 */
public final class CosmeticEquipEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Cosmetic cosmetic;
    private boolean cancelled;

    /**
     * @param player   who is putting it on
     * @param cosmetic what they are putting on
     */
    public CosmeticEquipEvent(@NotNull Player player, @NotNull Cosmetic cosmetic) {
        super(player);
        this.cosmetic = cosmetic;
    }

    /**
     * What is being put on.
     *
     * @return the cosmetic
     */
    public @NotNull Cosmetic cosmetic() {
        return cosmetic;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
