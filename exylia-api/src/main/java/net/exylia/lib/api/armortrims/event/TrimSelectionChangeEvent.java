package net.exylia.lib.api.armortrims.event;

import net.exylia.lib.api.armortrims.ArmorPiece;
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
 * The trims a player chose are about to change.
 *
 * <p>One event per write, however many slots it touches: engraving the full set
 * from the menu changes up to four slots at once, and every one of them changes
 * to the same thing — {@link #trimId()} going on, or nothing when trims are
 * coming off.
 *
 * <p>Fired for every way in: a click in the menu, an admin command, and another
 * plugin calling
 * {@link net.exylia.lib.api.armortrims.ArmorTrimService#select(Player, ArmorPiece, String)}
 * or one of the clears. Only once the write is known to change something, so a
 * handler never sees a write that was going to be refused anyway.
 *
 * <p>Cancelling writes nothing and explains nothing: the selection and the open
 * menu stay as they were, and the service call answers {@code false}. The
 * handler that refused is the one that knows why.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread.
 *
 * @since 1.3.0
 */
public final class TrimSelectionChangeEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Set<ArmorPiece> pieces;
    private final String trimId;
    private boolean cancelled;

    /**
     * @param player whose selection it is
     * @param pieces the slots that change
     * @param trimId the trim going on, or {@code null} when trims come off
     */
    public TrimSelectionChangeEvent(@NotNull Player player, @NotNull Set<ArmorPiece> pieces,
                                    @Nullable String trimId) {
        super(player, !Bukkit.isPrimaryThread());
        this.pieces = Set.copyOf(pieces);
        this.trimId = trimId;
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
     * @return the trim id going on — a preset id or a {@code pattern/material}
     *         combination — or empty when the trims are coming off
     */
    public @NotNull Optional<String> trimId() {
        return Optional.ofNullable(trimId);
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
