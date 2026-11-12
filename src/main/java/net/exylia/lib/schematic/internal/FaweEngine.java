package net.exylia.lib.schematic.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fastasyncworldedit.core.extent.clipboard.DiskOptimizedClipboard;
import com.fastasyncworldedit.core.extent.clipboard.MemoryOptimizedClipboard;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * The only class that names FastAsyncWorldEdit.
 *
 * <p>A server without FAWE never loads it — verified in bytecode, the same way
 * PacketEvents and Folia are. Everything above {@link SchematicEngine} keeps
 * working and reports itself unsupported.
 */
final class FaweEngine implements SchematicEngine {

    /**
     * Clipboards held in memory, by absolute file path.
     *
     * <p>Deliberately small. A clipboard is not a cache entry like a parsed
     * component: a 200x80x200 arena is millions of block states, tens of
     * megabytes live. ExyliaCommons held fifty, which on a practice server with
     * large arenas is most of a heap spent on schematics nobody is pasting.
     * Eight is the working set of a server mid-rotation, and anything past that
     * costs one file read on a thread that is already off the main one.
     *
     * <p>{@code weakValues()} was considered and rejected: it would make the
     * hit rate depend on GC timing, so it would be least predictable exactly
     * when the heap is under pressure, which is when a re-read is cheapest to
     * afford.
     */
    private final Cache<String, Clipboard> clipboards = Caffeine.newBuilder()
            .maximumSize(8)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    /**
     * Returns whether FastAsyncWorldEdit is loaded and usable.
     *
     * <p>Guards {@link Throwable}: a FAWE whose API moved throws
     * {@link NoClassDefFoundError}, which a {@code catch (Exception)} does not
     * see, and an unseen failure here would make the module claim an engine it
     * does not have.
     */
    static boolean ready() {
        try {
            return WorldEdit.getInstance() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void save(World world, Bounds bounds, File destination, boolean copyEntities)
            throws Exception {
        com.sk89q.worldedit.world.World target = BukkitAdapter.adapt(world);
        CuboidRegion region = new CuboidRegion(target,
                BlockVector3.at(bounds.minX(), bounds.minY(), bounds.minZ()),
                BlockVector3.at(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
        BlockArrayClipboard clipboard = inMemory(region);

        // changeSetNull, not the deprecated disableHistory: FAWE 2.8.4
        // deprecates the latter and the build carries zero warnings. History is
        // off either way — an arena regenerated between matches is not
        // something anyone will undo, and recording it would be a second copy
        // of every block.
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(target)
                .changeSetNull()
                .limitUnlimited()
                .build()) {
            ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard,
                    region.getMinimumPoint());
            copy.setCopyingEntities(copyEntities);
            // Biomes belong to the world, not to the build: a schematic pasted
            // elsewhere should not drag its climate along.
            copy.setCopyingBiomes(false);
            Operations.complete(copy);
        }

        try (OutputStream out = new FileOutputStream(destination);
             ClipboardWriter writer = BuiltInClipboardFormat.FAST.getWriter(out)) {
            writer.write(clipboard);
        }
        // A schematic that was just written is the one most likely to be
        // pasted next, so it goes in warm rather than being read straight back.
        clipboards.put(destination.getAbsolutePath(), clipboard);
    }

    @Override
    public void paste(World world, int x, int y, int z, File source, boolean copyEntities)
            throws Exception {
        Clipboard clipboard = load(source);
        com.sk89q.worldedit.world.World target = BukkitAdapter.adapt(world);
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(target)
                .changeSetNull()
                .limitUnlimited()
                .build()) {
            Operations.complete(new ClipboardHolder(clipboard)
                    .createPaste(session)
                    .to(BlockVector3.at(x, y, z))
                    // Air included: what the last match built is cleared rather
                    // than pasted around.
                    .ignoreAirBlocks(false)
                    .copyEntities(copyEntities)
                    .copyBiomes(false)
                    .build());
        }
    }

    @Override
    public void releaseAll() {
        clipboards.invalidateAll();
    }

    private Clipboard load(File source) throws Exception {
        Clipboard known = clipboards.getIfPresent(source.getAbsolutePath());
        if (known != null) {
            return known;
        }
        ClipboardFormat format = ClipboardFormats.findByFile(source);
        if (format == null) {
            throw new IllegalStateException(
                    "no clipboard format recognises " + source.getName());
        }
        Clipboard clipboard;
        try (InputStream in = new FileInputStream(source);
             ClipboardReader reader = format.getReader(in)) {
            clipboard = reader.read(UUID.randomUUID(), size -> inMemory(
                    new CuboidRegion(BlockVector3.ZERO, size.subtract(BlockVector3.ONE))));
        }
        clipboard = keepInMemory(clipboard);
        clipboards.put(source.getAbsolutePath(), clipboard);
        return clipboard;
    }

    /**
     * A clipboard held in memory, whatever FAWE's {@code clipboard.use-disk}.
     *
     * <p>{@code format.load} and {@code new BlockArrayClipboard(region)} follow
     * that setting, and with it on each one opens a {@code .bd} file under
     * {@code FastAsyncWorldEdit/clipboard/} that nothing closes or deletes until
     * the next start. This cache drops a clipboard every ten minutes or past
     * eight, so a practice server re-read the same arenas all day and piled up
     * gigabytes of identical files. These clipboards are the engine's cache,
     * not a player's clipboard: they never belong on disk.
     */
    /**
     * Moves a clipboard FAWE put on disk into memory, and removes its file.
     *
     * <p>Only the fast {@code .schem} reader honours the factory handed to
     * {@code read}; the MCEdit {@code .schematic} and plain Sponge readers
     * ignore it and follow {@code use-disk}, so each read of an old arena
     * still opened a {@code .bd} file. This copies it into memory at once and
     * deletes the file, closing first: Windows refuses to delete a mapped one.
     * Copying rather than keeping the disk clipboard until eviction means a
     * clipboard is never closed under a paste that is still reading it.
     */
    private static Clipboard keepInMemory(Clipboard clipboard) throws Exception {
        File backing = diskFile(clipboard);
        if (backing == null) {
            return clipboard;
        }
        try {
            BlockArrayClipboard copy = inMemory(new CuboidRegion(
                    clipboard.getMinimumPoint(), clipboard.getMaximumPoint()));
            copy.setOrigin(clipboard.getOrigin());
            ForwardExtentCopy forward = new ForwardExtentCopy(clipboard, clipboard.getRegion(),
                    copy, clipboard.getMinimumPoint());
            forward.setCopyingEntities(true);
            forward.setCopyingBiomes(false);
            Operations.complete(forward);
            return copy;
        } finally {
            try {
                clipboard.close();
            } catch (Exception ignored) {
                // The file is still removed below, or on exit.
            }
            if (backing.exists() && !backing.delete()) {
                backing.deleteOnExit();
            }
        }
    }

    /**
     * Returns the {@code .bd} file behind a clipboard, or {@code null} when
     * it lives in memory.
     *
     * <p>{@code getParent} and {@code DiskOptimizedClipboard} are FAWE
     * internals: a FAWE where they moved throws a {@link LinkageError}, and the
     * clipboard is then used as read rather than failing the paste.
     */
    private static File diskFile(Clipboard clipboard) {
        try {
            Clipboard inner = clipboard;
            while (inner instanceof BlockArrayClipboard array
                    && array.getParent() != null && array.getParent() != inner) {
                inner = array.getParent();
            }
            return inner instanceof DiskOptimizedClipboard disk ? disk.getFile() : null;
        } catch (LinkageError ignored) {
            return null;
        }
    }

    private static BlockArrayClipboard inMemory(CuboidRegion region) {
        return new BlockArrayClipboard(region, new MemoryOptimizedClipboard(region));
    }
}
