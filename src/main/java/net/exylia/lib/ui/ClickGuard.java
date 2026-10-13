package net.exylia.lib.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One physical click, one handling.
 *
 * <p>The vanilla client sends more than one packet for some single gestures. A
 * shift-double-click sends a {@code QUICK_MOVE} on press and, on release, one
 * more {@code QUICK_MOVE} for every slot in the window holding the same item
 * type; a plain double-click follows its {@code PICKUP} with a
 * {@code DOUBLE_CLICK}. Each arrives as its own {@code InventoryClickEvent}, so a
 * button handled per event runs two or more times.
 *
 * <p>Every ExyliaLib menu goes through this guard already. A plugin running its
 * own Bukkit inventory calls {@link #tryClick(InventoryClickEvent)} after
 * cancelling the event and acts only when it returns {@code true}.
 *
 * <p>The guard is per player, not per window: the burst a gesture produces
 * ignores which screen the server thinks is open.
 *
 * @since 1.295.0
 */
public final class ClickGuard {

    /**
     * Three ticks. A shift-double-click's release burst follows the press by as
     * long as the button is held, typically 60-120 ms, which the old two-tick
     * gap let through; 150 ms still allows more than six deliberate presses a
     * second on a +/- button.
     */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMillis(150);

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    private static volatile long cooldownNanos = DEFAULT_COOLDOWN.toNanos();
    private static volatile LongSupplier clock = System::nanoTime;

    private ClickGuard() {
    }

    /**
     * The server-wide cooldown, used when a menu does not set its own.
     *
     * @return the cooldown
     */
    public static @NotNull Duration cooldown() {
        return Duration.ofNanos(cooldownNanos);
    }

    /**
     * Changes the server-wide cooldown. {@link Duration#ZERO} turns the guard off
     * for every menu that does not set {@code click_cooldown}.
     *
     * @param cooldown the new cooldown, not negative
     */
    public static void cooldown(@NotNull Duration cooldown) {
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("Click cooldown cannot be negative: " + cooldown);
        }
        cooldownNanos = cooldown.toNanos();
    }

    /**
     * Whether a click should be acted on, for a raw Bukkit inventory.
     *
     * <p>A {@code DOUBLE_CLICK} is never a separate intent: the click before it
     * already ran, so it is always refused. Does not cancel the event; that is
     * still the caller's job, and a refused click should still be cancelled.
     *
     * @param event the click
     * @return {@code true} to act on it
     */
    public static boolean tryClick(@NotNull InventoryClickEvent event) {
        return event.getClick() != ClickType.DOUBLE_CLICK
                && tryClick(event.getWhoClicked().getUniqueId());
    }

    /**
     * Whether a player's click should be acted on, claiming it when it should.
     *
     * @param player who clicked
     * @return {@code false} if their previous accepted click was within the cooldown
     */
    public static boolean tryClick(@NotNull Player player) {
        return tryClick(player.getUniqueId());
    }

    /**
     * Whether a player's click should be acted on, claiming it when it should.
     *
     * @param player who clicked
     * @return {@code false} if their previous accepted click was within the cooldown
     */
    public static boolean tryClick(@NotNull UUID player) {
        return tryClick(player, cooldownNanos);
    }

    /**
     * Same as {@link #tryClick(UUID)} with a cooldown of its own.
     *
     * @param player   who clicked
     * @param cooldown how close together two accepted clicks may be
     * @return {@code false} if their previous accepted click was within the cooldown
     */
    public static boolean tryClick(@NotNull UUID player, @NotNull Duration cooldown) {
        return tryClick(player, cooldown.toNanos());
    }

    static boolean tryClick(UUID player, long cooldown) {
        if (cooldown <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        boolean[] accepted = {false};
        LAST.compute(player, (id, last) -> {
            if (last != null && now - last < cooldown) {
                return last;
            }
            accepted[0] = true;
            return now;
        });
        return accepted[0];
    }

    /**
     * Forgets a player. ExyliaLib calls this on quit.
     *
     * @param player the player
     */
    public static void forget(@NotNull UUID player) {
        LAST.remove(player);
    }

    /** For tests: replaces the nanosecond clock. */
    static void setClock(@NotNull LongSupplier replacement) {
        clock = replacement;
    }

    /** For tests: restores the real clock and forgets everyone. */
    static void resetClock() {
        clock = System::nanoTime;
        LAST.clear();
    }
}
