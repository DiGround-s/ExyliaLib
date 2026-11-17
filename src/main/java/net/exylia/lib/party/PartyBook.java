package net.exylia.lib.party;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Every party on the server, as plain ids: no players, no messages, no threads.
 *
 * <p>{@link Parties} wraps it with the commands, the lines and the watchers. Kept apart so the rules
 * (who may invite, who leads after a quit, when a party ends) are tested without a server.
 *
 * <p>ponytail: one lock for the whole book. Party changes are a few per second at most; split it
 * per party if that ever shows up in a profile.
 */
final class PartyBook {

    /** One party's mutable state. Only touched under the book's lock. */
    static final class Entry {
        final UUID id;
        UUID leader;
        final Set<UUID> members = new LinkedHashSet<>();
        /** Invitee → when the invitation expires (epoch millis). */
        final Map<UUID, Long> invites = new LinkedHashMap<>();
        /** Member → when they quit (epoch millis). */
        final Map<UUID, Long> offline = new LinkedHashMap<>();
        Ready ready;

        Entry(UUID id, UUID leader) {
            this.id = id;
            this.leader = leader;
            members.add(leader);
        }

        Party snapshot(int maxSize) {
            List<UUID> list = new ArrayList<>(members.size());
            list.add(leader);
            for (UUID member : members) if (!member.equals(leader)) list.add(member);
            return new Party(id, leader, List.copyOf(list), List.copyOf(offline.keySet()), maxSize);
        }
    }

    /** A ready check in progress. */
    static final class Ready {
        final String what;
        final long deadline;
        final Set<UUID> ready = new LinkedHashSet<>();
        final CompletableFuture<ReadyResult> future = new CompletableFuture<>();

        Ready(String what, long deadline) {
            this.what = what;
            this.deadline = deadline;
        }
    }

    enum Invite { SENT, SELF, NOT_LEADER, ALREADY_MEMBER, ALREADY_INVITED, FULL }

    enum Answer { JOINED, NO_INVITE, FULL }

    /** What {@link #invite} did; {@code created} when the inviter had no party before. */
    record InviteOutcome(@NotNull Invite result, @Nullable Party party, boolean created) {
    }

    /** What {@link #accept} did; {@code left} is the party the player had to leave first, if any. */
    record AcceptOutcome(@NotNull Answer result, @Nullable Party party, @Nullable Removal left) {
    }

    /**
     * A member gone from a party.
     *
     * @param party     the party after they went (as it was last, when it ended)
     * @param player    who went
     * @param reason    why
     * @param newLeader who leads now, when the leader was the one who went
     * @param ended     whether the party ended with it
     * @param ready     a ready check this broke, to complete with {@link ReadyResult#CHANGED}
     */
    record Removal(@NotNull Party party, @NotNull UUID player, @NotNull PartyLeave reason,
                   @Nullable UUID newLeader, boolean ended, @Nullable Ready ready) {
    }

    private final Map<UUID, Entry> byId = new LinkedHashMap<>();
    private final Map<UUID, Entry> byMember = new LinkedHashMap<>();
    private int maxSize;

    PartyBook(int maxSize) {
        this.maxSize = Math.max(2, maxSize);
    }

    synchronized void maxSize(int maxSize) {
        this.maxSize = Math.max(2, maxSize);
    }

    synchronized @Nullable Party of(@NotNull UUID member) {
        Entry entry = byMember.get(member);
        return entry == null ? null : entry.snapshot(maxSize);
    }

    synchronized @Nullable Party get(@NotNull UUID id) {
        Entry entry = byId.get(id);
        return entry == null ? null : entry.snapshot(maxSize);
    }

    synchronized @NotNull List<Party> all() {
        return byId.values().stream().map(e -> e.snapshot(maxSize)).toList();
    }

    /** The parties that invited this player and still wait, newest first. */
    synchronized @NotNull List<Party> invitesTo(@NotNull UUID player, long now) {
        List<Party> out = new ArrayList<>();
        for (Entry entry : byId.values()) {
            Long until = entry.invites.get(player);
            if (until != null && until > now) out.addFirst(entry.snapshot(maxSize));
        }
        return out;
    }

    synchronized @NotNull InviteOutcome invite(@NotNull UUID inviter, @NotNull UUID target, long now, long expiresAt) {
        if (inviter.equals(target)) return new InviteOutcome(Invite.SELF, null, false);
        Entry entry = byMember.get(inviter);
        boolean created = entry == null;
        if (entry != null && !entry.leader.equals(inviter)) return new InviteOutcome(Invite.NOT_LEADER, entry.snapshot(maxSize), false);
        if (entry != null && entry.members.contains(target)) return new InviteOutcome(Invite.ALREADY_MEMBER, entry.snapshot(maxSize), false);
        if (entry != null) {
            Long until = entry.invites.get(target);
            if (until != null && until > now) return new InviteOutcome(Invite.ALREADY_INVITED, entry.snapshot(maxSize), false);
            if (entry.members.size() >= maxSize) return new InviteOutcome(Invite.FULL, entry.snapshot(maxSize), false);
        }
        if (created) {
            entry = new Entry(UUID.randomUUID(), inviter);
            byId.put(entry.id, entry);
            byMember.put(inviter, entry);
        }
        entry.invites.put(target, expiresAt);
        return new InviteOutcome(Invite.SENT, entry.snapshot(maxSize), created);
    }

    /**
     * Answers yes to an invitation.
     *
     * @param leader whose party; {@code null} for the newest invitation
     */
    synchronized @NotNull AcceptOutcome accept(@NotNull UUID player, @Nullable UUID leader, long now) {
        Entry entry = invitation(player, leader, now);
        if (entry == null) return new AcceptOutcome(Answer.NO_INVITE, null, null);
        if (entry.members.size() >= maxSize) {
            entry.invites.remove(player);
            return new AcceptOutcome(Answer.FULL, entry.snapshot(maxSize), null);
        }
        entry.invites.remove(player);
        Removal left = null;
        Entry old = byMember.get(player);
        if (old != null && old != entry) left = remove(old, player, PartyLeave.SWITCHED);
        entry.members.add(player);
        entry.offline.remove(player);
        byMember.put(player, entry);
        Ready broken = entry.ready;
        entry.ready = null;
        if (broken != null) broken.future.complete(ReadyResult.CHANGED);
        return new AcceptOutcome(Answer.JOINED, entry.snapshot(maxSize), left);
    }

    /**
     * Answers no to an invitation.
     *
     * @return the party that invited them, with the invitation gone; {@code null} if none; the
     * party ends when it was only waiting for this answer
     */
    synchronized @Nullable Declined decline(@NotNull UUID player, @Nullable UUID leader, long now) {
        Entry entry = invitation(player, leader, now);
        if (entry == null) return null;
        entry.invites.remove(player);
        Party party = entry.snapshot(maxSize);
        return new Declined(party, endIfAlone(entry));
    }

    record Declined(@NotNull Party party, boolean ended) {
    }

    private Entry invitation(UUID player, @Nullable UUID leader, long now) {
        Entry found = null;
        for (Entry entry : byId.values()) {
            Long until = entry.invites.get(player);
            if (until == null || until <= now) continue;
            if (leader == null || entry.leader.equals(leader)) found = entry;
        }
        return found;
    }

    synchronized @Nullable Removal leave(@NotNull UUID player) {
        Entry entry = byMember.get(player);
        return entry == null ? null : remove(entry, player, PartyLeave.LEFT);
    }

    enum Staff { DONE, NOT_LEADER, NOT_MEMBER, SELF, NO_PARTY }

    record StaffOutcome(@NotNull Staff result, @Nullable Removal removal, @Nullable Party party) {
    }

    synchronized @NotNull StaffOutcome kick(@NotNull UUID leader, @NotNull UUID target) {
        Entry entry = byMember.get(leader);
        if (entry == null) return new StaffOutcome(Staff.NO_PARTY, null, null);
        if (!entry.leader.equals(leader)) return new StaffOutcome(Staff.NOT_LEADER, null, entry.snapshot(maxSize));
        if (leader.equals(target)) return new StaffOutcome(Staff.SELF, null, entry.snapshot(maxSize));
        if (!entry.members.contains(target)) return new StaffOutcome(Staff.NOT_MEMBER, null, entry.snapshot(maxSize));
        return new StaffOutcome(Staff.DONE, remove(entry, target, PartyLeave.KICKED), null);
    }

    synchronized @NotNull StaffOutcome promote(@NotNull UUID leader, @NotNull UUID target) {
        Entry entry = byMember.get(leader);
        if (entry == null) return new StaffOutcome(Staff.NO_PARTY, null, null);
        if (!entry.leader.equals(leader)) return new StaffOutcome(Staff.NOT_LEADER, null, entry.snapshot(maxSize));
        if (leader.equals(target)) return new StaffOutcome(Staff.SELF, null, entry.snapshot(maxSize));
        if (!entry.members.contains(target)) return new StaffOutcome(Staff.NOT_MEMBER, null, entry.snapshot(maxSize));
        entry.leader = target;
        return new StaffOutcome(Staff.DONE, null, entry.snapshot(maxSize));
    }

    /** Ends the leader's party; the snapshot is its last state. */
    synchronized @NotNull StaffOutcome disband(@NotNull UUID leader) {
        Entry entry = byMember.get(leader);
        if (entry == null) return new StaffOutcome(Staff.NO_PARTY, null, null);
        if (!entry.leader.equals(leader)) return new StaffOutcome(Staff.NOT_LEADER, null, entry.snapshot(maxSize));
        Party last = entry.snapshot(maxSize);
        end(entry);
        return new StaffOutcome(Staff.DONE, null, last);
    }

    /**
     * A member left the server. They stay in the party for the grace period; a leader hands the lead
     * to the first member still online.
     *
     * @param online who is still on the server
     * @return the new leader, or {@code null} when nothing changed hands
     */
    synchronized @Nullable UUID quit(@NotNull UUID player, long now, @NotNull Set<UUID> online) {
        Entry entry = byMember.get(player);
        if (entry == null) return null;
        entry.offline.put(player, now);
        Ready broken = entry.ready;
        entry.ready = null;
        if (broken != null) broken.future.complete(ReadyResult.CHANGED);
        if (!entry.leader.equals(player)) return null;
        for (UUID member : entry.members) {
            if (!member.equals(player) && online.contains(member) && !entry.offline.containsKey(member)) {
                entry.leader = member;
                return member;
            }
        }
        return null;
    }

    /** A member came back within the grace period. */
    synchronized @Nullable Party rejoin(@NotNull UUID player) {
        Entry entry = byMember.get(player);
        if (entry == null || entry.offline.remove(player) == null) return null;
        return entry.snapshot(maxSize);
    }

    /** What one sweep of {@link #sweep} found. */
    record Sweep(@NotNull List<Removal> removed, @NotNull List<ExpiredInvite> invites,
                 @NotNull List<Ready> timedOut, @NotNull List<Party> ended) {
    }

    record ExpiredInvite(@NotNull Party party, @NotNull UUID target) {
    }

    /**
     * Drops members offline past the grace period, invitations past their time and ready checks past
     * their deadline. The ready futures are left for the caller to complete, after its lines.
     */
    synchronized @NotNull Sweep sweep(long now, long graceMillis) {
        List<Removal> removed = new ArrayList<>();
        List<ExpiredInvite> invites = new ArrayList<>();
        List<Ready> timedOut = new ArrayList<>();
        List<Party> ended = new ArrayList<>();
        for (Entry entry : List.copyOf(byId.values())) {
            for (Map.Entry<UUID, Long> gone : List.copyOf(entry.offline.entrySet())) {
                if (now - gone.getValue() >= graceMillis && byId.containsKey(entry.id)) {
                    removed.add(remove(entry, gone.getKey(), PartyLeave.TIMED_OUT));
                }
            }
            if (!byId.containsKey(entry.id)) continue;
            for (Map.Entry<UUID, Long> invite : List.copyOf(entry.invites.entrySet())) {
                if (invite.getValue() <= now) {
                    entry.invites.remove(invite.getKey());
                    invites.add(new ExpiredInvite(entry.snapshot(maxSize), invite.getKey()));
                }
            }
            if (entry.ready != null && entry.ready.deadline <= now) {
                timedOut.add(entry.ready);
                entry.ready = null;
            }
            Party before = entry.snapshot(maxSize);
            if (endIfAlone(entry)) ended.add(before);
        }
        return new Sweep(removed, invites, timedOut, ended);
    }

    // ── Ready checks ──────────────────────────────────────────────────────

    /**
     * Starts a ready check; the leader is ready already. A check still running is replaced and
     * completes with {@link ReadyResult#CHANGED}.
     *
     * @return the check, or {@code null} when the player leads no party
     */
    synchronized @Nullable Ready startReady(@NotNull UUID partyId, @NotNull String what, long deadline) {
        Entry entry = byId.get(partyId);
        if (entry == null) return null;
        Ready old = entry.ready;
        Ready check = new Ready(what, deadline);
        check.ready.add(entry.leader);
        entry.ready = check;
        if (old != null) old.future.complete(ReadyResult.CHANGED);
        if (!entry.offline.isEmpty()) {
            entry.ready = null;
            check.future.complete(ReadyResult.OFFLINE);
        } else if (check.ready.containsAll(entry.members)) {
            entry.ready = null;
            check.future.complete(ReadyResult.READY);
        }
        return check;
    }

    enum Vote { COUNTED, ALL_READY, DECLINED, NO_CHECK, ALREADY }

    record VoteOutcome(@NotNull Vote result, @Nullable Party party, @Nullable Ready check) {
    }

    /** A member's answer to the running ready check. Completing the future is the caller's. */
    synchronized @NotNull VoteOutcome vote(@NotNull UUID player, boolean ready) {
        Entry entry = byMember.get(player);
        if (entry == null || entry.ready == null) return new VoteOutcome(Vote.NO_CHECK, null, null);
        Ready check = entry.ready;
        if (!ready) {
            entry.ready = null;
            return new VoteOutcome(Vote.DECLINED, entry.snapshot(maxSize), check);
        }
        if (!check.ready.add(player)) return new VoteOutcome(Vote.ALREADY, entry.snapshot(maxSize), check);
        if (check.ready.containsAll(entry.members)) {
            entry.ready = null;
            return new VoteOutcome(Vote.ALL_READY, entry.snapshot(maxSize), check);
        }
        return new VoteOutcome(Vote.COUNTED, entry.snapshot(maxSize), check);
    }

    /** Ends every party, for shutdown; their last states. */
    synchronized @NotNull Collection<Party> clear() {
        List<Party> last = all();
        for (Entry entry : List.copyOf(byId.values())) end(entry);
        return last;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private Removal remove(Entry entry, UUID player, PartyLeave reason) {
        entry.members.remove(player);
        entry.offline.remove(player);
        byMember.remove(player);
        Ready broken = entry.ready;
        entry.ready = null;
        UUID newLeader = null;
        if (entry.leader.equals(player) && !entry.members.isEmpty()) {
            newLeader = entry.members.stream().filter(m -> !entry.offline.containsKey(m)).findFirst()
                    .orElse(entry.members.iterator().next());
            entry.leader = newLeader;
        }
        Party after = entry.members.isEmpty() ? new Party(entry.id, player, List.of(), List.of(), maxSize)
                : entry.snapshot(maxSize);
        boolean ended = entry.members.isEmpty() || endIfAlone(entry);
        if (entry.members.isEmpty()) end(entry);
        return new Removal(after, player, reason, newLeader, ended, broken);
    }

    /** A party of one waiting for nobody is not a party. */
    private boolean endIfAlone(Entry entry) {
        if (entry.members.size() > 1 || !entry.invites.isEmpty()) return false;
        end(entry);
        return true;
    }

    private void end(Entry entry) {
        byId.remove(entry.id);
        for (UUID member : entry.members) byMember.remove(member, entry);
        if (entry.ready != null) entry.ready.future.complete(ReadyResult.CHANGED);
        entry.ready = null;
    }
}
