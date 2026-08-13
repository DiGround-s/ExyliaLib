package net.exylia.lib.skull.internal;

import net.exylia.lib.skull.SkullHandle;
import net.exylia.lib.skull.SkullSource;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * Creates handles and wires them to the lookup.
 *
 * <p>Separate from {@link Handle} so the public builder has one internal entry
 * point, and separate from {@link SkullRuntime} so the runtime stays about
 * textures rather than items.
 */
public final class Handles {

    private Handles() {
    }

    /**
     * Builds a handle for a source, starting a lookup only if one is needed.
     *
     * @param source    where the texture comes from
     * @param decorator applied to every item the handle produces
     * @param viewer    whose thread the callback runs on, may be {@code null}
     * @return the handle
     */
    public static SkullHandle create(SkullSource source,
                                     UnaryOperator<ItemStack> decorator,
                                     @Nullable Player viewer) {
        String known = SkullRuntime.cached(source);
        Handle handle = new Handle(source, known, decorator, viewer);
        if (known != null) {
            // Warm: no task, no future, no allocation beyond the handle. This
            // is the path a menu takes every time after the first.
            return handle;
        }
        if (!source.needsLookup()) {
            return handle;
        }
        SkullRuntime.resolve(source).thenAccept(handle::complete);
        return handle;
    }
}
