package net.exylia.lib.block.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.block.BlockButton;
import net.exylia.lib.block.ClickableBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where every registered block on the server lives.
 *
 * <p>One index for every plugin, because the listener that answers a click has
 * one block and has to find its owner from it. Registrations are held until
 * they are taken down or their plugin is disabled, so this is a registry rather
 * than a cache: what expires here is only the record of who clicked what a
 * moment ago.
 *
 * @since 1.110.0
 */
public final class BlockRuntime {

    /**
     * How long a click on the same block by the same player is treated as the
     * same click.
     *
     * <p>A held left button fires every tick, and both hands fire a right
     * click. Neither is a second click by any reading a player would give it,
     * and a crate that opens twice from one press costs a key.
     */
    private static final long DEBOUNCE_MILLIS = 250L;

    private static final Map<Position, ClickableBlock> BLOCKS = new ConcurrentHashMap<>();

    private static final Cache<Click, Long> RECENT = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(2_000)
            .build();

    /** Whether the hopper listener is up; it goes up with the first block that guards its inventory. */
    private static final AtomicBoolean INVENTORY_LISTENER = new AtomicBoolean();

    private BlockRuntime() {
        throw new AssertionError("No instances.");
    }

    /** A block, identified by where it is rather than by a snapshot of it. */
    public record Position(@NotNull UUID world, int x, int y, int z) {

        static @Nullable Position of(@Nullable Location location) {
            if (location == null) return null;
            World world = location.getWorld();
            if (world == null) return null;
            return new Position(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    /** {@code button} is {@code null} for the last right press, kept apart from the debounce. */
    private record Click(UUID player, @Nullable Position position, @Nullable BlockButton button) {
    }

    /** Registers a block, replacing whatever held its location. */
    public static void register(@NotNull ClickableBlock block) {
        Position position = Position.of(block.location());
        if (position == null) return;
        BLOCKS.put(position, block);
        if (block.protectsInventory() && INVENTORY_LISTENER.compareAndSet(false, true)) {
            listenToHoppers();
        }
    }

    /**
     * Registers the hopper listener under the library's own plugin.
     *
     * <p>Late, because Paper skips building a move event for every hopper on the
     * server while nobody listens to one, and most servers never guard an
     * inventory.
     */
    private static void listenToHoppers() {
        try {
            Plugin library = JavaPlugin.getProvidingPlugin(BlockRuntime.class);
            Bukkit.getPluginManager().registerEvents(new BlockInventoryListener(), library);
        } catch (RuntimeException | LinkageError noServer) {
            // A test, or the library loaded outside a plugin class loader.
            INVENTORY_LISTENER.set(false);
        }
    }

    /**
     * Takes a registration down, but only if it is still the one that is up:
     * a block re-registered by another plugin is not this one's to remove.
     */
    public static void unregister(@NotNull Location location, @NotNull ClickableBlock expected) {
        Position position = Position.of(location);
        if (position != null) BLOCKS.remove(position, expected);
    }

    /** What is registered at a location, or {@code null}. */
    public static @Nullable ClickableBlock at(@Nullable Location location) {
        Position position = Position.of(location);
        return position == null ? null : BLOCKS.get(position);
    }

    /**
     * What is registered at a block, or {@code null}. A double chest is one
     * block: either half answers with the registration of the other.
     */
    public static @Nullable ClickableBlock at(@Nullable Block block) {
        if (block == null || BLOCKS.isEmpty()) return null;
        UUID world = block.getWorld().getUID();
        ClickableBlock own = BLOCKS.get(new Position(world, block.getX(), block.getY(), block.getZ()));
        if (own != null) return own;
        // The type first: reading block data allocates, and this runs for every
        // block an explosion or a piston touches.
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return null;
        BlockData data = block.getBlockData();
        if (!(data instanceof Chest chest)) return null;
        BlockFace towards = otherHalf(chest.getFacing(), chest.getType());
        if (towards == null) return null;
        return BLOCKS.get(new Position(world, block.getX() + towards.getModX(), block.getY(),
                block.getZ() + towards.getModZ()));
    }

    /**
     * Where the other half of a double chest is, as Minecraft joins them: a left
     * half has it clockwise of where the chest faces, a right half
     * counter-clockwise.
     *
     * @return the direction, or {@code null} for a single chest
     */
    static @Nullable BlockFace otherHalf(@NotNull BlockFace facing, @NotNull Chest.Type type) {
        if (type == Chest.Type.SINGLE) return null;
        boolean left = type == Chest.Type.LEFT;
        return switch (facing) {
            case NORTH -> left ? BlockFace.EAST : BlockFace.WEST;
            case EAST -> left ? BlockFace.SOUTH : BlockFace.NORTH;
            case SOUTH -> left ? BlockFace.WEST : BlockFace.EAST;
            case WEST -> left ? BlockFace.NORTH : BlockFace.SOUTH;
            default -> null;
        };
    }

    /** How many are registered across every plugin. */
    public static int count() {
        return BLOCKS.size();
    }

    /** How many a plugin has registered. */
    public static int countOf(@NotNull String owner) {
        int total = 0;
        for (ClickableBlock block : BLOCKS.values()) {
            if (block.owner().equals(owner)) total++;
        }
        return total;
    }

    /** Forgets everything a plugin registered. Called when it is disabled. */
    public static void releaseOwner(@NotNull String owner) {
        BLOCKS.values().removeIf(block -> block.owner().equals(owner));
    }

    /** Forgets everything, on shutdown. */
    public static void releaseAll() {
        BLOCKS.clear();
        // The listener went with the library's plugin; a reload registers it again.
        INVENTORY_LISTENER.set(false);
        RECENT.invalidateAll();
    }

    /**
     * Whether this click is the tail of one already handled.
     *
     * @return {@code true} when it should be ignored
     */
    public static boolean isRepeat(@NotNull UUID player, @NotNull Position position, @NotNull BlockButton button) {
        return isRepeat(player, position, button, false);
    }

    /**
     * Whether this click is the tail of one already handled.
     *
     * <p>In adventure mode the client sends no digging packet, so Paper fires a
     * left click from the arm swing instead, and a right click swings the arm
     * too: every right click on a block also arrived as a left click, and a
     * crate opened its contents screen over the opening. There, a left click
     * on the block just right-clicked is that swing.
     *
     * @param adventure whether the player is in adventure mode
     * @return {@code true} when it should be ignored
     */
    public static boolean isRepeat(@NotNull UUID player, @NotNull Position position, @NotNull BlockButton button,
                                   boolean adventure) {
        long now = System.currentTimeMillis();
        RECENT.put(new Click(player, null, null), now);
        // The last right press, repeat or not: a held button swings on every one.
        Click pressed = new Click(player, position, null);
        if (button == BlockButton.RIGHT) {
            RECENT.put(pressed, now);
        } else if (adventure) {
            Long right = RECENT.getIfPresent(pressed);
            if (right != null && now - right < DEBOUNCE_MILLIS) return true;
        }
        Click click = new Click(player, position, button);
        Long last = RECENT.getIfPresent(click);
        if (last != null && now - last < DEBOUNCE_MILLIS) return true;
        RECENT.put(click, now);
        return false;
    }

    /**
     * Whether the player clicked a registered block a moment ago. The client
     * follows a block click with an item use in the air, which item listeners skip.
     */
    public static boolean justClicked(@NotNull UUID player) {
        Long last = RECENT.getIfPresent(new Click(player, null, null));
        return last != null && System.currentTimeMillis() - last < DEBOUNCE_MILLIS;
    }
}
