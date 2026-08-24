package net.exylia.lib.skull;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A head that may still be on its way.
 *
 * <p>The answer to the problem every skull menu has: the item has to exist
 * now, and the texture may not arrive for another few hundred milliseconds.
 * A handle is the item, immediately, plus a way to hear about the real one.
 *
 * <pre>{@code
 * SkullHandle handle = skulls.get(SkullSource.player("Notch"));
 * inventory.setItem(slot, handle.item());        // drawn now, plain if cold
 * handle.onReady(head -> inventory.setItem(slot, head));  // swapped when it lands
 * }</pre>
 *
 * <p>When the head was cached — which, after the first time, is nearly always
 * — {@link #isReady()} is already {@code true} and {@link #onReady} runs
 * straight away. Callers do not branch on it: the cold path and the warm path
 * are written the same way.
 *
 * @since 1.19.0
 */
public interface SkullHandle {

    /**
     * The item to show right now.
     *
     * <p>The finished head when it is known, and the library's configured
     * fallback texture when it is not. Never {@code null}, so a menu never
     * has an empty slot.
     *
     * <p>A fresh copy each time: item stacks are mutable, and a cached one
     * handed out directly would be renamed by the first caller for everybody.
     *
     * @return the item
     */
    @NotNull ItemStack item();

    /**
     * Returns whether the texture is already known.
     *
     * @return {@code true} when {@link #item()} is the finished head
     */
    boolean isReady();

    /**
     * Runs an action when the finished head is available.
     *
     * <p>Runs on the main thread — or, on Folia, on the thread that owns the
     * viewer — so it is safe to touch inventories from it. If the head is
     * already known, the action runs immediately and in the caller's thread.
     *
     * <p>Runs at most once, and never if the lookup fails. A menu that shows
     * a plain head forever is a cosmetic problem; a menu that gets a callback
     * with nothing in it is a null check in every plugin.
     *
     * @param action what to do with the finished head
     * @return this handle
     */
    @NotNull SkullHandle onReady(@NotNull java.util.function.Consumer<ItemStack> action);

    /**
     * Cancels the callback.
     *
     * <p>Called when a menu closes: without it, a player who closes a menu
     * before the heads land leaves a callback holding an inventory that
     * nobody is looking at.
     */
    void cancel();

    /**
     * The texture backing this head, once known.
     *
     * @return the base64 texture, or {@code null} while it is unknown
     */
    @Nullable String texture();
}
