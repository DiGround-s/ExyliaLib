package net.exylia.lib.skull.internal;

import net.exylia.lib.skull.SkullHandle;
import net.exylia.lib.skull.SkullSource;
import net.exylia.lib.skull.Skulls;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * The heads a render drew before their texture was known.
 *
 * <p>A renderer answers with whatever {@link Skulls} has now, which for a
 * player nobody has fetched yet is the fallback head. Whoever drew it — a menu
 * slot — wants to know which heads are still on their way so it can draw them
 * again when they land, instead of leaving "unknown" on screen until the menu
 * is reopened.
 *
 * <p>Collected per thread, so the renderer's signatures stay as they are: a
 * caller wraps the render in {@link #collect} and gets the handles back.
 */
public final class PendingHeads {

    private static final ThreadLocal<List<SkullHandle>> SINK = new ThreadLocal<>();

    private PendingHeads() {
    }

    /**
     * A head as it is now, noting it when it is still being fetched.
     *
     * @param source the head
     * @param viewer whose thread the late callback runs on, or {@code null}
     * @return the head, plain when its texture has not landed
     */
    public static ItemStack item(SkullSource source, @Nullable Player viewer) {
        SkullHandle handle = Skulls.of(source).viewer(viewer).build();
        List<SkullHandle> sink = SINK.get();
        if (sink != null && !handle.isReady()) {
            sink.add(handle);
        }
        return handle.item();
    }

    /**
     * Runs a render and adds every head it drew unfinished to {@code into}.
     *
     * <p>Nests: an outer collector also receives what an inner one caught.
     *
     * @param work  the render
     * @param into  where the unfinished heads go
     * @return what the render returned
     */
    public static <T> T collect(Supplier<T> work, List<SkullHandle> into) {
        List<SkullHandle> outer = SINK.get();
        SINK.set(into);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                SINK.remove();
            } else {
                SINK.set(outer);
                outer.addAll(into);
            }
        }
    }
}
