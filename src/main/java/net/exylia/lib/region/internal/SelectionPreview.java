package net.exylia.lib.region.internal;

import net.exylia.lib.effect.Effects;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The box the player is picking, drawn while they pick it.
 *
 * <p>What the first corner looks like matters as much as what two corners look
 * like: one clicked corner draws as the single block it is, so an admin can see
 * they hit the block they meant before going looking for the other end.
 *
 * <h2>Why this is not a {@code RegionVisualization}</h2>
 * That one draws a region the server has, looked up by id on every frame so a
 * shrinking zone updates itself. A selection has no id and is not a region yet;
 * what it has is two corners that change under the viewer's feet. The shape is
 * therefore asked for per frame rather than resolved once, and the outline
 * sampler — the expensive half, and the cached one — is shared between them.
 *
 * <h2>It ends when the selection does</h2>
 * There is no duration. A preview belongs to a session and is cancelled by the
 * same release path that ends it, so a selection that is still open is still
 * drawn, and one that ended is not drawn one frame longer.
 */
final class SelectionPreview {

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final UUID playerId;
    private final Supplier<@Nullable RegionShape> shape;
    private final UUID worldId;
    private final SelectionOptions options;
    private volatile TaskHandle task;

    private SelectionPreview(UUID playerId, Supplier<@Nullable RegionShape> shape, UUID worldId,
                             SelectionOptions options) {
        this.playerId = playerId;
        this.shape = shape;
        this.worldId = worldId;
        this.options = options;
    }

    /**
     * Starts drawing, on the thread that owns the viewer.
     *
     * @param owner    the plugin whose scheduler runs the frames
     * @param player   who sees it
     * @param worldId  the world the corners are in
     * @param shape    what to draw, asked for again on every frame
     * @param options  the particle, spacing and period
     * @return the running preview, or {@code null} when previews are off
     */
    static @Nullable SelectionPreview start(@NotNull Plugin owner,
                                            @NotNull Player player,
                                            @NotNull UUID worldId,
                                            @NotNull Supplier<@Nullable RegionShape> shape,
                                            @NotNull SelectionOptions options) {
        if (!options.hasPreview()) {
            return null;
        }
        SelectionPreview preview =
                new SelectionPreview(player.getUniqueId(), shape, worldId, options);
        preview.attach(Tasks.of(owner).runAtEntityTimer(player, 1L, options.previewPeriodTicks(),
                preview::render));
        return preview;
    }

    private void attach(TaskHandle handle) {
        this.task = handle;
        if (!active.get()) {
            handle.cancel();
        }
    }

    private void render(TaskHandle timer) {
        if (!active.get()) {
            timer.cancel();
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        RegionShape current = shape.get();
        if (player == null || !player.isOnline() || current == null) {
            stop();
            return;
        }
        // Somebody who walked into another world is not looking at this box.
        // Their session is still theirs; there is simply nothing to draw.
        if (!player.getWorld().getUID().equals(worldId)) {
            return;
        }
        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            stop();
            return;
        }
        OutlineSampler.Outline outline = OutlineSampler.sample(current, options.previewSpacing());
        double dynamicY = player.getLocation().getBlockY() + 1.0;
        double[] coordinates = outline.coordinates();
        for (int offset = 0; offset < coordinates.length; offset += 3) {
            double y = outline.dynamicY() ? dynamicY : coordinates[offset + 1];
            Location location = new Location(world, coordinates[offset], y, coordinates[offset + 2]);
            Effects.particle(options.previewParticle()).at(location).show(player);
        }
    }

    /** Stops drawing. Calling it twice is harmless. */
    void stop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        TaskHandle current = task;
        if (current != null) {
            current.cancel();
        }
    }
}
