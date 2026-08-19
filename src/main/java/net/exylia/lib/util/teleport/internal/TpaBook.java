package net.exylia.lib.util.teleport.internal;

import net.exylia.lib.util.teleport.TeleportRequestTicket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every request waiting to be answered, on the whole server.
 *
 * <h2>No timer, ever</h2>
 * Nothing here watches a clock. A ticket carries the moment it stops counting
 * and every read drops the ones that are past it, which is exactly how
 * {@link net.exylia.lib.util.Cooldowns} works and for exactly the same reason:
 * <em>no hay tarea que descuente, se compara al leer</em>. A hundred requests
 * nobody answered cost nothing at all until somebody looks at them, while a
 * hundred scheduled expiries are a hundred tasks the server runs to discover
 * that a player who already logged off is still not answering.
 *
 * <p>The one thing that costs is a request nobody ever reads again, from a
 * player who left. That is what {@link #forget} is for, and it is wired to
 * quitting on both sides.
 *
 * <h2>Both directions are O(1)</h2>
 * A target listing their requests and a sender withdrawing one are the two
 * things that happen, and both are a map lookup: the tickets are indexed by the
 * target and every one of them names its sender. A single flat list would make
 * the first of those a scan of every request on the server.
 *
 * <h2>Threads</h2>
 * Safe from any thread. Each target's own map is a {@link ConcurrentHashMap},
 * so a listing and an accept in the same tick cannot see a half-written one.
 */
@ApiStatus.Internal
public final class TpaBook {

    /** Requests waiting for each target, by who sent them. */
    private static final Map<UUID, Map<UUID, TeleportRequestTicket>> BY_TARGET =
            new ConcurrentHashMap<>();

    private TpaBook() {
        throw new AssertionError("No instances.");
    }

    /**
     * Files a request, if there is room and there is not one already.
     *
     * <p>How many a target may hold is checked by the caller through
     * {@link #countFor}, because a full target and a duplicate request are two
     * different answers to whoever sent it.
     *
     * @param ticket the request
     * @return whether it was filed; {@code false} means a live one already
     *         exists for that pair
     */
    public static boolean put(@NotNull TeleportRequestTicket ticket) {
        Map<UUID, TeleportRequestTicket> waiting =
                BY_TARGET.computeIfAbsent(ticket.to(), ignored -> new ConcurrentHashMap<>());
        // The expired one is removed rather than treated as a duplicate: a
        // sender whose first request ran out is asking again, not spamming.
        TeleportRequestTicket existing = waiting.get(ticket.from());
        if (existing != null && !existing.isExpired()) {
            return false;
        }
        waiting.put(ticket.from(), ticket);
        return true;
    }

    /**
     * How many live requests a target is sitting on.
     *
     * <p>Counted after dropping the expired ones, or a player buried under
     * eight requests that all ran out could never be asked again.
     *
     * @param target the target
     * @return how many are live
     */
    public static int countFor(@NotNull UUID target) {
        return liveFor(target).size();
    }

    /**
     * One live request, by the pair it belongs to.
     *
     * @param target who was asked
     * @param from   who asked
     * @return the request, or {@code null} when there is none or it ran out
     */
    public static @Nullable TeleportRequestTicket get(@NotNull UUID target, @NotNull UUID from) {
        Map<UUID, TeleportRequestTicket> waiting = BY_TARGET.get(target);
        if (waiting == null) {
            return null;
        }
        TeleportRequestTicket ticket = waiting.get(from);
        if (ticket == null) {
            return null;
        }
        if (ticket.isExpired()) {
            waiting.remove(from, ticket);
            return null;
        }
        return ticket;
    }

    /**
     * Whether a request exists at all, live or expired.
     *
     * <p>The difference matters to whoever is answering: "there is no request
     * from that person" and "there was, and it ran out" send a player to check
     * two different things.
     *
     * @param target who was asked
     * @param from   who asked
     * @return whether a ticket is on file, however old
     */
    public static boolean hasAny(@NotNull UUID target, @NotNull UUID from) {
        Map<UUID, TeleportRequestTicket> waiting = BY_TARGET.get(target);
        return waiting != null && waiting.containsKey(from);
    }

    /**
     * Every live request waiting for a target, newest expiry last.
     *
     * @param target who was asked
     * @return the live requests
     */
    public static @NotNull List<TeleportRequestTicket> liveFor(@NotNull UUID target) {
        Map<UUID, TeleportRequestTicket> waiting = BY_TARGET.get(target);
        if (waiting == null || waiting.isEmpty()) {
            return List.of();
        }
        List<TeleportRequestTicket> live = new ArrayList<>(waiting.size());
        for (TeleportRequestTicket ticket : waiting.values()) {
            if (ticket.isExpired()) {
                waiting.remove(ticket.from(), ticket);
                continue;
            }
            live.add(ticket);
        }
        live.sort(java.util.Comparator.comparing(TeleportRequestTicket::expiresAt));
        return List.copyOf(live);
    }

    /**
     * Removes a request, however old it was.
     *
     * @param target who was asked
     * @param from   who asked
     * @return the request that was removed, or {@code null} when there was none
     */
    public static @Nullable TeleportRequestTicket remove(@NotNull UUID target, @NotNull UUID from) {
        Map<UUID, TeleportRequestTicket> waiting = BY_TARGET.get(target);
        if (waiting == null) {
            return null;
        }
        TeleportRequestTicket removed = waiting.remove(from);
        if (waiting.isEmpty()) {
            BY_TARGET.remove(target, waiting);
        }
        return removed;
    }

    /**
     * Forgets every request a player is on either side of.
     *
     * <p>Both roles, because a player who left can neither accept one nor be
     * accepted: leaving only the ones they sent would let somebody accept a
     * visit from a person who is not on the server.
     *
     * @param player who left
     */
    public static void forget(@NotNull UUID player) {
        BY_TARGET.remove(player);
        for (Map.Entry<UUID, Map<UUID, TeleportRequestTicket>> entry : BY_TARGET.entrySet()) {
            Map<UUID, TeleportRequestTicket> waiting = entry.getValue();
            waiting.remove(player);
            if (waiting.isEmpty()) {
                BY_TARGET.remove(entry.getKey(), waiting);
            }
        }
    }

    /**
     * Drops every request that has run out.
     *
     * <p>Not needed for correctness — every read already drops what it finds —
     * but a server with a command that reports its own state wants a number,
     * and a plugin that never lists requests for a player who logged off would
     * otherwise keep them until that player came back.
     *
     * @return how many were dropped
     */
    public static int expireStale() {
        int dropped = 0;
        for (Map.Entry<UUID, Map<UUID, TeleportRequestTicket>> entry : BY_TARGET.entrySet()) {
            Map<UUID, TeleportRequestTicket> waiting = entry.getValue();
            for (TeleportRequestTicket ticket : List.copyOf(waiting.values())) {
                if (ticket.isExpired() && waiting.remove(ticket.from(), ticket)) {
                    dropped++;
                }
            }
            if (waiting.isEmpty()) {
                BY_TARGET.remove(entry.getKey(), waiting);
            }
        }
        return dropped;
    }

    /** Forgets everything, on shutdown and between tests. */
    public static void forgetEverything() {
        BY_TARGET.clear();
    }
}
