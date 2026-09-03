package net.exylia.lib.util.sequence;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Where a sequence happens, who caused it, and who gets to see it.
 *
 * <pre>{@code
 * SequenceTarget target = SequenceTarget.at(victim.getLocation())
 *         .by(killer)
 *         .on(victim)
 *         .visibleTo((observer, source) -> settings.showsEffects(observer));
 * }</pre>
 *
 * <h2>Location, not player</h2>
 * A sequence is anchored to a place. A kill effect plays at the victim's feet
 * even though the killer is the one who owns it, and a projectile trail has no
 * player at its position at all. Anchoring to a player instead would make the
 * common case wrong in a way nobody notices until the effect is visibly in the
 * wrong spot.
 *
 * <h2>Who sees it</h2>
 * Observers are the players within {@link #radius()} blocks, narrowed by an
 * optional predicate. The predicate is what a "show effects: off" toggle plugs
 * into &mdash; every consumer of this module implements one, so the seam is
 * here rather than copied three times.
 *
 * <p>The radius is a value rather than a constant: ExyliaCommons hardcoded 32
 * blocks, which is right for a kill effect and wasteful for a footstep.
 *
 * @since 1.30.0
 */
public final class SequenceTarget {

    /** What ExyliaCommons hardcoded, kept as the default so effects look the same. */
    public static final double DEFAULT_RADIUS = 32.0;

    private final Location location;
    private final Player source;
    private final Entity target;
    private final BiPredicate<Player, Player> visibility;
    private final double radius;

    /**
     * The last answer {@link #observers()} gave, and when.
     *
     * <p>One target is asked once per frame of every line it plays, and a kill
     * effect is twenty lines of several frames each: without this, one death
     * walks the world's player list a hundred times inside the same tick and
     * gets the same answer every time. Held for one tick, because that is how
     * long "who is nearby" can be true for.
     *
     * <p>Every other field is final and every builder method returns a new
     * target, so this is a memo of a pure function rather than state: two
     * targets never share it, and a moved target ({@link #movedTo}) starts
     * without one.
     */
    private volatile List<Player> memo;
    private volatile long memoAt;

    /** One tick, in milliseconds. */
    private static final long TICK_MS = 50L;

    private SequenceTarget(Location location, Player source, Entity target,
                           BiPredicate<Player, Player> visibility, double radius) {
        this.location = location;
        this.source = source;
        this.target = target;
        this.visibility = visibility;
        this.radius = radius;
    }

    /**
     * A sequence at a place, seen by everyone within the default radius.
     *
     * @param location where it happens
     * @return the target
     */
    public static @NotNull SequenceTarget at(@NotNull Location location) {
        return new SequenceTarget(location.clone(), null, null, null, DEFAULT_RADIUS);
    }

    /**
     * A sequence at a player's feet, caused by that same player.
     *
     * <p>The shorthand for a self effect &mdash; a rank cosmetic, a join
     * flourish.
     *
     * @param player whose feet, and whose effect
     * @return the target
     */
    public static @NotNull SequenceTarget of(@NotNull Player player) {
        return new SequenceTarget(player.getLocation(), player, player, null, DEFAULT_RADIUS);
    }

    /**
     * Who caused this.
     *
     * <p>Used by {@code [TITLE]} and {@code [ACTION_BAR]}, which have nobody to
     * show to without one, and by {@code {player}} in {@code [COMMAND]}.
     *
     * @param player the player, or {@code null} for none
     * @return a new target
     */
    public @NotNull SequenceTarget by(@Nullable Player player) {
        return new SequenceTarget(location, player, target, visibility, radius);
    }

    /**
     * What this happened to.
     *
     * <p>Used by {@code [POTION]}, which needs something living to apply to.
     *
     * @param entity the entity, or {@code null} for none
     * @return a new target
     */
    public @NotNull SequenceTarget on(@Nullable Entity entity) {
        return new SequenceTarget(location, source, entity, visibility, radius);
    }

    /**
     * Narrows who perceives this, on top of the radius.
     *
     * <p>The predicate receives the candidate observer and the source player,
     * and answers whether that observer should see it. It is asked once per
     * observer per step, so it must be cheap &mdash; a map lookup, not a
     * database call.
     *
     * @param predicate the test, or {@code null} to let the radius decide alone
     * @return a new target
     */
    public @NotNull SequenceTarget visibleTo(@Nullable BiPredicate<Player, Player> predicate) {
        return new SequenceTarget(location, source, target, predicate, radius);
    }

    /**
     * Shows this to one player and nobody else.
     *
     * <p>What a menu preview wants. Commons achieved it by passing a predicate
     * matching a single UUID, which every consumer wrote out by hand.
     *
     * @param viewer the only player who sees it
     * @return a new target
     */
    public @NotNull SequenceTarget onlyTo(@NotNull Player viewer) {
        return visibleTo((observer, ignored) -> observer.equals(viewer));
    }

    /**
     * How far away this can be seen, in blocks.
     *
     * @param blocks the radius; values below zero are treated as zero
     * @return a new target
     */
    public @NotNull SequenceTarget within(double blocks) {
        return new SequenceTarget(location, source, target, visibility, Math.max(0.0, blocks));
    }

    /**
     * The same target moved to another place.
     *
     * <p>What a projectile trail re-uses on every tick: the source, the
     * visibility rule and the radius are unchanged, only the position moves.
     *
     * @param destination the new position
     * @return a new target
     */
    public @NotNull SequenceTarget movedTo(@NotNull Location destination) {
        return new SequenceTarget(destination.clone(), source, target, visibility, radius);
    }

    /** Where this happens. */
    public @NotNull Location location() {
        return location;
    }

    /** Who caused it, or {@code null}. */
    public @Nullable Player source() {
        return source;
    }

    /** What it happened to, or {@code null}. */
    public @Nullable Entity target() {
        return target;
    }

    /** How far away it can be seen, in blocks. */
    public double radius() {
        return radius;
    }

    /**
     * The players who should perceive this, right now.
     *
     * <p>Squared distance, so no square root per player per step. A world that
     * has gone away answers with nobody rather than throwing, because a
     * sequence outliving its world is a normal shutdown, not a bug.
     *
     * @return the observers, possibly empty
     */
    public @NotNull List<Player> observers() {
        World world = location.getWorld();
        if (world == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Player> remembered = memo;
        if (remembered != null && now - memoAt < TICK_MS) {
            return remembered;
        }
        double limit = radius * radius;
        List<Player> observers = new ArrayList<>();
        for (Player online : world.getPlayers()) {
            // Comparing worlds first: distanceSquared throws when they differ,
            // and getPlayers() can return a player who changed world in between.
            if (!world.equals(online.getWorld())) {
                continue;
            }
            if (online.getLocation().distanceSquared(location) > limit) {
                continue;
            }
            if (visibility != null && !visibility.test(online, source)) {
                continue;
            }
            observers.add(online);
        }
        memo = observers;
        memoAt = now;
        return observers;
    }

    @Override
    public String toString() {
        return "SequenceTarget[" + location.getWorld() + " "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                + ", r=" + radius + ']';
    }
}
