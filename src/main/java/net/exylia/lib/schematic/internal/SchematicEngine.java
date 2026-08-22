package net.exylia.lib.schematic.internal;

import org.bukkit.World;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * The three things a schematic engine has to be able to do.
 *
 * <p>Everything else the module decides — the name, the folder, the order of
 * the stages, what happens when one of them fails — is above this line, so a
 * test installs a fake here and exercises all of it with no FastAsyncWorldEdit
 * and no server.
 *
 * <h2>Threading</h2>
 * Every method is called off the server threads, by the runtime, and may block.
 * None of them is called on a tick.
 */
@ApiStatus.Internal
public interface SchematicEngine {

    /**
     * Writes a box of the world to a file.
     *
     * @param world        the world the box is in
     * @param bounds       the blocks to copy, inclusive
     * @param destination  the file to write, whose folder already exists
     * @param copyEntities whether loose entities are part of the copy
     * @throws Exception when the copy or the write fails
     */
    void save(@NotNull World world, @NotNull Bounds bounds, @NotNull File destination,
              boolean copyEntities) throws Exception;

    /**
     * Reads a file and puts its blocks into the world.
     *
     * <p>Air included: what the last match built is cleared rather than pasted
     * around.
     *
     * @param world        the world to paste into
     * @param x            where the schematic's origin lands
     * @param y            where the schematic's origin lands
     * @param z            where the schematic's origin lands
     * @param source       the file to read
     * @param copyEntities whether loose entities in the file are pasted
     * @throws Exception when the read or the paste fails
     */
    void paste(@NotNull World world, int x, int y, int z, @NotNull File source,
               boolean copyEntities) throws Exception;

    /** Drops everything held in memory. Called when the server stops. */
    void releaseAll();
}
