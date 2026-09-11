package net.exylia.lib.api.armorskin.event;

import net.exylia.lib.api.armorskin.ArmorPiece;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * A player is putting a skin item onto a piece of armor.
 *
 * <p>The drag in the inventory: a skin item held on the cursor and dropped on
 * armor. Fired once the plugin has agreed to it — the player may wear the skin,
 * it fits the piece, and the armor carries no skin yet — and before anything is
 * written, so {@link #armor()} is still the armor as it was.
 *
 * <p>Not fired for
 * {@link net.exylia.lib.api.armorskin.ArmorSkinService#apply(ItemStack, String)}:
 * a plugin writing a skin itself already knows it is doing so, and has no
 * player to name.
 *
 * <p>Cancelling leaves the armor unskinned and the skin item on the cursor, and
 * says nothing to the player: the handler that refused is the only one that
 * knows why.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread.
 *
 * @since 1.3.0
 */
public final class SkinApplyEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ItemStack armor;
    private final String skinId;
    private final ArmorPiece piece;
    private boolean cancelled;

    /**
     * @param player who is applying it
     * @param armor  the armor being skinned
     * @param skinId the skin going on
     * @param piece  the slot that armor is worn in
     */
    public SkinApplyEvent(@NotNull Player player, @NotNull ItemStack armor,
                          @NotNull String skinId, @NotNull ArmorPiece piece) {
        super(player, !Bukkit.isPrimaryThread());
        this.armor = armor;
        this.skinId = skinId;
        this.piece = piece;
    }

    /**
     * The armor about to be skinned.
     *
     * <p>The live item, not a copy: read it, do not change it. It is written
     * the moment this event is done.
     *
     * @return the armor
     */
    public @NotNull ItemStack armor() {
        return armor;
    }

    /**
     * The skin going on.
     *
     * @return the skin id, lowercase
     */
    public @NotNull String skinId() {
        return skinId;
    }

    /**
     * The slot the armor is worn in, whether or not it is being worn now.
     *
     * @return the armor slot
     */
    public @NotNull ArmorPiece piece() {
        return piece;
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
