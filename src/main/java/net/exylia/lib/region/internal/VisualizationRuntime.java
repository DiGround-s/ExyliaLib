package net.exylia.lib.region.internal;

import net.exylia.lib.effect.Effects;
import net.exylia.lib.region.RegionId;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.region.RegionVisualization;
import net.exylia.lib.region.VisualizationOptions;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared lifecycle and render runtime for region visualizations. */
public final class VisualizationRuntime {

    private static final ConcurrentMap<UUID, Visualization> ACTIVE = new ConcurrentHashMap<>();

    private VisualizationRuntime() {
    }

    /** Starts an owner-scoped visualization using an entity-owned timer. */
    public static @NotNull RegionVisualization start(@NotNull Plugin ownerPlugin,
                                                      @NotNull Player player,
                                                      @NotNull RegionId regionId,
                                                      @NotNull VisualizationOptions options) {
        Objects.requireNonNull(ownerPlugin, "ownerPlugin");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(options, "options");

        Visualization visualization = new Visualization(UUID.randomUUID(), ownerPlugin.getName(),
                player.getUniqueId(), regionId, options);
        ACTIVE.put(visualization.handleId, visualization);
        TaskHandle task = Tasks.of(ownerPlugin).runAtEntityTimer(player, 1L, options.periodTicks(),
                visualization::render);
        visualization.attach(task);
        return visualization;
    }

    /** Stops every visualization belonging to one exact plugin owner. */
    public static int release(@NotNull String owner) {
        Objects.requireNonNull(owner, "owner");
        int count = 0;
        for (Visualization visualization : ACTIVE.values()) {
            if (visualization.owner.equals(owner) && visualization.stop()) {
                count++;
            }
        }
        return count;
    }

    /** Stops every visualization for a player who is leaving. */
    public static int forget(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        int count = 0;
        for (Visualization visualization : ACTIVE.values()) {
            if (visualization.playerId.equals(playerId) && visualization.stop()) {
                count++;
            }
        }
        return count;
    }

    /** Stops every active visualization. */
    public static void releaseAll() {
        for (Visualization visualization : ACTIVE.values()) {
            visualization.stop();
        }
        ACTIVE.clear();
    }

    static int activeCount() {
        return ACTIVE.size();
    }

    private static final class Visualization implements RegionVisualization {

        private final UUID handleId;
        private final String owner;
        private final UUID playerId;
        private final RegionId regionId;
        private final VisualizationOptions options;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile TaskHandle task;
        private long elapsedTicks;

        private Visualization(UUID handleId, String owner, UUID playerId, RegionId regionId,
                              VisualizationOptions options) {
            this.handleId = handleId;
            this.owner = owner;
            this.playerId = playerId;
            this.regionId = regionId;
            this.options = options;
        }

        private void attach(TaskHandle task) {
            this.task = Objects.requireNonNull(task, "task");
            if (!active.get()) {
                task.cancel();
            }
        }

        private void render(TaskHandle timer) {
            if (!active.get()) {
                timer.cancel();
                return;
            }
            if (elapsedTicks >= options.durationTicks()) {
                stop();
                return;
            }

            Player player = Bukkit.getPlayer(playerId);
            RegionSnapshot region = RegionRuntime.get(regionId);
            if (player == null || !player.isOnline() || region == null
                    || !region.owner().equals(owner)) {
                stop();
                return;
            }

            World playerWorld = player.getWorld();
            UUID worldId = region.worldId();
            if (!playerWorld.getUID().equals(worldId)) {
                elapsedTicks += options.periodTicks();
                return;
            }
            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                stop();
                return;
            }

            OutlineSampler.Outline outline = OutlineSampler.sample(region.shape(), options.spacing());
            double dynamicY = player.getLocation().getBlockY() + 1.0;
            double[] coordinates = outline.coordinates();
            for (int offset = 0; offset < coordinates.length; offset += 3) {
                double y = outline.dynamicY() ? dynamicY : coordinates[offset + 1];
                Location location = new Location(world, coordinates[offset], y, coordinates[offset + 2]);
                Effects.particle(options.particleName()).at(location).show(player);
            }
            elapsedTicks += options.periodTicks();
        }

        @Override
        public @NotNull UUID playerId() {
            return playerId;
        }

        @Override
        public @NotNull RegionId regionId() {
            return regionId;
        }

        @Override
        public boolean active() {
            return active.get();
        }

        @Override
        public void close() {
            stop();
        }

        private boolean stop() {
            if (!active.compareAndSet(true, false)) {
                return false;
            }
            ACTIVE.remove(handleId, this);
            TaskHandle current = task;
            if (current != null) {
                current.cancel();
            }
            return true;
        }
    }
}
