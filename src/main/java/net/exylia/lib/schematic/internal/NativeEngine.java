package net.exylia.lib.schematic.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The built-in engine: Sponge schematics with nothing but the Paper API.
 *
 * <p>Bound only when FastAsyncWorldEdit is not — a hybrid server (NeoForge or
 * Forge with the Paper API, such as Youer or Mohist) where FAWE cannot run, or
 * any server that simply does not have it. FAWE always wins when it is there:
 * it is faster on full rewrites and keeps every byte of block-entity NBT, which
 * Bukkit does not expose.
 *
 * <h2>Never on a tick for long</h2>
 * Reading, decompressing, parsing and comparing happen on the calling thread,
 * which the runtime guarantees is not a server thread. The world is touched
 * only on the thread that owns each chunk, through {@code runAtLocation}, so
 * the same code runs on Paper's main thread and on Folia's region threads:
 * <ol>
 *   <li><b>Load</b> — an unloaded chunk is loaded with
 *   {@code getChunkAtAsync}, never synchronously, and held with a plugin
 *   ticket until the paste is done with it.</li>
 *   <li><b>Snapshot</b> — a {@link ChunkSnapshot} is taken on the owning
 *   thread. It copies the chunk's palettes, a fraction of a millisecond.</li>
 *   <li><b>Diff</b> — off-thread, the snapshot is compared with the schematic
 *   and only blocks whose {@link BlockData} differs are kept.</li>
 *   <li><b>Write</b> — back on the owning thread, under
 *   {@link TickBudget}: at most {@link TickBudget#BUDGET_NANOS} per tick per
 *   thread, shared by every chunk of every paste, continuing next tick.</li>
 * </ol>
 * {@link #paste} blocks its own (asynchronous) thread until every chunk has
 * finished or one has failed, and gives up if nothing has made progress for
 * {@link #STALL_NANOS}: a total timeout would either be too short for a large
 * first paste or too long to notice a dropped task.
 *
 * <h2>What a paste writes</h2>
 * Every block whose state differs, air included and without physics, so what
 * the last match built is cleared. Then every block entity the file holds that
 * {@link NativeCodec} understands, whether or not the block changed: a looted
 * chest is the same {@code BlockData} as a full one. A block state the server
 * does not know — a removed or modded block — is left alone and reported once
 * per paste, not once per block.
 *
 * <h2>The gap</h2>
 * A chunk is compared with its snapshot, not with itself at the moment of
 * writing. A block somebody changes in the fraction of a second between the two
 * survives the paste. Regenerating an arena that is still being played in has
 * this problem with any engine; this one makes the window short, not zero.
 */
final class NativeEngine implements SchematicEngine {

    /** How long a paste or save waits without any chunk making progress. */
    static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(60);

    /** Block writes between clock reads: each is a microsecond or two. */
    private static final int BLOCK_BATCH = 16;

    /**
     * Parsed schematics held, by weight rather than by count.
     *
     * <p>FaweEngine holds eight clipboards; this engine's storage is a byte per
     * block for most arenas, so a count would mean anything from a few
     * kilobytes to half a gigabyte. 128 MB is a server mid-rotation's arenas
     * several times over, and a miss costs one file read on a thread that is
     * already off the main one.
     */
    private static final long CACHE_BYTES = 128L << 20;

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;

    private final Cache<String, Loaded> schematics = Caffeine.newBuilder()
            .maximumWeight(CACHE_BYTES)
            .weigher((String path, Loaded loaded) -> (int) Math.min(Integer.MAX_VALUE,
                    loaded.schematic.bytes()))
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    /**
     * One budget per server thread. Bounded by the server's own threads — the
     * main thread, or Folia's fixed region pool — and dropped by
     * {@link #releaseAll()}.
     */
    private final Map<Thread, TickBudget> budgets = new ConcurrentHashMap<>();

    NativeEngine(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    // ------------------------------------------------------------------
    // Paste
    // ------------------------------------------------------------------

    @Override
    public void paste(World world, int x, int y, int z, File source, boolean copyEntities)
            throws Exception {
        Loaded loaded = load(source);
        SpongeSchematic schematic = loaded.schematic;
        BlockData[] palette = loaded.palette();
        int dataVersion = schematic.dataVersion() < 0 ? NativeCodec.dataVersion() : schematic.dataVersion();
        int originX = x + schematic.offsetX();
        int originY = y + schematic.offsetY();
        int originZ = z + schematic.offsetZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        // Grouped once, here, rather than scanned once per chunk.
        Map<Long, List<Integer>> blockEntities = new HashMap<>();
        for (Integer index : schematic.blockEntities().keySet()) {
            int worldX = originX + index % schematic.width();
            int worldZ = originZ + (index / schematic.width()) % schematic.length();
            blockEntities.computeIfAbsent(ChunkDiff.key(worldX >> 4, worldZ >> 4),
                    ignored -> new ArrayList<>()).add(index);
        }
        Map<Long, List<SpongeSchematic.EntityEntry>> entities = new HashMap<>();
        if (copyEntities) {
            for (SpongeSchematic.EntityEntry entity : schematic.entities()) {
                int worldX = (int) Math.floor(originX + entity.x());
                int worldZ = (int) Math.floor(originZ + entity.z());
                entities.computeIfAbsent(ChunkDiff.key(worldX >> 4, worldZ >> 4),
                        ignored -> new ArrayList<>()).add(entity);
            }
        }

        Job job = new Job();
        List<CompletableFuture<Void>> chunks = new ArrayList<>();
        for (int chunkX = originX >> 4; chunkX <= (originX + schematic.width() - 1) >> 4; chunkX++) {
            for (int chunkZ = originZ >> 4; chunkZ <= (originZ + schematic.length() - 1) >> 4; chunkZ++) {
                long key = ChunkDiff.key(chunkX, chunkZ);
                ChunkPaste work = new ChunkPaste(job, world, chunkX, chunkZ, schematic, palette,
                        dataVersion, originX, originY, originZ, minY, maxY,
                        blockEntities.getOrDefault(key, List.of()),
                        entities.getOrDefault(key, List.of()));
                chunks.add(work.done);
                withChunk(job, world, chunkX, chunkZ, work.done, work::snapshot);
            }
        }
        try {
            await(job, chunks);
        } finally {
            if (!loaded.unknown.isEmpty()) {
                debug.warn(source.getName() + " holds " + loaded.unknown.size()
                        + " block state(s) this server does not know, left as they were: "
                        + String.join(", ", loaded.unknown.subList(0, Math.min(5, loaded.unknown.size())))
                        + (loaded.unknown.size() > 5 ? ", ..." : ""));
            }
            if (job.incomplete.get() > 0) {
                debug.warn(source.getName() + ": " + job.incomplete.get()
                        + " block entit(ies) or entit(ies) could not be fully restored.");
            }
        }
    }

    /** One chunk of a paste, from snapshot to the last write. */
    private final class ChunkPaste {

        private final Job job;
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private final SpongeSchematic schematic;
        private final BlockData[] palette;
        private final int dataVersion;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final int minY;
        private final int maxY;
        private final List<Integer> blockEntities;
        private final List<SpongeSchematic.EntityEntry> entities;
        private final Location at;
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        private Chunk chunk;
        private int[] changes;
        private int nextBlock;
        private int nextBlockEntity;
        private int nextEntity;

        private ChunkPaste(Job job, World world, int chunkX, int chunkZ, SpongeSchematic schematic,
                           BlockData[] palette, int dataVersion, int originX, int originY, int originZ,
                           int minY, int maxY, List<Integer> blockEntities,
                           List<SpongeSchematic.EntityEntry> entities) {
            this.job = job;
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.schematic = schematic;
            this.palette = palette;
            this.dataVersion = dataVersion;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.minY = minY;
            this.maxY = maxY;
            this.blockEntities = blockEntities;
            this.entities = entities;
            this.at = centre(world, chunkX, chunkZ);
        }

        /** On the owning thread: the chunk is loaded and ticketed. */
        private void snapshot(Chunk loaded) {
            this.chunk = loaded;
            ChunkSnapshot snapshot = loaded.getChunkSnapshot(false, false, false);
            tasks.runAsync(() -> {
                try {
                    changes = ChunkDiff.diff(schematic, palette, chunkX, chunkZ, originX, originY,
                            originZ, minY, maxY, snapshot::getBlockData);
                    job.progress();
                    tasks.runAtLocation(at, this::write);
                } catch (Throwable failure) {
                    fail(failure);
                }
            });
        }

        /** On the owning thread, as many times as the budget needs. */
        private void write() {
            try {
                if (job.failure != null) {
                    release();
                    done.complete(null);
                    return;
                }
                TickBudget budget = budget();
                int blocks = changes.length / 2;
                // Blocks first, then the block entities that sit on them, then
                // loose entities that may stand on either.
                nextBlock = budget.run(nextBlock, blocks, BLOCK_BATCH, this::writeBlock);
                if (nextBlock == blocks) {
                    nextBlockEntity = budget.run(nextBlockEntity, blockEntities.size(), 1,
                            this::writeBlockEntity);
                }
                if (nextBlock == blocks && nextBlockEntity == blockEntities.size()) {
                    nextEntity = budget.run(nextEntity, entities.size(), 1, this::spawnEntity);
                }
                job.progress();
                if (nextBlock < blocks || nextBlockEntity < blockEntities.size()
                        || nextEntity < entities.size()) {
                    tasks.runAtLocationLater(at, 1, this::write);
                    return;
                }
                release();
                done.complete(null);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void writeBlock(int change) {
            int packed = changes[change * 2];
            BlockData data = palette[changes[change * 2 + 1]];
            world.getBlockAt((chunkX << 4) | (packed & 15), (packed >>> 8) + minY,
                    (chunkZ << 4) | ((packed >>> 4) & 15)).setBlockData(data, false);
        }

        private void writeBlockEntity(int position) {
            int index = blockEntities.get(position);
            if (palette[schematic.stateAt(index)] == null) {
                return;
            }
            int relativeX = index % schematic.width();
            int relativeZ = (index / schematic.width()) % schematic.length();
            int relativeY = index / (schematic.width() * schematic.length());
            int worldY = originY + relativeY;
            if (worldY < minY || worldY >= maxY) {
                return;
            }
            try {
                if (!NativeCodec.restore(world.getBlockAt(originX + relativeX, worldY, originZ + relativeZ),
                        schematic.blockEntities().get(index), dataVersion)) {
                    job.incomplete.incrementAndGet();
                }
            } catch (Throwable failure) {
                job.incomplete.incrementAndGet();
            }
        }

        private void spawnEntity(int position) {
            SpongeSchematic.EntityEntry entity = entities.get(position);
            try {
                NativeCodec.spawn(world, entity, originX + entity.x(), originY + entity.y(),
                        originZ + entity.z(), dataVersion);
            } catch (Throwable failure) {
                job.incomplete.incrementAndGet();
            }
        }

        private void fail(Throwable failure) {
            job.fail(failure);
            // The ticket is removed on the thread that owns the chunk, like
            // everything else that touches it.
            tasks.runAtLocation(at, this::release);
            done.completeExceptionally(failure);
        }

        private void release() {
            Chunk held = chunk;
            chunk = null;
            if (held != null) {
                held.removePluginChunkTicket(plugin);
            }
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    @Override
    public void save(World world, Bounds bounds, File destination, boolean copyEntities)
            throws Exception {
        int width = bounds.maxX() - bounds.minX() + 1;
        int height = bounds.maxY() - bounds.minY() + 1;
        int length = bounds.maxZ() - bounds.minZ() + 1;
        long volume = (long) width * height * length;
        if (volume > SpongeSchematic.MAX_VOLUME || width > 0xFFFF || height > 0xFFFF || length > 0xFFFF) {
            throw new IllegalArgumentException("the box holds " + volume + " blocks, more than the "
                    + SpongeSchematic.MAX_VOLUME + " the built-in engine saves; install "
                    + "FastAsyncWorldEdit for builds this large");
        }
        int dataVersion = NativeCodec.dataVersion();
        SaveBuilder builder = new SaveBuilder(bounds, width, height, length,
                world.getMinHeight(), world.getMaxHeight());

        Job job = new Job();
        List<CompletableFuture<Void>> chunks = new ArrayList<>();
        for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
                CompletableFuture<Void> done = new CompletableFuture<>();
                chunks.add(done);
                int capturedX = chunkX;
                int capturedZ = chunkZ;
                withChunk(job, world, chunkX, chunkZ, done, chunk -> {
                    try {
                        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
                        Map<Integer, SpongeSchematic.BlockEntity> blockEntities;
                        List<SpongeSchematic.EntityEntry> entities;
                        try {
                            blockEntities = captureBlockEntities(chunk, bounds, width, length, dataVersion);
                            entities = copyEntities ? captureEntities(chunk, bounds) : List.of();
                        } catch (IOException unreadable) {
                            // A save that silently dropped a chest's contents
                            // would be found out at the next regeneration;
                            // failing it is found out now.
                            throw new UncheckedIOException(unreadable);
                        }
                        tasks.runAsync(() -> {
                            try {
                                builder.accept(capturedX, capturedZ, snapshot, blockEntities, entities);
                                job.progress();
                                done.complete(null);
                            } catch (Throwable failure) {
                                job.fail(failure);
                                done.completeExceptionally(failure);
                            }
                        });
                    } finally {
                        // Everything needed is in the snapshot: the chunk can go.
                        chunk.removePluginChunkTicket(plugin);
                    }
                });
            }
        }
        await(job, chunks);
        SpongeSchematic schematic = builder.build(dataVersion);

        // Written beside the destination and moved over it, so a crash halfway
        // leaves the old arena rather than half of a new one.
        File partial = new File(destination.getParentFile(), destination.getName() + ".partial");
        try (OutputStream out = new FileOutputStream(partial)) {
            schematic.write(out);
        }
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        // A schematic that was just written is the one most likely to be
        // pasted next, so it goes in warm rather than being read straight back.
        schematics.put(destination.getAbsolutePath(), new Loaded(schematic, destination.lastModified()));
    }

    private static Map<Integer, SpongeSchematic.BlockEntity> captureBlockEntities(
            Chunk chunk, Bounds bounds, int width, int length, int dataVersion) throws IOException {
        Map<Integer, SpongeSchematic.BlockEntity> captured = new LinkedHashMap<>();
        for (BlockState state : chunk.getTileEntities(false)) {
            int x = state.getX();
            int y = state.getY();
            int z = state.getZ();
            if (!bounds.contains(x, y, z)) {
                continue;
            }
            SpongeSchematic.BlockEntity entry = NativeCodec.capture(state, dataVersion);
            if (entry != null) {
                int index = ((y - bounds.minY()) * length + (z - bounds.minZ())) * width + (x - bounds.minX());
                captured.put(index, entry);
            }
        }
        return captured;
    }

    private static List<SpongeSchematic.EntityEntry> captureEntities(Chunk chunk, Bounds bounds)
            throws IOException {
        List<SpongeSchematic.EntityEntry> captured = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            Location at = entity.getLocation();
            if (entity instanceof Player
                    || !bounds.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                continue;
            }
            SpongeSchematic.EntityEntry entry = NativeCodec.capture(entity, bounds.minX(),
                    bounds.minY(), bounds.minZ());
            if (entry != null) {
                captured.add(entry);
            }
        }
        return captured;
    }

    /**
     * Turns chunk snapshots into one schematic, a chunk at a time.
     *
     * <p>Each snapshot is folded in as soon as it arrives and then dropped, so
     * a save holds the schematic it is building and a handful of snapshots,
     * never every chunk of the box at once.
     */
    static final class SaveBuilder {

        private final Bounds bounds;
        private final int width;
        private final int height;
        private final int length;
        private final int minY;
        private final int maxY;
        private final int[] states;
        private final Map<BlockData, Integer> ids = new HashMap<>();
        private final List<BlockData> palette = new ArrayList<>();
        private final Map<Integer, SpongeSchematic.BlockEntity> blockEntities = new LinkedHashMap<>();
        private final List<SpongeSchematic.EntityEntry> entities = new ArrayList<>();

        SaveBuilder(Bounds bounds, int width, int height, int length, int minY, int maxY) {
            this.bounds = bounds;
            this.width = width;
            this.height = height;
            this.length = length;
            this.minY = minY;
            this.maxY = maxY;
            this.states = new int[width * height * length];
            // Blocks past the world's height are never visited; -1 marks them
            // for air at the end.
            Arrays.fill(states, -1);
        }

        synchronized void accept(int chunkX, int chunkZ, ChunkSnapshot snapshot,
                                 Map<Integer, SpongeSchematic.BlockEntity> captured,
                                 List<SpongeSchematic.EntityEntry> loose) {
            int fromX = Math.max(bounds.minX(), chunkX << 4);
            int toX = Math.min(bounds.maxX(), (chunkX << 4) + 15);
            int fromZ = Math.max(bounds.minZ(), chunkZ << 4);
            int toZ = Math.min(bounds.maxZ(), (chunkZ << 4) + 15);
            int fromY = Math.max(bounds.minY(), minY);
            int toY = Math.min(bounds.maxY(), maxY - 1);
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    for (int x = fromX; x <= toX; x++) {
                        BlockData data = snapshot.getBlockData(x & 15, y, z & 15);
                        Integer id = ids.get(data);
                        if (id == null) {
                            id = palette.size();
                            ids.put(data, id);
                            palette.add(data);
                        }
                        states[((y - bounds.minY()) * length + (z - bounds.minZ())) * width
                                + (x - bounds.minX())] = id;
                    }
                }
            }
            blockEntities.putAll(captured);
            entities.addAll(loose);
        }

        synchronized SpongeSchematic build(int dataVersion) {
            List<String> strings = new ArrayList<>(palette.size() + 1);
            for (BlockData data : palette) {
                strings.add(data.getAsString());
            }
            int air = -1;
            SpongeSchematic.Indices indices = SpongeSchematic.Indices.of(strings.size() + 1, states.length);
            for (int index = 0; index < states.length; index++) {
                int state = states[index];
                if (state < 0) {
                    if (air < 0) {
                        air = strings.indexOf(SpongeSchematic.AIR);
                        if (air < 0) {
                            air = strings.size();
                            strings.add(SpongeSchematic.AIR);
                        }
                    }
                    state = air;
                }
                indices.set(index, state);
            }
            return new SpongeSchematic(width, height, length, 0, 0, 0,
                    new int[]{bounds.minX(), bounds.minY(), bounds.minZ()}, dataVersion,
                    strings.toArray(String[]::new), indices, blockEntities, entities);
        }
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    @Override
    public void releaseAll() {
        schematics.invalidateAll();
        budgets.clear();
    }

    /**
     * Hands a loaded, ticketed chunk to {@code then}, on the thread that owns
     * it.
     *
     * <p>Waits for the thread's budget before taking a snapshot, so two hundred
     * chunk tasks due on one tick spread over several. An unloaded chunk is
     * loaded asynchronously; only a server whose API lacks
     * {@code getChunkAtAsync} gets the synchronous load, one chunk per task.
     */
    private void withChunk(Job job, World world, int chunkX, int chunkZ,
                           CompletableFuture<Void> done, Consumer<Chunk> then) {
        Location at = centre(world, chunkX, chunkZ);
        Consumer<Chunk> use = chunk -> {
            try {
                if (job.failure != null) {
                    done.complete(null);
                    return;
                }
                TickBudget budget = budget();
                long start = budget.now();
                chunk.addPluginChunkTicket(plugin);
                then.accept(chunk);
                budget.charge(budget.now() - start);
                job.progress();
            } catch (Throwable failure) {
                job.fail(failure);
                done.completeExceptionally(failure);
            }
        };
        Runnable[] attempt = new Runnable[1];
        attempt[0] = () -> {
            try {
                if (job.failure != null) {
                    done.complete(null);
                    return;
                }
                if (!budget().hasTime()) {
                    tasks.runAtLocationLater(at, 1, attempt[0]);
                    return;
                }
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    use.accept(world.getChunkAt(chunkX, chunkZ));
                    return;
                }
                CompletableFuture<Chunk> loading;
                try {
                    loading = world.getChunkAtAsync(chunkX, chunkZ);
                } catch (LinkageError | UnsupportedOperationException noAsyncApi) {
                    use.accept(world.getChunkAt(chunkX, chunkZ));
                    return;
                }
                // Back through the scheduler: where the load completes is the
                // server's business, and the chunk is only touched on its owner.
                loading.whenComplete((chunk, failure) -> {
                    if (failure != null || chunk == null) {
                        Throwable cause = failure != null ? failure
                                : new IllegalStateException("chunk " + chunkX + "," + chunkZ + " did not load");
                        job.fail(cause);
                        done.completeExceptionally(cause);
                        return;
                    }
                    tasks.runAtLocation(at, () -> use.accept(chunk));
                });
            } catch (Throwable failure) {
                job.fail(failure);
                done.completeExceptionally(failure);
            }
        };
        tasks.runAtLocation(at, attempt[0]);
    }

    /**
     * Waits for every chunk, failing as soon as one fails or nothing moves.
     *
     * <p>Blocks the calling thread, which the runtime guarantees is not a
     * server thread.
     */
    private static void await(Job job, List<CompletableFuture<Void>> chunks) throws Exception {
        CompletableFuture<Void> every = CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new));
        while (true) {
            Throwable failure = job.failure;
            if (failure != null) {
                throw rethrowable(failure);
            }
            try {
                every.get(1, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException waiting) {
                if (System.nanoTime() - job.lastProgress.get() > STALL_NANOS) {
                    TimeoutException stalled = new TimeoutException("no chunk made progress for "
                            + TimeUnit.NANOSECONDS.toSeconds(STALL_NANOS) + " seconds");
                    job.fail(stalled);
                    throw stalled;
                }
            } catch (ExecutionException failed) {
                job.fail(failed.getCause());
                throw rethrowable(failed.getCause());
            } catch (InterruptedException interrupted) {
                job.fail(interrupted);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }

    private static Exception rethrowable(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return failure instanceof Exception exception ? exception : new IllegalStateException(failure);
    }

    private TickBudget budget() {
        return budgets.computeIfAbsent(Thread.currentThread(), ignored -> new TickBudget(System::nanoTime));
    }

    private static Location centre(World world, int chunkX, int chunkZ) {
        return new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
    }

    private Loaded load(File source) throws IOException {
        String path = source.getAbsolutePath();
        long modified = source.lastModified();
        Loaded known = schematics.getIfPresent(path);
        // Keyed by path, checked by modification time: a file replaced by hand
        // is read again rather than pasted from memory.
        if (known != null && known.modified == modified) {
            return known;
        }
        SpongeSchematic schematic;
        try (InputStream in = new FileInputStream(source)) {
            schematic = SpongeSchematic.read(in);
        }
        Loaded loaded = new Loaded(schematic, modified);
        schematics.put(path, loaded);
        return loaded;
    }

    /** A parsed file and, once asked for, its palette as this server's block data. */
    private static final class Loaded {

        private final SpongeSchematic schematic;
        private final long modified;
        private volatile BlockData[] resolved;
        private volatile List<String> unknown = List.of();

        private Loaded(SpongeSchematic schematic, long modified) {
            this.schematic = schematic;
            this.modified = modified;
        }

        /**
         * Parsed once per file rather than once per paste, and off the server
         * threads: {@code createBlockData} only reads the block registry.
         */
        private synchronized BlockData[] palette() {
            if (resolved == null) {
                String[] strings = schematic.palette();
                BlockData[] out = new BlockData[strings.length];
                List<String> missing = new ArrayList<>();
                for (int id = 0; id < strings.length; id++) {
                    if (strings[id] == null) {
                        continue;
                    }
                    try {
                        out[id] = Bukkit.createBlockData(strings[id]);
                    } catch (IllegalArgumentException notKnown) {
                        missing.add(strings[id]);
                    }
                }
                unknown = List.copyOf(missing);
                resolved = out;
            }
            return resolved;
        }
    }

    /** What every chunk of one operation shares. */
    private static final class Job {

        private volatile Throwable failure;
        private final AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        private final AtomicInteger incomplete = new AtomicInteger();

        private void fail(Throwable cause) {
            if (failure == null) {
                failure = cause;
            }
        }

        private void progress() {
            lastProgress.set(System.nanoTime());
        }
    }
}
