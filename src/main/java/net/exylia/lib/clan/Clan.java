package net.exylia.lib.clan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A clan as this library sees it.
 *
 * <p>Every clan plugin has something, but no two agree on what to call it.
 * Factions has relationships, SimpleClans has allies and rivals, and
 * UltimateClans has neither. A field that does not apply returns an empty set
 * or zero, so a caller that asks about alliances never branches on which plugin
 * runs underneath.
 *
 * <p>Values are snapshots: the plugin's state at the moment the call was made.
 * Nothing here is live, and nothing is cached by the caller's thread.
 *
 * @param id          the clan's unique identifier, as its plugin gives it
 * @param name        the human-readable name
 * @param tag         the short tag, or the name when the plugin has no tags
 * @param displayName the name shown in chat, or the name when unset
 * @param leaders     every player who can disband or promote
 * @param moderators  every player who can invite and kick
 * @param members     every player who belongs
 * @param onlineCount how many members are online right now
 * @param level       the clan's level, or 0
 * @param balance     the clan's balance, or 0
 * @param createdAt   when the clan was formed
 * @param maxMembers  the most it can hold, or 0 when unlimited
 * @param allies      every other clan's id that this one has an alliance with;
 *                    empty when the plugin has no alliances
 * @param rivals      every other clan's id that this one is at war with; empty
 *                    when the plugin has no rivalries
 * @param provider    which clan plugin supplied this snapshot
 * @since 1.8.0
 */
public record Clan(
        @NotNull String id,
        @NotNull String name,
        @NotNull String tag,
        @NotNull String displayName,
        @NotNull Set<UUID> leaders,
        @NotNull Set<UUID> moderators,
        @NotNull Set<UUID> members,
        int onlineCount,
        int level,
        double balance,
        long createdAt,
        int maxMembers,
        @NotNull Set<String> allies,
        @NotNull Set<String> rivals,
        @NotNull String provider) {

    public Clan {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a clan needs an id");
        }
        if (name == null) {
            name = id;
        }
        if (tag == null) {
            tag = name;
        }
        if (displayName == null) {
            displayName = name;
        }
        leaders = freeze(leaders);
        moderators = freeze(moderators);
        members = freeze(members);
        allies = freeze(allies);
        rivals = freeze(rivals);
        if (provider == null) {
            provider = "";
        }
    }

    private static <T> Set<T> freeze(Set<T> set) {
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    /**
     * A clan with only the essentials: name, members and leaders.
     *
     * <p>The rest stays at defaults, which is how lightweight integrations are
     * done.
     */
    public static @NotNull Clan of(@NotNull String id, @NotNull String name, @NotNull String tag,
                                   @NotNull Set<UUID> leaders, @NotNull Set<UUID> members,
                                   @NotNull String provider) {
        return new Clan(id, name, tag, name, leaders, Set.of(), members,
                0, 0, 0, 0L, 0, Set.of(), Set.of(), provider);
    }

    /** Returns every member, regardless of rank. */
    public @NotNull Set<UUID> allMembers() {
        if (leaders.isEmpty() && moderators.isEmpty() && members.isEmpty()) {
            return Set.of();
        }
        Set<UUID> all = new java.util.HashSet<>(leaders.size() + moderators.size() + members.size());
        all.addAll(leaders);
        all.addAll(moderators);
        all.addAll(members);
        return Collections.unmodifiableSet(all);
    }

    /** Returns the total membership, online and offline. */
    public int memberCount() {
        return allMembers().size();
    }

    /** Returns whether a player belongs to this clan. */
    public boolean isMember(@NotNull UUID player) {
        return leaders.contains(player) || moderators.contains(player) || members.contains(player);
    }

    /** Returns whether a player is a leader. */
    public boolean isLeader(@NotNull UUID player) {
        return leaders.contains(player);
    }

    /** Returns whether a player is a moderator. */
    public boolean isModerator(@NotNull UUID player) {
        return moderators.contains(player);
    }

    /** Returns whether a player is both a member and online. */
    public boolean isOnline(@NotNull UUID player, @NotNull Predicate<UUID> onlineCheck) {
        return isMember(player) && onlineCheck.test(player);
    }

    /** Returns whether this clan has an alliance with another. */
    public boolean isAllied(@NotNull String otherId) {
        return allies.contains(otherId);
    }

    /** Returns whether this clan is at war with another. */
    public boolean isRival(@NotNull String otherId) {
        return rivals.contains(otherId);
    }

    /**
     * Returns whether two ids belong to clans that are allied.
     *
     * <p>Symmetric: the other clan's alliances are not consulted, since this is
     * a snapshot of one clan at a time.
     */
    public boolean alliedWith(@NotNull Clan other) {
        return allies.contains(other.id()) || other.allies.contains(id);
    }

    /**
     * Returns whether two ids belong to clans that are at war.
     *
     * <p>Symmetric for the same reason as {@link #alliedWith}.
     */
    public boolean rivalOf(@NotNull Clan other) {
        return rivals.contains(other.id()) || other.rivals.contains(id);
    }

    // ------------------------------------------------------------------
    // Builder for providers
    // ------------------------------------------------------------------

    public static @NotNull Builder builder(@NotNull String id) {
        return new Builder(id);
    }

    /** A builder for when the provider is constructing the record piecemeal. */
    public static final class Builder {

        private final String id;
        private String name;
        private String tag;
        private String displayName;
        private final Set<UUID> leaders = new java.util.LinkedHashSet<>();
        private final Set<UUID> moderators = new java.util.LinkedHashSet<>();
        private final Set<UUID> members = new java.util.LinkedHashSet<>();
        private int onlineCount;
        private int level;
        private double balance;
        private long createdAt;
        private int maxMembers;
        private final Set<String> allies = new java.util.HashSet<>();
        private final Set<String> rivals = new java.util.HashSet<>();
        private String provider;

        Builder(String id) {
            this.id = id;
        }

        public @NotNull Builder name(@Nullable String value) {
            this.name = value != null && !value.isEmpty() ? value : id;
            return this;
        }

        public @NotNull Builder tag(@Nullable String value) {
            this.tag = value != null && !value.isEmpty() ? value : name;
            return this;
        }

        public @NotNull Builder displayName(@Nullable String value) {
            this.displayName = value;
            return this;
        }

        public @NotNull Builder leader(@NotNull UUID player) {
            leaders.add(player);
            return this;
        }

        public @NotNull Builder leaders(@NotNull Collection<UUID> players) {
            leaders.addAll(players);
            return this;
        }

        public @NotNull Builder moderator(@NotNull UUID player) {
            moderators.add(player);
            return this;
        }

        public @NotNull Builder moderators(@NotNull Collection<UUID> players) {
            moderators.addAll(players);
            return this;
        }

        public @NotNull Builder member(@NotNull UUID player) {
            members.add(player);
            return this;
        }

        public @NotNull Builder members(@NotNull Collection<UUID> players) {
            members.addAll(players);
            return this;
        }

        public @NotNull Builder onlineCount(int value) {
            this.onlineCount = value;
            return this;
        }

        public @NotNull Builder level(int value) {
            this.level = value;
            return this;
        }

        public @NotNull Builder balance(double value) {
            this.balance = value;
            return this;
        }

        public @NotNull Builder createdAt(long value) {
            this.createdAt = value;
            return this;
        }

        public @NotNull Builder maxMembers(int value) {
            this.maxMembers = value;
            return this;
        }

        public @NotNull Builder ally(@NotNull String clanId) {
            allies.add(clanId);
            return this;
        }

        public @NotNull Builder allies(@NotNull Collection<String> clanIds) {
            allies.addAll(clanIds);
            return this;
        }

        public @NotNull Builder rival(@NotNull String clanId) {
            rivals.add(clanId);
            return this;
        }

        public @NotNull Builder rivals(@NotNull Collection<String> clanIds) {
            rivals.addAll(clanIds);
            return this;
        }

        public @NotNull Builder provider(@NotNull String value) {
            this.provider = value;
            return this;
        }

        public @NotNull Clan build() {
            return new Clan(id,
                    name != null ? name : id,
                    tag != null ? tag : (name != null ? name : id),
                    displayName != null ? displayName : (name != null ? name : id),
                    Set.copyOf(leaders), Set.copyOf(moderators), Set.copyOf(members),
                    onlineCount, level, balance, createdAt, maxMembers,
                    Set.copyOf(allies), Set.copyOf(rivals),
                    provider != null ? provider : "");
        }
    }
}
