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
 *
 * <h2>The lists carry every plugin's regions, so filter before acting</h2>
 * One step can leave one plugin's region and enter another's, and both arrive
 * in the same event. Acting on {@link #exited()} without checking who owns each
 * region is how a player who walked out of somebody else's region gets killed
 * by a game they were never in:
 *
 * <pre>{@code
 * @EventHandler
 * public void onRegionChange(PlayerRegionChangeEvent event) {
 *     for (RegionSnapshot left : event.exited(regions)) {
 *         arenas.get(left.id()).ifPresent(arena -> arena.eliminate(event.player()));
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #exited(PluginRegions)} and {@link #entered(PluginRegions)} return
 * only the regions that plugin registered. They are the ones to reach for; the
 * unfiltered lists are there for the rare listener that genuinely wants to see
 * the whole server's movement.
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

    /**
     * The regions this plugin owns that the player just left.
     *
     * @param regions the plugin's region registry
     * @return its own exited regions, empty when none of them were
     */
    public @NotNull List<RegionSnapshot> exited(@NotNull PluginRegions regions) {
        return ownedBy(exited, regions.namespace());
    }

    /**
     * The regions this plugin owns that the player just entered.
     *
     * @param regions the plugin's region registry
     * @return its own entered regions, empty when none of them were
     */
    public @NotNull List<RegionSnapshot> entered(@NotNull PluginRegions regions) {
        return ownedBy(entered, regions.namespace());
    }

    /**
     * Whether any of this plugin's regions changed at all.
     *
     * <p>For a listener that would otherwise walk two lists to find out it has
     * nothing to do, which is most of them: a player crossing a border is
     * usually crossing somebody else's.
     *
     * @param regions the plugin's region registry
     * @return {@code true} when it entered or left one of them
     */
    public boolean involves(@NotNull PluginRegions regions) {
        String namespace = regions.namespace();
        return anyOwnedBy(entered, namespace) || anyOwnedBy(exited, namespace);
    }

    private static @NotNull List<RegionSnapshot> ownedBy(List<RegionSnapshot> regions,
                                                         String namespace) {
        if (!anyOwnedBy(regions, namespace)) {
            // The common case by a wide margin: no copy, no allocation.
            return List.of();
        }
        List<RegionSnapshot> mine = new java.util.ArrayList<>(regions.size());
        for (RegionSnapshot region : regions) {
            if (region.id().namespace().equals(namespace)) {
                mine.add(region);
            }
        }
        return List.copyOf(mine);
    }

    private static boolean anyOwnedBy(List<RegionSnapshot> regions, String namespace) {
        for (RegionSnapshot region : regions) {
            if (region.id().namespace().equals(namespace)) {
                return true;
            }
        }
        return false;
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
