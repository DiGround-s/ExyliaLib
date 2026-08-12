package net.exylia.lib.util;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player cooldowns.
 *
 * <p>The thing every plugin rewrites: an ability on a timer, a command that
 * cannot be spammed, a kit that refreshes once a minute.
 *
 * <pre>{@code
 * if (Cooldowns.isActive(player, "pearl")) {
 *     player.sendMessage("Wait " + Cooldowns.remainingSeconds(player, "pearl") + "s");
 *     return;
 * }
 * Cooldowns.start(player, "pearl", Duration.ofSeconds(16));
 * }</pre>
 *
 * <h2>Cost</h2>
 * A check is two map lookups and a subtraction — no allocation, no scanning,
 * no scheduled task. Cooldowns are not ticked down: an expiry instant is
 * stored once and compared on read, so a thousand idle cooldowns cost exactly
 * nothing until somebody asks about them.
 *
 * <p>Expired entries are dropped the moment they are read, and everything a
 * player owns is dropped when they leave, so the map cannot grow without
 * bound.
 *
 * <h2>Keys</h2>
 * A key is any string the caller picks. Two plugins using the same key share
 * the cooldown, which is a feature when it is deliberate ("global-teleport")
 * and a bug when it is not, so prefix keys that should be private.
 *
 * <h2>Threading</h2>
 * Safe from any thread.
 *
 * @since 1.10.0
 */
public final class Cooldowns {

    private Cooldowns() {
        throw new AssertionError("No instances.");
    }

    /**
     * Per-player map of key to expiry instant, in milliseconds.
     *
     * <p>Nested rather than flat so that forgetting a player is a single
     * removal instead of a scan of every cooldown on the server.
     */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new ConcurrentHashMap<>();

    /** The clock. Injectable so tests do not sleep. */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    /** Puts a key on cooldown for a duration. */
    public static void start(@NotNull Player player, @NotNull String key,
                             @NotNull Duration duration) {
        start(player.getUniqueId(), key, duration);
    }

    /** Puts a key on cooldown for a duration. */
    public static void start(@NotNull UUID player, @NotNull String key,
                             @NotNull Duration duration) {
        long millis = duration.toMillis();
        if (millis <= 0) {
            // A zero or negative cooldown is not a cooldown. Clearing rather
            // than storing keeps a stale entry from outliving its own expiry.
            clear(player, key);
            return;
        }
        COOLDOWNS.computeIfAbsent(player, id -> new ConcurrentHashMap<>())
                .put(key, clock.getAsLong() + millis);
    }

    /** Puts a key on cooldown for a number of seconds. */
    public static void startSeconds(@NotNull Player player, @NotNull String key, long seconds) {
        start(player.getUniqueId(), key, Duration.ofSeconds(seconds));
    }

    /** Puts a key on cooldown for a number of ticks. */
    public static void startTicks(@NotNull Player player, @NotNull String key, long ticks) {
        start(player.getUniqueId(), key, Duration.ofMillis(ticks * 50L));
    }

    // ------------------------------------------------------------------
    // Asking
    // ------------------------------------------------------------------

    /** Returns whether a key is still on cooldown. */
    public static boolean isActive(@NotNull Player player, @NotNull String key) {
        return isActive(player.getUniqueId(), key);
    }

    /** Returns whether a key is still on cooldown. */
    public static boolean isActive(@NotNull UUID player, @NotNull String key) {
        return remaining(player, key) > 0;
    }

    /** Returns what is left, or {@link Duration#ZERO} when nothing is. */
    public static @NotNull Duration remaining(@NotNull Player player, @NotNull String key) {
        return Duration.ofMillis(remaining(player.getUniqueId(), key));
    }

    /** Returns the milliseconds left, or {@code 0} when nothing is. */
    public static long remaining(@NotNull UUID player, @NotNull String key) {
        Map<String, Long> owned = COOLDOWNS.get(player);
        if (owned == null) {
            return 0L;
        }
        Long expiry = owned.get(key);
        if (expiry == null) {
            return 0L;
        }
        long left = expiry - clock.getAsLong();
        if (left <= 0) {
            // Expired entries are dropped on read: nothing scans the map, so
            // this is the only moment the garbage can be noticed.
            owned.remove(key);
            if (owned.isEmpty()) {
                COOLDOWNS.remove(player, owned);
            }
            return 0L;
        }
        return left;
    }

    /** Returns the seconds left, rounded up, or {@code 0} when nothing is. */
    public static long remainingSeconds(@NotNull Player player, @NotNull String key) {
        long millis = remaining(player.getUniqueId(), key);
        // Rounded up, because "1 second left" reading as 0 is a lie to the
        // player looking at the message.
        return (millis + 999L) / 1000L;
    }

    /**
     * Starts the cooldown and returns whether it was free to begin with.
     *
     * <p>The whole guard in one call, which is how it is almost always used:
     *
     * <pre>{@code
     * if (!Cooldowns.tryStart(player, "pearl", Duration.ofSeconds(16))) {
     *     return; // still on cooldown
     * }
     * }</pre>
     *
     * @return {@code true} when the cooldown was not active and has now been
     *         started, {@code false} when it was already running
     */
    public static boolean tryStart(@NotNull Player player, @NotNull String key,
                                   @NotNull Duration duration) {
        if (isActive(player.getUniqueId(), key)) {
            return false;
        }
        start(player.getUniqueId(), key, duration);
        return true;
    }

    // ------------------------------------------------------------------
    // Clearing
    // ------------------------------------------------------------------

    /** Ends one cooldown early. */
    public static void clear(@NotNull Player player, @NotNull String key) {
        clear(player.getUniqueId(), key);
    }

    /** Ends one cooldown early. */
    public static void clear(@NotNull UUID player, @NotNull String key) {
        Map<String, Long> owned = COOLDOWNS.get(player);
        if (owned != null) {
            owned.remove(key);
            if (owned.isEmpty()) {
                COOLDOWNS.remove(player, owned);
            }
        }
    }

    /** Ends every cooldown a player has. */
    public static void clearAll(@NotNull Player player) {
        COOLDOWNS.remove(player.getUniqueId());
    }

    /** Forgets a player entirely. Called when they leave. */
    public static void forget(@NotNull UUID player) {
        COOLDOWNS.remove(player);
    }

    /** Forgets everybody. Called on shutdown. */
    public static void clearEverything() {
        COOLDOWNS.clear();
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** For tests: replaces the clock so time can be moved without sleeping. */
    static void setClock(@NotNull LongSupplier replacement) {
        clock = replacement;
    }

    /** For tests: restores the real clock. */
    static void resetClock() {
        clock = System::currentTimeMillis;
    }

    /** For tests: how many players hold at least one cooldown. */
    static int trackedPlayers() {
        return COOLDOWNS.size();
    }
}
