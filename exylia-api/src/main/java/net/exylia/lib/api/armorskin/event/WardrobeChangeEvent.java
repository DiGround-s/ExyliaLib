package net.exylia.lib.api.armorskin.event;

import net.exylia.lib.api.armorskin.ArmorPiece;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Optional;
import java.util.Set;

/**
 * A player's wardrobe is about to change.
 *
 * <p>One event per write, however many slots it touches: dressing the full set
 * from the menu changes up to four slots at once, and every one of them changes
 * to the same thing — {@link #skinId()} going on, or nothing when skins are
 * coming off.
 *
 * <p>Fired for every way in: a click in the wardrobe, an admin command, and
 * another plugin calling
 * {@link net.exylia.lib.api.armorskin.ArmorSkinService#select(Player, ArmorPiece, String)}
 * or one of the clears. Only once the write is known to go through — the player
 * may wear the skin and something would actually change — so a handler never
 * sees a write that was going to be refused anyway.
 *
 * <p>Cancelling writes nothing and explains nothing: the wardrobe and the open
 * menu stay as they were, and the service call answers {@code false}. The
 * handler that refused is the one that knows why.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread.
 *
 * @since 1.3.0
 */
public final class WardrobeChangeEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Set<ArmorPiece> pieces;
    private final String skinId;
    private boolean cancelled;

    /**
     * @param player whose wardrobe it is
     * @param pieces the slots that change
     * @param skinId the skin going on, or {@code null} when skins come off
     */
    public WardrobeChangeEvent(@NotNull Player player, @NotNull Set<ArmorPiece> pieces,
                               @Nullable String skinId) {
        super(player, !Bukkit.isPrimaryThread());
        this.pieces = Set.copyOf(pieces);
        this.skinId = skinId;
    }

    /**
     * The slots that change. Never empty.
     *
     * @return the armor slots
     */
    public @NotNull @Unmodifiable Set<ArmorPiece> pieces() {
        return pieces;
    }

    /**
     * What every slot in {@link #pieces()} is about to wear.
     *
     * @return the skin id going on, or empty when the skins are coming off
     */
    public @NotNull Optional<String> skinId() {
        return Optional.ofNullable(skinId);
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
