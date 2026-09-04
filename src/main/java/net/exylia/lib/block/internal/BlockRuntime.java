package net.exylia.lib.block.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.block.BlockButton;
import net.exylia.lib.block.ClickableBlock;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private record Click(UUID player, Position position, BlockButton button) {
    }

    /** Registers a block, replacing whatever held its location. */
    public static void register(@NotNull ClickableBlock block) {
        Position position = Position.of(block.location());
        if (position == null) return;
        BLOCKS.put(position, block);
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

    /** What is registered at a block, or {@code null}. */
    public static @Nullable ClickableBlock at(@Nullable Block block) {
        if (block == null) return null;
        return BLOCKS.get(new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
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
        RECENT.invalidateAll();
    }

    /**
     * Whether this click is the tail of one already handled.
     *
     * @return {@code true} when it should be ignored
     */
    public static boolean isRepeat(@NotNull UUID player, @NotNull Position position, @NotNull BlockButton button) {
        Click click = new Click(player, position, button);
        long now = System.currentTimeMillis();
        Long last = RECENT.getIfPresent(click);
        if (last != null && now - last < DEBOUNCE_MILLIS) return true;
        RECENT.put(click, now);
        return false;
    }
}
