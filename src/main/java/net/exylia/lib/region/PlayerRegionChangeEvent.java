package net.exylia.lib.region;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Batch notification fired after one player's region membership has been committed.
 *
 * <p>The event is not cancellable: movement has already been accepted and registry changes have
 * already published their immutable index. It is always fired on the thread owning the player.
 */
public final class PlayerRegionChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final RegionChangeCause cause;
    private final BlockPosition previous;
    private final BlockPosition current;
    private final List<RegionSnapshot> exited;
    private final List<RegionSnapshot> entered;
    private final long revision;

    public PlayerRegionChangeEvent(@NotNull Player player, @NotNull RegionChangeCause cause,
                                   @Nullable BlockPosition previous, @Nullable BlockPosition current,
                                   @NotNull List<RegionSnapshot> exited,
                                   @NotNull List<RegionSnapshot> entered, long revision) {
        this.player = Objects.requireNonNull(player, "player");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.previous = previous;
        this.current = current;
        this.exited = List.copyOf(Objects.requireNonNull(exited, "exited"));
        this.entered = List.copyOf(Objects.requireNonNull(entered, "entered"));
        this.revision = revision;
    }

    public @NotNull Player player() {
        return player;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    public @NotNull RegionChangeCause cause() {
        return cause;
    }

    public @NotNull RegionChangeCause getCause() {
        return cause;
    }

    public @NotNull Optional<BlockPosition> previous() {
        return Optional.ofNullable(previous);
    }

    public @NotNull Optional<BlockPosition> getPrevious() {
        return previous();
    }

    public @NotNull Optional<BlockPosition> current() {
        return Optional.ofNullable(current);
    }

    public @NotNull Optional<BlockPosition> getCurrent() {
        return current();
    }

    public @NotNull List<RegionSnapshot> exited() {
        return exited;
    }

    public @NotNull List<RegionSnapshot> getExited() {
        return exited;
    }

    public @NotNull List<RegionSnapshot> entered() {
        return entered;
    }

    public @NotNull List<RegionSnapshot> getEntered() {
        return entered;
    }

    public long revision() {
        return revision;
    }

    public long getRevision() {
        return revision;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
