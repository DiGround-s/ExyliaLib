package net.exylia.lib.price;

import net.exylia.lib.price.internal.SellTargetRuntime;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Blocks a sell wand can sell that are not containers. A wand plugin asks;
 * the plugin that owns the block answers, and neither depends on the other.
 *
 * <pre>{@code
 * // answering: a spawner plugin sells its storage
 * SellTargets.register(this, new SellTarget() {
 *     public boolean claims(Block block) { return spawners.at(block).isPresent(); }
 *     public CompletableFuture<SellTarget.Sold> sell(Player player, Block block, double factor) {
 *         return selling.sell(player, spawners.at(block).orElseThrow(), factor);
 *     }
 * });
 *
 * // asking: a wand used on something that is no chest
 * if (!SellTargets.claims(block)) return;
 * SellTargets.sell(player, block, wand.multiplier()).ifPresent(sale ->
 *         sale.thenAccept(sold -> { if (!sold.isEmpty()) spendUse(); }));
 * }</pre>
 *
 * <p>Targets are asked in the order they were registered and the first that
 * claims the block sells it. One that throws is logged and skipped. What a
 * plugin registered is released when it disables.
 *
 * @since 1.435.0
 */
public final class SellTargets {

    private SellTargets() {
        throw new AssertionError("No instances.");
    }

    /** Adds a target. Released with the plugin. */
    public static void register(@NotNull Plugin plugin, @NotNull SellTarget target) {
        SellTargetRuntime.register(plugin.getName(), target);
    }

    /** Takes a target away before its plugin disables. */
    public static void unregister(@NotNull SellTarget target) {
        SellTargetRuntime.unregister(target);
    }

    /** Whether some target sells this block. Memory only. */
    public static boolean claims(@NotNull Block block) {
        return SellTargetRuntime.claimer(block) != null;
    }

    /**
     * Sells what a block holds through the first target that claims it.
     * Call it on the block's region thread.
     *
     * @return empty when no target claims the block
     */
    public static @NotNull Optional<CompletableFuture<SellTarget.Sold>> sell(@NotNull Player player, @NotNull Block block,
                                                                             double factor) {
        return SellTargetRuntime.sell(player, block, factor);
    }
}
