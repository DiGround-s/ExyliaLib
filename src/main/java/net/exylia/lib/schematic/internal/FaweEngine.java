package net.exylia.lib.schematic.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.World;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.Duration;

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
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

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
        Clipboard clipboard = format.load(source);
        clipboards.put(source.getAbsolutePath(), clipboard);
        return clipboard;
    }
}
