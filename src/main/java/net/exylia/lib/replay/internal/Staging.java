package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayFrame;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.replay.ReplayScene;
import net.exylia.lib.replay.ReplayStage;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.world.TemporaryWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lays a recording's ground down on the temporary world.
 *
 * <h2>A plot per scene</h2>
 * Each scene's chunks keep their layout and their real height: chunk for chunk,
 * section for section, shifted sideways onto a plot and nowhere else. The
 * scene's anchor moves by exactly the same shift, so every position in the
 * recording lands on the block it was recorded on.
 *
 * <h2>Spread over ticks</h2>
 * Placing blocks is the one expensive thing here, and it is done on the server's
 * own thread (the plot's region, on Folia). It is spread so no tick places more
 * than {@link #BLOCKS_PER_TICK}: a typical capture is ready in under a second,
 * a long chase in a few.
 */
@ApiStatus.Internal
final class Staging implements ReplayStage {

    private static final int BLOCKS_PER_TICK = 20_000;

    private final String owner;
    private final Replay replay;
    private final Location[] anchors;
    private final List<ReplayPlayback> playbacks = new CopyOnWriteArrayList<>();

    private Staging(String owner, Replay replay, Location[] anchors) {
        this.owner = owner;
        this.replay = replay;
        this.anchors = anchors;
    }

    /** Reserves the plots and lays the ground down; completes once it is all there. */
    static CompletableFuture<ReplayStage> build(String owner, Replay replay, TaskScheduler scheduler) {
        if (!replay.hasTerrain()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("This recording carries no terrain to stage"));
        }
        List<TerrainSection> terrain = replay.terrain();
        int scenes = replay.scenes().size();
        List<List<TerrainSection>> byScene = new ArrayList<>(scenes);
        for (int index = 0; index < scenes; index++) byScene.add(new ArrayList<>());
        for (TerrainSection section : terrain) {
            if (section.scene() >= 0 && section.scene() < scenes) byScene.get(section.scene()).add(section);
        }
        Location[] anchors = new Location[scenes];
        List<CompletableFuture<Void>> laying = new CopyOnWriteArrayList<>();
        AtomicInteger delay = new AtomicInteger();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int index = 0; index < scenes; index++) {
            int scene = index;
            List<TerrainSection> sections = byScene.get(scene);
            int minX = 0;
            int maxX = 0;
            int minZ = 0;
            int maxZ = 0;
            for (TerrainSection section : sections) {
                minX = Math.min(minX, section.chunkX());
                maxX = Math.max(maxX, section.chunkX());
                minZ = Math.min(minZ, section.chunkZ());
                maxZ = Math.max(maxZ, section.chunkZ());
            }
            int fromX = minX;
            int fromZ = minZ;
            int width = (maxX - minX + 1) * 16;
            int depth = (maxZ - minZ + 1) * 16;
            // One at a time: the temporary world is created by the first, and
            // plots are handed out in order.
            chain = chain.thenCompose(previous -> TemporaryWorld.reserve(width, depth)).thenCompose(plot -> {
                if (plot == null) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("The temporary world could not be created"));
                }
                ReplayScene recorded = replay.scenes().get(scene);
                double x = Math.floor(recorded.x());
                double z = Math.floor(recorded.z());
                int anchorChunkX = (int) x >> 4;
                int anchorChunkZ = (int) z >> 4;
                // The plot's corner is chunk (anchor + from); the anchor sits as
                // far into it as it sat into that chunk.
                anchors[scene] = new Location(plot.world(),
                        plot.minX() + (x - (anchorChunkX + fromX) * 16.0) + (recorded.x() - x),
                        recorded.y(),
                        plot.minZ() + (z - (anchorChunkZ + fromZ) * 16.0) + (recorded.z() - z));
                laying.add(lay(plot.world(), plot.minX() >> 4, plot.minZ() >> 4, fromX, fromZ,
                        sections, scheduler, delay));
                return CompletableFuture.completedFuture(null);
            });
        }
        return chain.thenCompose(done -> CompletableFuture.allOf(laying.toArray(CompletableFuture[]::new)))
                .thenApply(done -> new Staging(owner, replay, anchors));
    }

    /** Places one scene's sections, a chunk at a time, on the chunk's own region. */
    private static CompletableFuture<Void> lay(World world, int plotChunkX, int plotChunkZ, int fromX,
                                               int fromZ, List<TerrainSection> sections,
                                               TaskScheduler scheduler, AtomicInteger delay) {
        Map<Long, List<TerrainSection>> byChunk = new HashMap<>();
        for (TerrainSection section : sections) {
            byChunk.computeIfAbsent(BlackBox.key(section.chunkX(), section.chunkZ()),
                    key -> new ArrayList<>()).add(section);
        }
        List<CompletableFuture<Void>> chunks = new ArrayList<>();
        for (List<TerrainSection> column : byChunk.values()) {
            TerrainSection first = column.getFirst();
            int chunkX = plotChunkX + first.chunkX() - fromX;
            int chunkZ = plotChunkZ + first.chunkZ() - fromZ;
            int blocks = column.size() * TerrainSection.VOLUME;
            long wait = delay.getAndAdd(blocks) / BLOCKS_PER_TICK;
            CompletableFuture<Void> placed = new CompletableFuture<>();
            Location corner = new Location(world, chunkX << 4, 0, chunkZ << 4);
            scheduler.runAtLocationLater(corner, Math.max(1L, wait + 1), () -> {
                try {
                    place(world, chunkX, chunkZ, column);
                    placed.complete(null);
                } catch (RuntimeException failure) {
                    placed.completeExceptionally(failure);
                }
            });
            chunks.add(placed);
        }
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new));
    }

    private static void place(World world, int chunkX, int chunkZ, List<TerrainSection> column) {
        Map<String, BlockData> parsed = new HashMap<>();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (TerrainSection section : column) {
            int baseY = section.sectionY() << 4;
            if (baseY < world.getMinHeight() || baseY >= world.getMaxHeight()) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        String state = section.at(x, y, z);
                        if (state.equals("minecraft:air")) continue;
                        BlockData data = parsed.computeIfAbsent(state, Staging::parse);
                        if (data == null || data.getMaterial().isAir()) continue;
                        // Without physics: sand stays up, water stays still, as
                        // they were in the instant the ground was read.
                        world.getBlockAt(baseX + x, baseY + y, baseZ + z).setBlockData(data, false);
                    }
                }
            }
        }
    }

    private static @Nullable BlockData parse(String state) {
        try {
            return Bukkit.createBlockData(state);
        } catch (IllegalArgumentException unknown) {
            // A block from a newer version: a gap rather than a failed stage.
            return null;
        }
    }

    @Override
    public @NotNull Replay replay() {
        return replay;
    }

    @Override
    public @Nullable ReplayPlayback play(@NotNull List<Player> viewers) {
        ReplayPlayback playback = ReplayRuntime.play(owner, replay, List.of(anchors), viewers,
                net.exylia.lib.replay.ReplayWorld.SOLID, true);
        if (playback != null) playbacks.add(playback);
        return playback;
    }

    @Override
    public @NotNull Location anchor(int scene) {
        return anchors[Math.clamp(scene, 0, anchors.length - 1)].clone();
    }

    @Override
    public @Nullable Location locate(int tick, @NotNull UUID actor) {
        ReplayFrame frame = replay.at(tick, actor);
        if (!frame.present()) return null;
        Location at = anchor(replay.sceneAt(tick)).add(frame.x(), frame.y(), frame.z());
        at.setYaw(frame.yaw());
        at.setPitch(frame.pitch());
        return at;
    }

    @Override
    public void close() {
        playbacks.forEach(ReplayPlayback::stop);
        playbacks.clear();
    }
}
