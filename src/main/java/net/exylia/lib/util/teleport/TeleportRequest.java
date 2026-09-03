package net.exylia.lib.util.teleport;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.teleport.internal.TeleportPlan;
import net.exylia.lib.util.teleport.internal.TeleportRuntime;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * A teleport being described, before it is started.
 *
 * <pre>{@code
 * teleports.to(player, spawn)
 *         .warmup(3.0)
 *         .cancelOnMove()
 *         .cooldown("warp", 30.0)
 *         .safe()
 *         .onStart(config.warpStarting())
 *         .onArrive(config.warpArrived())
 *         .onCancel(config.warpCancelled())
 *         .onTick(left -> Text.of("{primary}Teleporting in {highlight}%time%")
 *                 .with("time", TimeFormats.render(left))
 *                 .send(player))
 *         .start();
 * }</pre>
 *
 * <h2>Why a builder rather than a method with nine parameters</h2>
 * Almost every teleport wants two of these and none of the rest. A respawn
 * wants nothing but the destination; a warp wants a countdown, a cooldown and
 * three effects. Written as parameters, the first caller passes seven nulls,
 * and the day somebody swaps two booleans by mistake the compiler says nothing.
 *
 * <h2>Nothing happens until {@link #start()}</h2>
 * Describing a teleport is free and does not touch the player. This matters for
 * the cooldown in particular: the key is only claimed when the teleport is
 * actually started, so a request built and thrown away never charges anybody.
 *
 * <h2>Threading</h2>
 * Safe to build from any thread. {@link #start()} may also be called from any
 * thread — it hops onto the player's own before touching them.
 *
 * @since 1.34.0
 */
public final class TeleportRequest {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final Player player;
    private final TeleportSettings settings;

    private final @Nullable Location destination;
    private final @Nullable TeleportResult failure;

    /**
     * The other two ways of saying where, set by the module rather than by the
     * caller.
     *
     * <p>Not constructor parameters because a request is built by four public
     * methods that all mean "a place", and a fifth and sixth parameter that are
     * {@code null} at every one of those call sites is a signature that
     * documents nothing. The two methods that do mean something else —
     * {@code random} and a cross-server handover — say so by name.
     */
    private @Nullable RandomArea random;
    private @Nullable ExyliaLocation crossServer;
    private @Nullable UUID follow;

    /**
     * The module's own answer to how this ended.
     *
     * <p>Kept apart from {@link #then} rather than folded into it, because the
     * caller owns that one: a {@code /back} that has to put its entry back when
     * the teleport fails would lose that the moment a consumer called
     * {@code then()} on the request it was handed.
     */
    private @Nullable Consumer<TeleportResult> bookkeeping;

    private double warmupSeconds;
    private boolean cancelOnMove;
    private boolean cancelOnDamage;
    private boolean safe;
    private TeleportCause cause = TeleportCause.PLUGIN;

    private @Nullable String cooldownKey;
    private double cooldownSeconds;

    private @Nullable EffectConfig onStart;
    private @Nullable EffectConfig onArrive;
    private @Nullable EffectConfig onCancel;
    private @Nullable DoubleConsumer onTick;
    private @Nullable Consumer<TeleportResult> then;

    TeleportRequest(@NotNull Plugin plugin, @NotNull TaskScheduler tasks, @NotNull Debug debug,
                    @NotNull Player player, @Nullable Location destination,
                    @Nullable TeleportResult failure, @NotNull TeleportSettings settings) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.debug = debug;
        this.player = Objects.requireNonNull(player, "player");
        this.destination = destination;
        this.failure = failure;
        this.settings = settings;
        // The owner's defaults, which the caller may still override below. A
        // request that says nothing behaves the way the server was configured.
        this.warmupSeconds = settings.warmupSeconds();
        this.cancelOnMove = settings.cancelOnMove();
        this.cancelOnDamage = settings.cancelOnDamage();
    }

    /** Aims this request at an area to be searched once the countdown ends. */
    @NotNull TeleportRequest searching(@NotNull RandomArea area) {
        this.random = Objects.requireNonNull(area, "area");
        return this;
    }

    /** Aims this request at a place on another server. */
    @NotNull TeleportRequest handingOverTo(@NotNull ExyliaLocation elsewhere) {
        this.crossServer = Objects.requireNonNull(elsewhere, "elsewhere");
        return this;
    }

    /** Aims this request at wherever a player is on the network, once the countdown ends. */
    @NotNull TeleportRequest following(@NotNull UUID target) {
        this.follow = Objects.requireNonNull(target, "target");
        return this;
    }

    /** Gives the module its own end-callback, which the caller cannot replace. */
    @NotNull TeleportRequest bookkeeping(@NotNull Consumer<TeleportResult> listener) {
        this.bookkeeping = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Makes the player wait this long before being moved.
     *
     * <p>Seconds, with decimals: {@code 3.5} is three and a half. Zero removes
     * the countdown that the configuration may have set.
     *
     * @param seconds how long to wait
     * @return this
     */
    public @NotNull TeleportRequest warmup(double seconds) {
        this.warmupSeconds = Double.isFinite(seconds) ? Math.max(0, seconds) : 0;
        return this;
    }

    /**
     * Cancels the countdown if the player leaves the block they started on.
     *
     * @return this
     */
    public @NotNull TeleportRequest cancelOnMove() {
        return cancelOnMove(true);
    }

    /**
     * Whether leaving the starting block cancels the countdown.
     *
     * @param cancel whether to cancel
     * @return this
     */
    public @NotNull TeleportRequest cancelOnMove(boolean cancel) {
        this.cancelOnMove = cancel;
        return this;
    }

    /**
     * Cancels the countdown if the player takes damage.
     *
     * @return this
     */
    public @NotNull TeleportRequest cancelOnDamage() {
        return cancelOnDamage(true);
    }

    /**
     * Whether taking damage cancels the countdown.
     *
     * @param cancel whether to cancel
     * @return this
     */
    public @NotNull TeleportRequest cancelOnDamage(boolean cancel) {
        this.cancelOnDamage = cancel;
        return this;
    }

    /**
     * Lands the player on the nearest spot they can survive.
     *
     * <p>Worth asking for whenever the destination came from somewhere that
     * does not know what is there now: a stored home in a world that has since
     * been built on, a random coordinate, a warp set before a WorldEdit.
     *
     * @return this
     */
    public @NotNull TeleportRequest safe() {
        return safe(true);
    }

    /**
     * Whether to land the player on the nearest survivable spot.
     *
     * @param adjust whether to search
     * @return this
     */
    public @NotNull TeleportRequest safe(boolean adjust) {
        this.safe = adjust;
        return this;
    }

    /**
     * Refuses the teleport when this key is already on cooldown, and claims it
     * when it is not.
     *
     * <p>The key goes through {@link Cooldowns}, so it is the same cooldown
     * everything else in the ecosystem sees, and it survives a restart when it
     * is long enough to be worth saving. Prefix it with something of your own:
     * two plugins using {@code "warp"} share one cooldown.
     *
     * <p>A cancelled countdown gives the cooldown back. The player never got
     * the teleport, and charging them for one they did not receive is a bug.
     *
     * @param key     the cooldown key
     * @param seconds how long it lasts
     * @return this
     */
    public @NotNull TeleportRequest cooldown(@NotNull String key, double seconds) {
        this.cooldownKey = Objects.requireNonNull(key, "key");
        this.cooldownSeconds = Math.max(0, seconds);
        return this;
    }

    /**
     * Says why this teleport is happening, for whoever is listening.
     *
     * @param cause the reason
     * @return this
     */
    public @NotNull TeleportRequest cause(@NotNull TeleportCause cause) {
        this.cause = Objects.requireNonNull(cause, "cause");
        return this;
    }

    /**
     * Plays a configured effect when the countdown begins.
     *
     * @param effect the effect from the plugin's config, or {@code null}
     * @return this
     */
    public @NotNull TeleportRequest onStart(@Nullable EffectConfig effect) {
        this.onStart = effect;
        return this;
    }

    /**
     * Plays a configured effect when the player arrives.
     *
     * @param effect the effect from the plugin's config, or {@code null}
     * @return this
     */
    public @NotNull TeleportRequest onArrive(@Nullable EffectConfig effect) {
        this.onArrive = effect;
        return this;
    }

    /**
     * Plays a configured effect when the teleport is called off.
     *
     * @param effect the effect from the plugin's config, or {@code null}
     * @return this
     */
    public @NotNull TeleportRequest onCancel(@Nullable EffectConfig effect) {
        this.onCancel = effect;
        return this;
    }

    /**
     * Reports how much of the countdown is left, four times a second.
     *
     * <p>For an action bar that counts down. Given seconds with decimals,
     * because a countdown that reads {@code 3, 3, 3, 2} looks broken next to
     * one that reads {@code 3.0, 2.8, 2.5}.
     *
     * @param listener told the seconds remaining
     * @return this
     */
    public @NotNull TeleportRequest onTick(@Nullable DoubleConsumer listener) {
        this.onTick = listener;
        return this;
    }

    /**
     * Told how the teleport ended, however it ended.
     *
     * <p>Runs on whichever thread ended it. It is called exactly once, and it
     * is called for the failures too — a caller that only handles success is a
     * caller whose player is never told why nothing happened.
     *
     * @param listener told the result
     * @return this
     */
    public @NotNull TeleportRequest then(@Nullable Consumer<TeleportResult> listener) {
        this.then = listener;
        return this;
    }

    /**
     * Starts it.
     *
     * <p>In order: the cooldown is claimed, and a busy one ends the request
     * immediately with {@link TeleportResult#ON_COOLDOWN} without playing
     * anything or moving anybody. Then, with no countdown, the destination is
     * resolved and the player is moved. With one, the start effect plays and
     * the countdown runs until it elapses, the player moves, they are hit, or
     * they leave.
     *
     * @return the running teleport, which may already be finished
     */
    public @NotNull TeleportHandle start() {
        if (failure != null || (destination == null && random == null && crossServer == null
                && follow == null)) {
            // The request was built from something unusable — an unparseable
            // stored string, or nowhere recorded to go back to. Reported when it
            // was built; answered here.
            return DoneTeleport.of(player, cause,
                    failure == null ? TeleportResult.FAILED : failure, bookkeeping, then, debug);
        }
        if (cooldownKey != null && cooldownSeconds > 0
                && !Cooldowns.tryStart(player, cooldownKey, ofSeconds(cooldownSeconds))) {
            // Nothing plays and nobody moves. Whoever wants to tell the player
            // how long is left asks Cooldowns, which still knows.
            return DoneTeleport.of(player, cause, TeleportResult.ON_COOLDOWN,
                    bookkeeping, then, debug);
        }

        TeleportPlan plan = new TeleportPlan(plugin, player, destination, random, crossServer, follow,
                cause, tasks, debug, settings,
                Ticks.fromSeconds(warmupSeconds), cancelOnMove, cancelOnDamage, safe,
                settings.safeSearchRadius(), settings.safeMaxAttempts(),
                cooldownSeconds > 0 ? cooldownKey : null,
                onStart, onArrive, onCancel, onTick, bookkeeping, then);
        return TeleportRuntime.start(plan);
    }

    /** Seconds with decimals as a Duration, without losing the decimals. */
    private static Duration ofSeconds(double seconds) {
        return Duration.ofMillis(Math.round(seconds * 1000.0));
    }

    /**
     * A teleport that was over before it began.
     *
     * <p>Returned rather than {@code null} so a caller never has to check: a
     * refused teleport answers the same questions as one that ran.
     */
    private record DoneTeleport(Player player, TeleportCause cause,
                                CompletableFuture<TeleportResult> future) implements TeleportHandle {

        /**
         * The module's callback runs first, and a throw in either does not
         * skip the other.
         *
         * <p>The same order and the same guarding as a teleport that actually
         * ran, because a {@code /back} refused by a cooldown is exactly as much
         * of a back-teleport the player never received as one cancelled halfway
         * through its countdown, and the entry has to go back either way.
         */
        static TeleportHandle of(Player player, TeleportCause cause, TeleportResult result,
                                 @Nullable Consumer<TeleportResult> bookkeeping,
                                 @Nullable Consumer<TeleportResult> then, Debug debug) {
            DoneTeleport done = new DoneTeleport(player, cause,
                    CompletableFuture.completedFuture(result));
            call(bookkeeping, result, debug, "A teleport's own bookkeeping threw");
            call(then, result, debug, "A teleport callback threw");
            return done;
        }

        private static void call(@Nullable Consumer<TeleportResult> listener,
                                 TeleportResult result, Debug debug, String complaint) {
            if (listener == null) {
                return;
            }
            try {
                listener.accept(result);
            } catch (RuntimeException failed) {
                debug.error(complaint, failed);
            }
        }

        @Override
        public void cancel() {
            // Nothing left to call off.
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public double remainingWarmupSeconds() {
            return 0.0;
        }
    }
}
