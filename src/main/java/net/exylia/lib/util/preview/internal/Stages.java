package net.exylia.lib.util.preview.internal;

import net.exylia.lib.util.preview.PreviewSettings;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hands out a patch of empty sky to each preview, one at a time.
 *
 * <h2>Why slots rather than "above the player"</h2>
 * Two players previewing at once would otherwise be lifted straight up from
 * wherever they stood. In a lobby that is often the same few blocks, so each
 * would see the other's effect and each other's floating body &mdash; the one
 * thing a preview must never do.
 *
 * <p>Slots are laid out on a grid far apart, claimed for the length of a
 * preview and released afterwards. The grid is global rather than per plugin:
 * two different plugins previewing at the same time must not collide either.
 */
final class Stages {

    /** Which slot index each world's stages are using. */
    private static final ConcurrentMap<Long, Boolean> CLAIMED = new ConcurrentHashMap<>();

    private Stages() {
    }

    /**
     * Claims a free slot and returns where the player should stand.
     *
     * <p>The stage keeps the player's own world: crossing worlds would change
     * their sky, their time of day and their biome sounds, and would fire a
     * world-change event at every plugin on the server for something the player
     * did not do.
     *
     * @param world    the player's world
     * @param settings where the stage sits
     * @return the claimed slot
     */
    static @NotNull Slot claim(@NotNull World world, @NotNull PreviewSettings settings) {
        for (long index = 0; ; index++) {
            if (CLAIMED.putIfAbsent(index, Boolean.TRUE) == null) {
                return new Slot(index, position(world, settings, index));
            }
        }
    }

    /**
     * Where a slot sits, as a square spiral around the world origin.
     *
     * <p>Spread over two axes rather than a line, so the hundredth simultaneous
     * preview is still near spawn rather than a hundred separations away in one
     * direction, where the client would be loading chunks it will never see.
     */
    private static Location position(World world, PreviewSettings settings, long index) {
        int side = (int) Math.ceil(Math.sqrt(index + 1));
        long offset = index - (long) (side - 1) * (side - 1);
        int x = (int) (offset < side ? offset : side - 1);
        int z = (int) (offset < side ? side - 1 : offset - side);
        return new Location(world,
                (x - side / 2.0) * settings.separation() + 0.5,
                settings.height(),
                (z - side / 2.0) * settings.separation() + 0.5);
    }

    /** Gives a slot back. */
    static void release(@NotNull Slot slot) {
        CLAIMED.remove(slot.index());
    }

    /** How many stages are in use, for diagnostics and tests. */
    static int inUse() {
        return CLAIMED.size();
    }

    /** Frees every slot, on shutdown. */
    static void releaseAll() {
        CLAIMED.clear();
    }

    /** One claimed patch of sky. */
    record Slot(long index, @NotNull Location where) {
    }
}
