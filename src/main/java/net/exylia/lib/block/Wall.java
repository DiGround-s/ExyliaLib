package net.exylia.lib.block;

import net.exylia.lib.block.internal.WallFile;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A wall standing in the world, raised through {@link Walls#raise}.
 *
 * <p>The handle is how it comes down: {@link #lower()} while the server runs,
 * {@link #lowerNow()} while it stops. Whatever neither brought down is brought
 * down by {@link Walls#recover} on the next start.
 *
 * @since 1.458.0
 */
public final class Wall {

    private final Walls owner;
    private final String id;
    private final World world;
    private final Material material;
    private final List<WallFile.Placed> blocks;
    private final Set<Long> keys;
    /** The file write; deleting waits for it, so a wall lowered at once leaves no file behind. */
    final CompletableFuture<Void> written;

    Wall(Walls owner, String id, World world, Material material, List<WallFile.Placed> blocks,
         CompletableFuture<Void> written) {
        this.owner = owner;
        this.id = id;
        this.world = world;
        this.material = material;
        this.blocks = List.copyOf(blocks);
        Set<Long> packed = new HashSet<>(blocks.size() * 2);
        for (WallFile.Placed placed : blocks) packed.add(pack(placed.x(), placed.y(), placed.z()));
        this.keys = Set.copyOf(packed);
        this.written = written;
    }

    /** The name it was raised under. */
    public @NotNull String id() {
        return id;
    }

    public @NotNull World world() {
        return world;
    }

    /** What it is made of. */
    public @NotNull Material material() {
        return material;
    }

    /** How many blocks it replaced; the ones already of its material or not replaceable are not counted. */
    public int size() {
        return blocks.size();
    }

    /** Whether it replaced the block at these coordinates. Any thread, no lock. */
    public boolean contains(int x, int y, int z) {
        return keys.contains(pack(x, y, z));
    }

    /** The same, for a block of any world. */
    public boolean contains(@NotNull Block block) {
        return block.getWorld().equals(world) && contains(block.getX(), block.getY(), block.getZ());
    }

    /**
     * Puts back what it replaced, only where its material still stands, and
     * forgets its file once every block is back.
     *
     * @return whether every block came back; a {@code false} keeps the file, so
     *         the next start tries again
     */
    public @NotNull CompletableFuture<Boolean> lower() {
        return owner.lower(this);
    }

    /**
     * Puts back what the calling thread may write, at once, for a plugin that is
     * stopping and can wait for no task. Chunks owned by another thread stay
     * walled and keep the file, which the next start's {@link Walls#recover}
     * reads.
     *
     * @return whether every block came back
     */
    public boolean lowerNow() {
        return owner.lowerNow(this);
    }

    List<WallFile.Placed> blocks() {
        return blocks;
    }

    static long pack(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }
}
