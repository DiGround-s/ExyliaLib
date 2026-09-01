package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.Ticks;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.teleport.TeleportCause;
import net.exylia.lib.util.teleport.TeleportHandle;
import net.exylia.lib.util.teleport.TeleportResult;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One teleport, from the moment it was started until it ends.
 *
 * <h2>Ending exactly once</h2>
 * A countdown can be ended by six different things — arriving, moving, being
 * hit, quitting, {@code cancel()}, the plugin disabling — and several of them
 * can happen in the same tick. A single atomic claim decides which one wins, so
 * the effect plays once, the cooldown is refunded once, and the future is
 * completed once.
 *
 * <h2>Why the countdown ticks every five ticks rather than every one</h2>
 * A quarter of a second is finer than a player can read and twenty times less
 * work than ticking every tick. The remaining time is still reported with
 * decimals, so an action bar counting {@code 3.0, 2.8, 2.5} looks like a
 * countdown rather than a stutter.
 */
@ApiStatus.Internal
final class RunningTeleport implements TeleportHandle {

    /** How often the countdown reports itself: a quarter of a second. */
    private static final long TICK_PERIOD = 5L;

    private final TeleportPlan plan;
    private final CompletableFuture<TeleportResult> future = new CompletableFuture<>();
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final Runnable onFinished;

    /** Where the player stood when the countdown began, for the move check. */
    private final int startBlockX;
    private final int startBlockY;
    private final int startBlockZ;

    private volatile long remainingTicks;
    private volatile TaskHandle timer;

    RunningTeleport(@NotNull TeleportPlan plan, @NotNull Runnable onFinished) {
        this.plan = plan;
        this.onFinished = onFinished;
        this.remainingTicks = Math.max(0, plan.warmupTicks());
        Location standing = plan.player().getLocation();
        this.startBlockX = standing == null ? 0 : standing.getBlockX();
        this.startBlockY = standing == null ? 0 : standing.getBlockY();
        this.startBlockZ = standing == null ? 0 : standing.getBlockZ();
    }

    // ------------------------------------------------------------------ handle

    @Override
    public @NotNull CompletableFuture<TeleportResult> future() {
        return future;
    }

    @Override
    public void cancel() {
        end(TeleportResult.CANCELLED_MANUALLY);
    }

    @Override
    public boolean isDone() {
        return future.isDone();
    }

    @Override
    public @NotNull Player player() {
        return plan.player();
    }

    @Override
    public @NotNull TeleportCause cause() {
        return plan.cause();
    }

    @Override
    public double remainingWarmupSeconds() {
        if (future.isDone()) {
            return 0.0;
        }
        return Ticks.toSeconds(Math.max(0, remainingTicks));
    }

    // ------------------------------------------------------------------ start

    /** Runs the countdown, or moves the player now when there is none. */
    void begin() {
        if (plan.warmupTicks() <= 0) {
            perform();
            return;
        }
        // The warmup's own length, because the effect is the warmup being
        // shown: a file that could set the number itself could only ever
        // disagree with the teleport it is counting.
        play(plan.onStart(), remainingWarmupSeconds());
        // Reported once immediately: a countdown whose first frame only appears
        // a quarter of a second in reads as a delay before the delay.
        report();
        // On the player's own thread, and it dies with them: a countdown for
        // somebody who left has nothing to count down to.
        timer = plan.tasks().runAtEntityTimer(plan.player(), TICK_PERIOD, TICK_PERIOD, handle -> {
            if (future.isDone()) {
                handle.cancel();
                return;
            }
            remainingTicks -= TICK_PERIOD;
            if (remainingTicks <= 0) {
                handle.cancel();
                perform();
                return;
            }
            report();
        });
    }

    // ------------------------------------------------------------- interrupts

    /**
     * Ends the countdown if the player left the block they started on.
     *
     * <p>Block position rather than exact coordinates, so looking around and
     * the sub-block drift of standing still do not count. A player who wanted
     * to cancel walks; one who turned their head did not ask for anything.
     *
     * @param to where they moved to
     */
    void movedTo(@Nullable Location to) {
        if (!plan.cancelOnMove() || to == null) {
            return;
        }
        if (to.getBlockX() != startBlockX || to.getBlockY() != startBlockY
                || to.getBlockZ() != startBlockZ) {
            end(TeleportResult.CANCELLED_ON_MOVE);
        }
    }

    /** Ends the countdown if being hit is meant to end it. */
    void damaged() {
        if (plan.cancelOnDamage()) {
            end(TeleportResult.CANCELLED_ON_DAMAGE);
        }
    }

    /** Ends the countdown because the player is no longer here. */
    void playerLeft() {
        end(TeleportResult.PLAYER_LEFT);
    }

    // ------------------------------------------------------------------ doing

    /**
     * Resolves where the player is actually going, then moves them.
     *
     * <p>The safe search reads blocks, so it happens on the thread owning the
     * destination rather than wherever the countdown was ticking. That is a
     * different thread from the player's on Folia, and the same one everywhere
     * else.
     *
     * <h2>Why the three destinations are resolved here rather than earlier</h2>
     * All three of them are answered <em>after</em> the countdown, and that is
     * the whole reason they are three components rather than one resolved place
     * on the plan. Searching for a random spot before the countdown would load
     * chunks for a teleport the player is about to walk out of, and handing a
     * player over to another server before it would move somebody who then
     * cancelled.
     */
    private void perform() {
        if (!claim()) {
            return;
        }
        if (plan.random() != null) {
            findSomewhereRandom(plan.random());
            return;
        }
        if (plan.crossServer() != null) {
            CrossServer.hand(plan).thenAccept(this::finish);
            return;
        }
        Location destination = plan.destination();
        if (destination == null || destination.getWorld() == null) {
            finish(TeleportResult.WORLD_NOT_FOUND);
            return;
        }
        if (!plan.safe()) {
            move(destination);
            return;
        }
        plan.tasks().runAtLocation(destination, () -> {
            Location landing = SafeLocations.nearest(
                    destination, plan.safeRadius(), plan.safeAttempts());
            if (landing == null) {
                // Refused rather than dropped anywhere: a teleport that asked to
                // be safe and lands a player in lava is worse than one that did
                // not happen.
                finish(TeleportResult.NO_SAFE_LOCATION);
                return;
            }
            move(landing);
        });
    }

    /**
     * Looks for somewhere in the area, then moves them to whatever it found.
     *
     * <p>The search is not asked to be safe by the request: it only ever
     * returns places {@link SafeLocations} already approved, so asking again on
     * the way out would be a second search for the same answer.
     */
    private void findSomewhereRandom(net.exylia.lib.util.teleport.RandomArea area) {
        RandomLocations.search(area, plan.settings().randomMaxAttempts(),
                        plan.safeRadius(), plan.safeAttempts(), plan.tasks(), plan.debug())
                .thenAccept(landing -> {
                    if (landing == null) {
                        // Every attempt used up. Refused rather than dropped
                        // into the ocean the search kept finding, for the same
                        // reason the safe search refuses.
                        finish(TeleportResult.NO_SAFE_LOCATION);
                        return;
                    }
                    move(landing);
                });
    }

    private void move(Location landing) {
        Teleporter.teleport(plan.plugin(), plan.player(), landing, plan.cause(),
                        plan.tasks(), plan.debug(), plan.settings().backHistorySize())
                .thenAccept(this::finish);
    }

    // ------------------------------------------------------------------ ending

    /**
     * Ends this before it moved anybody.
     *
     * <p>The claim is what makes this idempotent, and it is also what stops a
     * cancel arriving during the teleport itself from refunding a cooldown for
     * a teleport that did happen.
     */
    private void end(TeleportResult result) {
        if (!claim()) {
            return;
        }
        TaskHandle running = timer;
        if (running != null) {
            running.cancel();
        }
        play(plan.onCancel());
        finish(result);
    }

    /**
     * Takes ownership of the ending, once.
     *
     * @return whether this caller is the one that gets to end it
     */
    private boolean claim() {
        return claimed.compareAndSet(false, true);
    }

    /** Completes the future, plays the arrival effect, and deregisters. */
    private void finish(TeleportResult result) {
        TaskHandle running = timer;
        if (running != null) {
            running.cancel();
        }
        if (result.isSuccess()) {
            play(plan.onArrive());
        } else {
            // The player never arrived. Leaving the cooldown running would
            // charge them for something they did not receive, and on a long
            // warp cooldown that is the difference between a cancelled
            // teleport and a lost one.
            //
            // Here rather than in end(), because a countdown is only one of
            // the ways a teleport fails to happen: another plugin vetoing it,
            // nowhere safe to land, a world that unloaded, or the move itself
            // failing all reach this method without going through end() at
            // all. Refunding there charged the player for every one of them.
            refundCooldown();
        }
        remainingTicks = 0;
        onFinished.run();
        // Completed before the callback so a callback that starts another
        // teleport does not find this one still counting as active.
        future.complete(result);
        // The module's own first, and separately guarded: a caller whose
        // listener throws must not cost a /back the entry it was owed, and the
        // module throwing must not cost the caller the answer they asked for.
        call(plan.bookkeeping(), result, "A teleport's own bookkeeping threw");
        call(plan.then(), result, "A teleport callback threw");
    }

    private void call(@Nullable Consumer<TeleportResult> listener, TeleportResult result,
                      String complaint) {
        if (listener == null) {
            return;
        }
        try {
            listener.accept(result);
        } catch (RuntimeException failed) {
            // Reported against whoever wrote it rather than swallowed, and it
            // must not stop this teleport from being considered finished.
            plan.debug().error(complaint, failed);
        }
    }

    private void refundCooldown() {
        String key = plan.cooldownKey();
        if (key != null) {
            Cooldowns.clear(plan.player(), key);
        }
    }

    private void report() {
        if (plan.onTick() == null) {
            return;
        }
        try {
            plan.onTick().accept(remainingWarmupSeconds());
        } catch (RuntimeException failed) {
            plan.debug().error("A teleport countdown callback threw", failed);
        }
    }

    private void play(@Nullable EffectConfig effect) {
        play(effect, 0);
    }

    private void play(@Nullable EffectConfig effect, double seconds) {
        if (effect == null) {
            return;
        }
        try {
            // Owner-scoped rather than static: the static form works the owner
            // out from the calling class, and the caller here is the library
            // itself, which under a shading or loader classloader resolves to
            // nothing at all.
            Effects.of(plan.plugin()).play(effect, plan.player(), seconds);
        } catch (RuntimeException failed) {
            // A misconfigured sound name should not stop a teleport: the point
            // of the module is the move, and the effect is decoration on it.
            plan.debug().error("Could not play a teleport effect", failed);
        }
    }
}
