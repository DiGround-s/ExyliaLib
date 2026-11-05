package net.exylia.lib.block;

import net.exylia.lib.block.internal.CustomBlockIds;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Which resource-pack block a world block is: Nexo, Oraxen, ItemsAdder or
 * CraftEngine.
 *
 * <pre>{@code
 * String id = CustomBlocks.id(event.getBlock()); // "nexo:ruby_ore", or null for a vanilla block
 * if ("nexo:ruby_ore".equals(id)) reward(player);
 * }</pre>
 *
 * <p>Ids are spelled exactly as a {@code material} writes the provider's item
 * ({@code docs/items.md}): {@code nexo:<id>}, {@code oraxen:<id>},
 * {@code itemsadder:<namespace>:<id>} and {@code craftengine:<namespace>:<id>}.
 * One string names the item in a config and the block it places in the world.
 *
 * <p>These blocks are vanilla states underneath (note blocks, tripwire, chorus
 * or mushroom blocks) or furniture entities, so {@code getType()} cannot tell
 * them apart. None of the providers is a dependency: each is reached by
 * reflection once it is enabled, and a provider that is not installed, not
 * enabled yet or whose API changed answers nothing (reported once).
 *
 * <h2>Threads</h2>
 * Ask from the thread that owns the block or entity, where its events run.
 *
 * @since 1.478.0
 */
public final class CustomBlocks {

    private CustomBlocks() {
    }

    /**
     * The custom block at a position, or furniture anchored there.
     *
     * @param block the block
     * @return its id, or {@code null} for a vanilla block or when no provider runs
     */
    public static @Nullable String id(@NotNull Block block) {
        return CustomBlockIds.id(Objects.requireNonNull(block, "block"));
    }

    /**
     * The furniture an entity belongs to (its display, seat or hitbox entity,
     * whichever the provider answers for).
     *
     * @param entity the entity
     * @return the furniture's id, or {@code null} when it is not furniture
     */
    public static @Nullable String id(@NotNull Entity entity) {
        return CustomBlockIds.id(Objects.requireNonNull(entity, "entity"));
    }
}
