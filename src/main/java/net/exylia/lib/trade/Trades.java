package net.exylia.lib.trade;

import net.exylia.lib.escrow.Escrow;
import net.exylia.lib.session.Sessions;
import net.exylia.lib.session.Stays;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.trade.internal.TradeSession;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A two-player trade window: both put items in, both see both offers live,
 * both confirm, and a short countdown that any change resets stops a
 * last-second swap.
 *
 * <pre>{@code
 * private Trades trades;
 *
 * @Override
 * public void onEnable() {
 *     trades = Trades.of(this);     // opens the plugin's escrow: owed returns are delivered on join
 * }
 *
 * trades.open(challenger, target, TradeOptions.defaults().allowEmpty(false)).thenAccept(result -> {
 *     if (!result.confirmed()) return;                  // items already went back
 *     escrow.holdItems(duelId, result.first(), result.firstItems());
 *     escrow.holdItems(duelId, result.second(), result.secondItems());
 * });
 * }</pre>
 *
 * <h2>Items live in the window, and only there</h2>
 * Each player has their own window: their half holds their real items, the
 * other half a copy of the other player's, redrawn on every change. Nothing
 * else can put an item into or take one out of a copy. Closing, leaving,
 * dying, a timeout or the plugin stopping cancels the trade and gives each
 * player their own items back: into the inventory, and whatever does not fit
 * &mdash; or everything, for a player who is offline or dead &mdash; through
 * the plugin's {@link Escrow} owed rows, on their next join or respawn.
 *
 * <h2>Threads</h2>
 * {@link #open} may be called from any thread. Each window is built, clicked
 * and emptied on its own player's thread, so two players on two Folia regions
 * never touch each other's inventory. The future completes on whichever
 * thread emptied the last window.
 *
 * @since 1.349.0
 */
public final class Trades {

    private static final Map<String, Trades> OPEN = new ConcurrentHashMap<>();
    private static final Map<UUID, TradeSession> BUSY = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final Escrow escrow;

    private Trades(Plugin plugin) {
        this.plugin = plugin;
        this.escrow = Escrow.of(plugin);
    }

    /**
     * A plugin's trades. Call it in {@code onEnable}: it opens the plugin's
     * {@link Escrow}, which is what delivers items a cancelled trade could not
     * give back on the spot.
     *
     * @param plugin the plugin trades are opened for
     * @return the same instance for the same plugin load
     */
    public static @NotNull Trades of(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (key, open) -> open.plugin == plugin ? open : null);
        return OPEN.computeIfAbsent(plugin.getName(), key -> new Trades(plugin));
    }

    /** {@link #open(Player, Player, TradeOptions)} with {@link TradeOptions#defaults()}. */
    public @NotNull CompletableFuture<TradeResult> open(@NotNull Player first, @NotNull Player second) {
        return open(first, second, TradeOptions.defaults());
    }

    /**
     * Opens a trade window for two players.
     *
     * @param first   one player
     * @param second  the other
     * @param options how it looks and behaves
     * @return how it ended; {@link TradeOutcome#UNAVAILABLE} at once when a
     *         player is the same, offline, dead or already trading
     */
    public @NotNull CompletableFuture<TradeResult> open(@NotNull Player first, @NotNull Player second,
                                                        @NotNull TradeOptions options) {
        Objects.requireNonNull(options, "options");
        UUID a = first.getUniqueId();
        UUID b = second.getUniqueId();
        if (a.equals(b) || !first.isOnline() || !second.isOnline() || first.isDead() || second.isDead()
                || busyElsewhere(a) || busyElsewhere(b)) {
            return CompletableFuture.completedFuture(unavailable(a, b));
        }
        TradeSession session = new TradeSession(plugin, Tasks.of(plugin), escrow, first, second, options,
                done -> {
                    BUSY.remove(a, done);
                    BUSY.remove(b, done);
                });
        if (BUSY.putIfAbsent(a, session) != null) {
            return CompletableFuture.completedFuture(unavailable(a, b));
        }
        if (BUSY.putIfAbsent(b, session) != null) {
            BUSY.remove(a, session);
            return CompletableFuture.completedFuture(unavailable(a, b));
        }
        session.start();
        return session.future();
    }

    /**
     * Whether a player has a trade open, with any plugin.
     *
     * @param player the player
     * @return {@code true} while their trade window is open or being emptied
     */
    public static boolean isTrading(@NotNull UUID player) {
        return BUSY.containsKey(player);
    }

    /**
     * Whether another plugin answers for the player's belongings: it holds them
     * through {@link Sessions} (an arena, an event), or a stay of any plugin is
     * still being recovered. A hold of this plugin's own is fine: a duel claims
     * both players before it opens the bet.
     */
    private boolean busyElsewhere(UUID player) {
        return Sessions.holder(player).filter(claim -> !claim.plugin().equals(plugin.getName())).isPresent()
                || Stays.isRecovering(player);
    }

    private static TradeResult unavailable(UUID a, UUID b) {
        return new TradeResult(TradeOutcome.UNAVAILABLE, a, List.of(), b, List.of(), null);
    }

    // -------------------------------------------------------------- lifecycle

    /** Ends a leaving player's trade on their own thread, during the quit. Called by the library. */
    public static void left(@NotNull Player player) {
        TradeSession session = BUSY.get(player.getUniqueId());
        if (session != null) {
            session.left(player);
        }
    }

    /**
     * Cancels a plugin's open trades and gives every item back, right now,
     * before its tasks are cancelled. Called by the library.
     */
    public static void release(@NotNull String pluginName) {
        OPEN.remove(pluginName);
        for (TradeSession session : List.copyOf(BUSY.values())) {
            if (session.plugin().getName().equals(pluginName)) {
                session.shutdown();
            }
        }
    }

    /** Cancels every open trade, on shutdown. Called by the library. */
    public static void releaseAll() {
        for (TradeSession session : List.copyOf(BUSY.values())) {
            session.shutdown();
        }
        BUSY.clear();
        OPEN.clear();
    }
}
