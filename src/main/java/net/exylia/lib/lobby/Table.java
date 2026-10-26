package net.exylia.lib.lobby;

import net.exylia.lib.session.Claim;
import net.exylia.lib.task.TaskHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One table of a {@link Lobby}: what is played at it, its seats, and where it is in its life.
 *
 * <p>Every change goes through a {@code synchronized} method, which is what makes "two players took
 * the last seat at the same time" impossible: the second one finds it taken. A table is reachable
 * from commands, menu clicks and asynchronous admissions, on any thread. Only the lobby changes it;
 * a plugin reads it.
 *
 * <p>Seats keep their numbers for the whole life of the table: a player who leaves once it is
 * {@link TableState#PLAYING} keeps their seat in {@link #seats()} and is answered by {@link #hasLeft}.
 *
 * @param <T> what the plugin plays at it: a game, a mode, a stake, anything it hands to {@link Lobby#create}
 * @since 1.356.0
 */
public final class Table<T> {

    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final String id;
    private final T subject;
    private final TableRules rules;
    /** The players an invitation was sent to, in the order invited; empty for a table anybody may take. */
    private final List<UUID> invitees;
    /** Invitees who have not declined. */
    private final Set<UUID> awaiting;
    private final long createdAt;
    private final List<Seat> seats = new ArrayList<>();
    /** Seats taken whose admission is still running; the table starts only once none are. */
    private final Set<UUID> pending = new HashSet<>();
    /** Seats whose player left after the table started. */
    private final Set<UUID> gone = new HashSet<>();
    private final Map<UUID, Claim> claims = new HashMap<>();

    private TableState state = TableState.CREATING;
    private boolean started;
    private long startedAt;
    private long expiresAt;
    private TaskHandle timer;

    Table(@NotNull T subject, @NotNull TableRules rules, @NotNull Seat host, @Nullable UUID invitee, long now) {
        this(subject, rules, host, invitee == null ? List.of() : List.of(invitee), now);
    }

    Table(@NotNull T subject, @NotNull TableRules rules, @NotNull Seat host, @NotNull List<UUID> invitees, long now) {
        this.id = newId();
        this.subject = Objects.requireNonNull(subject, "subject");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.invitees = List.copyOf(invitees);
        this.awaiting = new HashSet<>(invitees);
        this.createdAt = now;
        seats.add(host);
        pending.add(host.player());
    }

    private static String newId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder id = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    /** A short id, unique among the lobby's live tables: what a command or a menu names it by. */
    public @NotNull String id() {
        return id;
    }

    public @NotNull T subject() {
        return subject;
    }

    public @NotNull TableRules rules() {
        return rules;
    }

    /** The first player an invitation was sent to; {@code null} for an open table. */
    public @Nullable UUID invitee() {
        return invitees.isEmpty() ? null : invitees.getFirst();
    }

    /**
     * Every player an invitation was sent to, in the order invited; empty for an open table.
     *
     * @since 1.359.0
     */
    public @NotNull List<UUID> invitees() {
        return invitees;
    }

    public boolean isInvitation() {
        return !invitees.isEmpty();
    }

    /**
     * Whether an invitation sent to this player still waits for their answer: they were invited,
     * have not declined, and hold no seat.
     *
     * @since 1.359.0
     */
    public synchronized boolean awaits(@NotNull UUID player) {
        return awaiting.contains(player) && seatOf(player) < 0;
    }

    /**
     * Whether anybody may take a free seat: an open table, or an invitation to one player once they sat down.
     *
     * <p>An invitation to one player at a table of more than two would otherwise never fill: once the
     * invitee has accepted, the remaining seats are open to everyone. An invitation to several keeps
     * its seats for them.
     */
    public synchronized boolean isPublic() {
        return invitees.isEmpty() || (invitees.size() == 1 && seatOf(invitees.getFirst()) >= 0);
    }

    public long createdAt() {
        return createdAt;
    }

    /** When it started being played, in epoch milliseconds; 0 before. */
    public synchronized long startedAt() {
        return startedAt;
    }

    /** When it stops waiting for players, in epoch milliseconds; 0 when it is not waiting. */
    public synchronized long expiresAt() {
        return expiresAt;
    }

    public @NotNull Seat host() {
        return seats.getFirst();
    }

    /** Every seat taken, in seat order, leavers included once it started. */
    public synchronized @NotNull List<Seat> seats() {
        return List.copyOf(seats);
    }

    /** The seats whose player is still at the table. */
    public synchronized @NotNull List<Seat> present() {
        return seats.stream().filter(seat -> !gone.contains(seat.player())).toList();
    }

    /** A player's seat number, or {@code -1}. */
    public synchronized int seatOf(@NotNull UUID player) {
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).player().equals(player)) return i;
        }
        return -1;
    }

    /** Whether the player of a seat left after the table started. */
    public synchronized boolean hasLeft(int seat) {
        return seat >= 0 && seat < seats.size() && gone.contains(seats.get(seat).player());
    }

    public synchronized @NotNull TableState state() {
        return state;
    }

    public synchronized boolean isFull() {
        return seats.size() >= rules.maxSeats();
    }

    // ── What the lobby changes ────────────────────────────────────────────

    /**
     * Moves to the next state, only from the one expected.
     *
     * @return whether this call made the move; {@code false} means somebody else already did
     */
    synchronized boolean advance(TableState from, TableState to) {
        if (state != from) return false;
        state = to;
        if (to == TableState.PLAYING) startedAt = System.currentTimeMillis();
        if (to != TableState.OPEN) expiresAt = 0;
        return true;
    }

    /**
     * Ends the table from whatever state it is in.
     *
     * @return the state it was in, or {@code null} when it had already ended
     */
    synchronized TableState end() {
        if (state == TableState.ENDED) return null;
        TableState was = state;
        state = TableState.ENDED;
        expiresAt = 0;
        return was;
    }

    /**
     * Takes a seat for a player, before they are admitted. The seat is held while the admission runs,
     * so nobody else can take it; {@link #release} gives it back when the admission fails.
     *
     * @return whether the seat is theirs; the table moves to {@link TableState#STARTING} when it was
     *         the last one
     */
    synchronized boolean reserve(Seat seat) {
        if (state != TableState.OPEN || seats.size() >= rules.maxSeats() || seatOf(seat.player()) >= 0) return false;
        if (!isPublic() && !awaiting.contains(seat.player())) return false;
        seats.add(seat);
        pending.add(seat.player());
        if (seats.size() >= rules.maxSeats()) state = TableState.STARTING;
        return true;
    }

    /**
     * Gives back a seat before the table starts: its admission failed, or its player walked away. The
     * host's seat is never given back; a table without its host is cancelled instead.
     *
     * @return the seat given back, or {@code null} when the player holds none that can be
     */
    synchronized Released release(UUID player) {
        int index = seatOf(player);
        if (index <= 0 || started || state == TableState.PLAYING || state == TableState.ENDED) return null;
        Seat seat = seats.remove(index);
        boolean admitted = !pending.remove(player);
        if (state == TableState.STARTING) state = TableState.OPEN;
        return new Released(seat, admitted);
    }

    /**
     * A seat given back before the start.
     *
     * @param admitted whether its admission had gone through, so what it took is to be given back now;
     *                 otherwise the admission still running finds the seat gone and gives it back then
     */
    record Released(Seat seat, boolean admitted) {
    }

    /**
     * A reserved seat's admission went through.
     *
     * @return whether the player still holds that seat at a live table; {@code false} means the seat
     *         went (the table ended, or they left) while the admission ran, and what it took goes back
     */
    synchronized boolean admitted(UUID player) {
        if (!pending.remove(player)) return false;
        return state != TableState.ENDED;
    }

    /**
     * Starts a table before it is full, on its host's word: only with at least {@code minSeats}
     * seated and every one of them admitted, so an admission still running can never fail after the
     * table has left the lobby.
     *
     * @return whether this call moved the table to {@link TableState#STARTING}
     */
    synchronized boolean startEarly() {
        if (state != TableState.OPEN || !pending.isEmpty() || seats.size() < rules.minSeats()) return false;
        state = TableState.STARTING;
        return true;
    }

    /**
     * Whether the table is to be started now: {@link TableState#STARTING} with every seat admitted.
     * Answers {@code true} once, to whichever caller sees it first.
     */
    synchronized boolean beginStart() {
        if (started || state != TableState.STARTING || !pending.isEmpty()) return false;
        started = true;
        return true;
    }

    /**
     * A player leaves a table being played; their seat stays, marked as left.
     *
     * @return their seat number, or {@code -1} when they hold none or already left
     */
    synchronized int drop(UUID player) {
        int index = seatOf(player);
        if (index < 0 || state != TableState.PLAYING || !gone.add(player)) return -1;
        return index;
    }

    /**
     * An invitee declines.
     *
     * @return whether they were still awaited
     */
    synchronized boolean decline(UUID player) {
        return seatOf(player) < 0 && awaiting.remove(player);
    }

    /** Whether no invitee is left to answer: every one sat down or declined. */
    synchronized boolean answered() {
        for (UUID player : awaiting) if (seatOf(player) < 0) return false;
        return true;
    }

    synchronized void expireAt(long at) {
        expiresAt = at;
    }

    synchronized void claim(UUID player, Claim claim) {
        claims.put(player, claim);
    }

    /** Takes the claim held for a player off the table. */
    synchronized Claim unclaim(UUID player) {
        return claims.remove(player);
    }

    synchronized List<Claim> unclaimAll() {
        List<Claim> all = List.copyOf(claims.values());
        claims.clear();
        return all;
    }

    /** The task that ends the table when it runs out; replacing it cancels the one before. */
    synchronized void timer(TaskHandle timer) {
        if (this.timer != null) this.timer.cancel();
        this.timer = timer;
    }

    @Override
    public String toString() {
        return "Table{" + id + " " + rules.kind() + " " + state + " seats=" + seats.size() + "/" + rules.maxSeats() + "}";
    }
}
