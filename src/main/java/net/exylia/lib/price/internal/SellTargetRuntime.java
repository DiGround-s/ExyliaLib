package net.exylia.lib.price.internal;

import net.exylia.lib.price.SellTarget;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Who sells blocks a wand is used on, in registration order, per plugin, released with it. */
public final class SellTargetRuntime {

    private record Registered(String plugin, SellTarget target) {
    }

    private static final Logger LOG = Logger.getLogger("ExyliaLib");
    private static final List<Registered> TARGETS = new CopyOnWriteArrayList<>();

    private SellTargetRuntime() {
    }

    public static void register(@NotNull String plugin, @NotNull SellTarget target) {
        TARGETS.add(new Registered(plugin, target));
    }

    public static void unregister(@NotNull SellTarget target) {
        TARGETS.removeIf(registered -> registered.target() == target);
    }

    public static void release(@NotNull String plugin) {
        TARGETS.removeIf(registered -> registered.plugin().equals(plugin));
    }

    /** The first target that claims the block, or {@code null}; one that throws is logged and skipped. */
    public static @Nullable SellTarget claimer(@NotNull Block block) {
        for (Registered registered : TARGETS) {
            try {
                if (registered.target().claims(block)) return registered.target();
            } catch (RuntimeException broken) {
                LOG.log(Level.WARNING, "Sell target of " + registered.plugin() + " failed; skipped.", broken);
            }
        }
        return null;
    }

    public static @NotNull Optional<CompletableFuture<SellTarget.Sold>> sell(@NotNull Player player, @NotNull Block block,
                                                                             double factor) {
        SellTarget target = claimer(block);
        if (target == null) return Optional.empty();
        try {
            return Optional.of(target.sell(player, block, factor));
        } catch (RuntimeException broken) {
            LOG.log(Level.WARNING, "Sell target failed to sell; nothing sold.", broken);
            return Optional.of(CompletableFuture.completedFuture(SellTarget.Sold.NOTHING));
        }
    }
}
