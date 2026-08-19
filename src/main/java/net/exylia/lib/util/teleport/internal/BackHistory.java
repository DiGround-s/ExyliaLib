package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.util.teleport.ExyliaLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The last few places each player was, so they can walk back out of one.
 *
 * <h2>Why nothing is written to disk</h2>
 * A place a player stood is not worth a write. It is worth exactly as much as
 * the waypoints the client module sends: real while they are here, pointless
 * once they leave, and cheap enough to rebuild by walking somewhere. Persisting
 * it would buy a player the ability to undo a teleport they made last Tuesday,
 * at the cost of a file that grows with everybody who has ever joined.
 *
 * <h2>Bounded twice, and both bounds matter</h2>
 * By count, because an unbounded deque per player is a leak with a nicer name.
 * And by age, because a place somebody left an hour ago is not somewhere they
 * meant to come back to — offering it turns an undo into a surprise. The age
 * check happens on the way out rather than on a timer: a stale entry costs
 * nothing until somebody asks for it, and the reading that notices it is the
 * one that drops it. Same reasoning as {@link net.exylia.lib.util.Cooldowns},
 * where a thousand idle cooldowns cost nothing because they are compared on
 * read rather than counted down by a task.
 *
 * <h2>Threads</h2>
 * Safe from any thread. Each player's deque is only ever touched while holding
 * that deque's own monitor, which is enough: a deque belongs to one player, and
 * the two things that write to one — a teleport finishing and a {@code /back}
 * being built — are already rare.
 */
@ApiStatus.Internal
public final class BackHistory {

    /** Newest first: the front of the deque is where {@code /back} goes. */
    private static final Map<UUID, Deque<Recorded>> BY_PLAYER = new ConcurrentHashMap<>();

    /**
     * Where "now" comes from.
     *
     * <p>A seam rather than a direct call so a test can age an entry past the
     * limit without sleeping for it, exactly as {@code Cooldowns} does.
     */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /**
     * One place, and when it was recorded.
     *
     * <p>The time travels with the place rather than being re-stamped, so a
     * place handed back after a teleport that did not happen ages out on the
     * schedule it was already on.
     *
     * @param where      the place
     * @param recordedAt when it was recorded, in milliseconds
     */
    public record Recorded(@NotNull ExyliaLocation where, long recordedAt) {
    }

    private BackHistory() {
        throw new AssertionError("No instances.");
    }

    /**
     * Records where a player was, dropping the oldest place when full.
     *
     * <p>Called for every successful teleport this module performs, the ones
     * caused by {@code /back} included: a player who undoes an undo is asking
     * for something perfectly sensible, and refusing to record it would make
     * the second {@code /back} say there is nowhere to go.
     *
     * @param player  who moved
     * @param where   where they were before they moved
     * @param maxSize how many places to keep at most
     */
    public static void push(@NotNull UUID player, @NotNull ExyliaLocation where, int maxSize) {
        record(player, new Recorded(where, clock.getAsLong()), maxSize);
    }

    /**
     * Puts a place back at the front, for a {@code /back} that never happened.
     *
     * <p>The rule the cooldown already follows: a teleport that was called off
     * is free, and an undo the player never received is not one they should
     * have spent.
     *
     * @param player  who was going back
     * @param taken   the place that was taken, with the time it was recorded
     * @param maxSize how many places to keep at most
     */
    public static void restore(@NotNull UUID player, @NotNull Recorded taken, int maxSize) {
        record(player, taken, maxSize);
    }

    /**
     * The most recent place, without taking it.
     *
     * @param player     who is asking
     * @param maxMinutes how old a place may be and still be offered
     * @return where they were, or {@code null} when there is nothing to offer
     */
    public static @Nullable ExyliaLocation peek(@NotNull UUID player, int maxMinutes) {
        Recorded newest = take(player, maxMinutes, false);
        return newest == null ? null : newest.where();
    }

    /**
     * The most recent place, taking it.
     *
     * <p>Taking rather than reading is what keeps two points from growing the
     * stack: going back pops one entry and arriving pushes one, so a player
     * bouncing between two places forever holds exactly one.
     *
     * @param player     who is asking
     * @param maxMinutes how old a place may be and still be offered
     * @return where they were and when it was recorded, or {@code null} when
     *         there is nothing to offer
     */
    public static @Nullable Recorded pop(@NotNull UUID player, int maxMinutes) {
        return take(player, maxMinutes, true);
    }

    /**
     * Forgets everything recorded for one player.
     *
     * <p>Wired to quitting. A player who left must not keep memory, and the
     * places they walked through are the least useful thing to keep of them.
     *
     * @param player who left
     */
    public static void forget(@NotNull UUID player) {
        BY_PLAYER.remove(player);
    }

    /** Forgets everybody, on shutdown and between tests. */
    public static void forgetEverything() {
        BY_PLAYER.clear();
    }

    /**
     * How many places are recorded for a player.
     *
     * <p>Counted without regard to age, because this exists to prove the count
     * bound holds; the age bound is proved by what {@link #peek} offers.
     *
     * @param player the player
     * @return how many places are held
     */
    public static int sizeOf(@NotNull UUID player) {
        Deque<Recorded> places = BY_PLAYER.get(player);
        if (places == null) {
            return 0;
        }
        synchronized (places) {
            return places.size();
        }
    }

    /**
     * Test seam: where "now" comes from, so age is testable without waiting.
     *
     * @param millis the clock
     */
    public static void setClock(@NotNull LongSupplier millis) {
        clock = millis;
    }

    /** Test seam: back to the real clock. */
    public static void resetClock() {
        clock = System::currentTimeMillis;
    }

    private static void record(UUID player, Recorded entry, int maxSize) {
        int bound = Math.max(1, maxSize);
        Deque<Recorded> places = BY_PLAYER.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        synchronized (places) {
            places.addFirst(entry);
            while (places.size() > bound) {
                places.removeLast();
            }
        }
    }

    /**
     * The newest place that is still young enough, dropping the ones that are
     * not.
     *
     * <p>Everything behind a stale entry is staler still, since the deque is in
     * recorded order, so the whole thing goes rather than just the head.
     */
    private static @Nullable Recorded take(UUID player, int maxMinutes, boolean remove) {
        Deque<Recorded> places = BY_PLAYER.get(player);
        if (places == null) {
            return null;
        }
        long oldest = clock.getAsLong() - Math.max(1, maxMinutes) * 60_000L;
        synchronized (places) {
            Recorded newest = places.peekFirst();
            if (newest == null) {
                return null;
            }
            if (newest.recordedAt() < oldest) {
                // Dropped where it was noticed rather than by a task: the
                // reading that finds a stale entry is the only thing that ever
                // needed it gone.
                places.clear();
                return null;
            }
            if (remove) {
                places.removeFirst();
            }
            return newest;
        }
    }
}
