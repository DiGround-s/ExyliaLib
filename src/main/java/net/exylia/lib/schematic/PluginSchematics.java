package net.exylia.lib.schematic;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.RegionSnapshot;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.schematic.internal.SchematicRuntime;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * One plugin's schematics.
 *
 * <p>Obtained from {@link Schematics#of(Plugin)}. Files live under this
 * plugin's own data folder, and everything it still has in flight completes as
 * {@link SchematicOutcome#FAILED} when it is disabled.
 *
 * <pre>{@code
 * PluginSchematics schematics = Schematics.of(this);
 * schematics.save("arena_1", bounds, world);
 * schematics.regenerate("arena_1", bounds, world, RegenerateOptions.defaults());
 * }</pre>
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread and none of them blocks. The three that
 * answer from memory — {@link #exists}, {@link #names} and {@link #isIndexed} —
 * return on the calling thread; everything else returns a future that completes
 * on whichever thread finished the work.
 *
 * @since 1.48.0
 */
public final class PluginSchematics {

    private final Plugin plugin;

    /**
     * Package-private: obtained through {@link Schematics#of(Plugin)}.
     *
     * <p>Holds nothing but the plugin. Every folder, index and outstanding
     * future lives in the runtime keyed by plugin name, so two of these are
     * interchangeable and neither can go stale.
     *
     * @param plugin the owner
     */
    PluginSchematics(@NotNull Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** The plugin these schematics belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * Writes a box of the world to {@code <name>.schem}, without its loose
     * entities.
     *
     * @param name   the schematic name; letters, digits, {@code .}, {@code _}
     *               and {@code -}, up to 128 characters, no leading dot
     * @param bounds the blocks to copy
     * @param world  the world the box is in
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> save(@NotNull String name,
                                                            @NotNull Cuboid bounds,
                                                            @NotNull WorldIdentity world) {
        return save(name, bounds, world, false);
    }

    /**
     * Writes a box of the world, choosing whether loose entities come along.
     *
     * <p>Entities are off by default because that is what an arena wants:
     * copying them would restore the dropped swords of the last match along
     * with the walls. Block entities — chests with their loot, spawners, signs
     * with their text — are part of the blocks and always come.
     *
     * <pre>{@code
     * schematics.save("plot_3", bounds, world, true);   // armour stands are the build
     * }</pre>
     *
     * @param name         the schematic name
     * @param bounds       the blocks to copy
     * @param world        the world the box is in
     * @param copyEntities whether loose entities are part of the copy
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> save(@NotNull String name,
                                                            @NotNull Cuboid bounds,
                                                            @NotNull WorldIdentity world,
                                                            boolean copyEntities) {
        return SchematicRuntime.save(plugin, name, bounds, world, copyEntities);
    }

    /**
     * Writes the box a region occupies.
     *
     * @param name   the schematic name
     * @param region the region whose shape and world are used
     * @return how it ended, {@code FAILED} when the region is not a cuboid
     */
    public @NotNull CompletableFuture<SchematicResult> save(@NotNull String name,
                                                            @NotNull RegionSnapshot region) {
        RegionShape shape = region.shape();
        if (!(shape instanceof Cuboid cuboid)) {
            // A schematic is a box. A cylinder or a polygon would have to be
            // squared off, and silently saving a bigger area than the region
            // covers is how an arena regenerates over its neighbour.
            return CompletableFuture.completedFuture(SchematicResult.failed(name,
                    "only cuboid regions can be saved as a schematic, and "
                            + region.id() + " is a " + shape.getClass().getSimpleName()));
        }
        return save(name, cuboid, region.world());
    }

    /**
     * Writes the box between two block corners, inclusive.
     *
     * @param name   the schematic name
     * @param first  one corner
     * @param second the other, in the same world
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> save(@NotNull String name,
                                                            @NotNull BlockPosition first,
                                                            @NotNull BlockPosition second) {
        if (!first.world().equals(second.world())) {
            return CompletableFuture.completedFuture(SchematicResult.failed(name,
                    "the two corners are in different worlds"));
        }
        return save(name, Cuboid.blocks(first, second), first.world());
    }

    /**
     * Writes the box between two locations, inclusive.
     *
     * @param name   the schematic name
     * @param first  one corner
     * @param second the other, in the same world
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> save(@NotNull String name,
                                                            @NotNull Location first,
                                                            @NotNull Location second) {
        if (first.getWorld() == null || second.getWorld() == null) {
            return CompletableFuture.completedFuture(
                    SchematicResult.failed(name, "a corner has no world"));
        }
        return save(name, BlockPosition.from(first), BlockPosition.from(second));
    }

    // ------------------------------------------------------------------
    // Pasting
    // ------------------------------------------------------------------

    /**
     * Puts a schematic into the world, without its loose entities.
     *
     * <p>Air included: what stood there is cleared rather than pasted around.
     *
     * @param name the schematic name
     * @param at   where the schematic's origin lands
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> paste(@NotNull String name,
                                                             @NotNull Location at) {
        return paste(name, at, false);
    }

    /**
     * Puts a schematic into the world, choosing whether its loose entities come
     * with it.
     *
     * @param name         the schematic name
     * @param at           where the schematic's origin lands
     * @param copyEntities whether loose entities in the file are pasted
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> paste(@NotNull String name,
                                                             @NotNull Location at,
                                                             boolean copyEntities) {
        return SchematicRuntime.paste(plugin, name, at, copyEntities);
    }

    // ------------------------------------------------------------------
    // Regenerating
    // ------------------------------------------------------------------

    /**
     * Puts an arena back the way it was saved.
     *
     * <p>Three stages, in this order and no other: the loose entities inside
     * the bounds are removed while the old blocks are still there, the blocks
     * go back, and anyone the new blocks buried is moved up to the nearest air.
     * Rescuing first would put a player back inside a wall that had not been
     * placed yet.
     *
     * <p>The future completes once the blocks are back — which is what a caller
     * reopening the arena is waiting for — and the rescue then follows on the
     * world thread.
     *
     * @param name    the schematic name
     * @param bounds  the box the arena occupies
     * @param world   the world it is in
     * @param options which of the two side stages run
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> regenerate(@NotNull String name,
                                                                  @NotNull Cuboid bounds,
                                                                  @NotNull WorldIdentity world,
                                                                  @NotNull RegenerateOptions options) {
        return SchematicRuntime.regenerate(plugin, name, bounds, world, options);
    }

    /**
     * Puts an arena back with both side stages on.
     *
     * @param name   the schematic name
     * @param bounds the box the arena occupies
     * @param world  the world it is in
     * @return how it ended
     */
    public @NotNull CompletableFuture<SchematicResult> regenerate(@NotNull String name,
                                                                  @NotNull Cuboid bounds,
                                                                  @NotNull WorldIdentity world) {
        return regenerate(name, bounds, world, RegenerateOptions.defaults());
    }

    /**
     * Puts back the arena a region describes.
     *
     * @param name    the schematic name
     * @param region  the region whose shape and world are used
     * @param options which of the two side stages run
     * @return how it ended, {@code FAILED} when the region is not a cuboid
     */
    public @NotNull CompletableFuture<SchematicResult> regenerate(@NotNull String name,
                                                                  @NotNull RegionSnapshot region,
                                                                  @NotNull RegenerateOptions options) {
        RegionShape shape = region.shape();
        if (!(shape instanceof Cuboid cuboid)) {
            return CompletableFuture.completedFuture(SchematicResult.failed(name,
                    "only cuboid regions can be regenerated, and "
                            + region.id() + " is a " + shape.getClass().getSimpleName()));
        }
        return regenerate(name, cuboid, region.world(), options);
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    /**
     * Returns whether a schematic exists, from memory.
     *
     * <p><strong>Never touches the disk.</strong> That is a contract, not an
     * implementation detail: roughly twenty call sites in the ecosystem are
     * menu renders, and ExyliaCommons answered each with {@code File.exists()},
     * one stat syscall per slot per redraw on the thread that also runs the
     * game.
     *
     * <p>Two consequences. A file created or deleted behind the module's back
     * is not noticed until the next restart, so a server owner dropping a
     * {@code .schem} in by hand has to reload. And between this plugin enabling
     * and its first listing finishing, this answers {@code false} for
     * everything — {@link #isIndexed()} says whether that moment has passed.
     * Neither costs anything real: an operation asked for anyway reads the disk
     * and answers {@link SchematicOutcome#NOT_FOUND} honestly.
     *
     * @param name the schematic name
     * @return whether it is known
     */
    public boolean exists(@NotNull String name) {
        return SchematicRuntime.exists(plugin, name);
    }

    /**
     * Returns whether the first listing has finished.
     *
     * @return {@code true} once {@link #exists} and {@link #names} are
     *         answering from a folder that has actually been read
     */
    public boolean isIndexed() {
        return SchematicRuntime.isIndexed(plugin);
    }

    /**
     * Every schematic this plugin has, from memory.
     *
     * <p>Includes the ones still only in the ExyliaCommons folder.
     *
     * @return an immutable snapshot
     */
    public @NotNull Set<String> names() {
        return SchematicRuntime.names(plugin);
    }

    /**
     * Deletes a schematic from both folders.
     *
     * <p>Both, because leaving the old ExyliaCommons copy would make a deleted
     * arena come back at the next restart.
     *
     * @param name the schematic name
     * @return whether anything was removed
     */
    public @NotNull CompletableFuture<Boolean> delete(@NotNull String name) {
        return SchematicRuntime.delete(plugin, name);
    }

    /**
     * The folder schematics are written to.
     *
     * <p>{@code <plugin data folder>/schematics}. The ExyliaCommons folder that
     * is also read is {@code regions} inside it.
     *
     * @return the folder, which may not exist until the first save
     */
    public @NotNull File folder() {
        return SchematicRuntime.folder(plugin);
    }
}
