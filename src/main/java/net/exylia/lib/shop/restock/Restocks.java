package net.exylia.lib.shop.restock;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point of restock shops: a limited stock of offers that rotates every
 * N minutes, with a per-player limit. See {@code docs/restocks.md}.
 *
 * <pre>{@code
 * Restock seeds = Restocks.of(this, "seeds")
 *         .every(Duration.ofMinutes(5)).slots(4).pool(offers)
 *         .scope(config.scope())          // default, global, server or a group
 *         .build();
 * seeds.take(player.getUniqueId(), "carrot", 2).thenAccept(ok -> { ... });
 * }</pre>
 *
 * @since 1.632.0
 */
public final class Restocks {

    private Restocks() {
    }

    /**
     * Starts a shop.
     *
     * @param plugin the plugin whose database keeps the purchases
     * @param id     the shop's id; every server using the same id shows the same stock
     */
    public static @NotNull Restock.Builder of(@NotNull Plugin plugin, @NotNull String id) {
        return new Restock.Builder(plugin, id);
    }
}
