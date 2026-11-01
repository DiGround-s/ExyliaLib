package net.exylia.lib.price;

import net.exylia.lib.price.internal.SellTargetRuntime;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellTargetsTest {

    private static SellTarget target(Predicate<Block> claims, long items) {
        return new SellTarget() {
            @Override public boolean claims(Block block) { return claims.test(block); }
            @Override public CompletableFuture<Sold> sell(Player player, Block block, double factor) {
                return CompletableFuture.completedFuture(new Sold(items, BigDecimal.valueOf(factor)));
            }
        };
    }

    @AfterEach
    void clear() {
        SellTargetRuntime.release("A");
        SellTargetRuntime.release("B");
    }

    @Test
    void firstClaimingTargetSellsAndBrokenOnesAreSkipped() {
        SellTargetRuntime.register("A", target(block -> { throw new IllegalStateException("broken"); }, 1));
        SellTargetRuntime.register("A", target(block -> false, 2));
        SellTargetRuntime.register("B", target(block -> true, 3));

        SellTarget.Sold sold = SellTargets.sell(null, null, 2.0).orElseThrow().join();

        assertEquals(3, sold.items());
        assertEquals(BigDecimal.valueOf(2.0), sold.money());
    }

    @Test
    void releasedTargetsAreNotAsked() {
        SellTargetRuntime.register("B", target(block -> true, 1));
        SellTargetRuntime.release("B");

        assertFalse(SellTargets.claims(null));
        assertTrue(SellTargets.sell(null, null, 1.0).isEmpty());
    }
}
