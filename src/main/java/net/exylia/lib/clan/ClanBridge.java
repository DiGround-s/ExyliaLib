package net.exylia.lib.clan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A data source a plugin outside ExyliaLib can hand in, using no Exylia or
 * Bukkit types.
 *
 * <p>This is the same contract ExyliaCommons gave its own external providers,
 * plus the alliance and rivalry tables commons never added. A plugin that wants
 * the library to see its clans implements this and calls
 * {@link net.exylia.lib.clan.Clans#registerBridge(ClanBridge)} in its {@code onEnable()}.
 *
 * <p>Only standard library types appear: a UUID, a string, an int. That is so
 * the implementation lives anywhere, not just inside a Bukkit plugin.
 *
 * <h2>Snapshots</h2>
 * Every method returns a snapshot. Nothing here is live, and nothing here
 * caches: the library holds the snapshot for a short time and forgets it.
 *
 * @since 1.8.0
 */
public interface ClanBridge {

    /** The name shown in diagnostics. */
    @NotNull String name();

    /** Returns whether this bridge is ready. */
    boolean available();

    /** Returns the snapshot for a player, or {@code null} when they have none. */
    @Nullable Snapshot of(@NotNull UUID player);

    /** Returns the snapshot for a clan, or {@code null} when there is none. */
    @Nullable Snapshot byTag(@NotNull String tag);

    /** Returns the snapshot for a clan, or {@code null} when there is none. */
    @Nullable Snapshot byId(@NotNull String id);

    /** Returns every clan this bridge knows. */
    @NotNull Collection<Snapshot> all();

    /** Returns whether a player belongs to any clan. */
    boolean hasClan(@NotNull UUID player);

    /**
     * Returns the ids of allied clans, empty when none.
     */
    @NotNull Set<String> alliesOf(@NotNull String clanId);

    /**
     * Returns the ids of rival clans, empty when none.
     */
    @NotNull Set<String> rivalsOf(@NotNull String clanId);

    /**
     * Returns whether two players share a clan.
     */
    boolean sameClan(@NotNull UUID player, @NotNull UUID other);

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    /**
     * A clan as the external plugin sees it.
     *
     * <p>Only the fields the plugin fills in are present. Unset ones stay at
     * their defaults, which every bridge implementor must document.
     */
    record Snapshot(
            @NotNull String id,
            @NotNull String name,
            @NotNull String tag,
            @Nullable String displayName,
            @NotNull Set<UUID> leaders,
            @NotNull Set<UUID> moderators,
            @NotNull Set<UUID> members,
            int onlineCount,
            int level,
            double balance,
            long createdAt,
            int maxMembers,
            @NotNull Set<String> allies,
            @NotNull Set<String> rivals) {

        public Snapshot {
            leaders = leaders == null ? Set.of() : Set.copyOf(leaders);
            moderators = moderators == null ? Set.of() : Set.copyOf(moderators);
            members = members == null ? Set.of() : Set.copyOf(members);
            allies = allies == null ? Set.of() : Set.copyOf(allies);
            rivals = rivals == null ? Set.of() : Set.copyOf(rivals);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a clan needs a name");
            }
        }

        /** A minimal snapshot with only the fields every plugin has. */
        public static @NotNull Snapshot of(@NotNull String id, @NotNull String name,
                                           @NotNull String tag,
                                           @NotNull Set<UUID> leaders,
                                           @NotNull Set<UUID> members) {
            return new Snapshot(id, name, tag, null, leaders, Set.of(), members,
                    0, 0, 0, 0L, 0, Set.of(), Set.of());
        }

        /** A snapshot that knows the difference between leaders and moderators. */
        public static @NotNull Snapshot of(@NotNull String id, @NotNull String name,
                                           @NotNull String tag,
                                           @NotNull Set<UUID> leaders,
                                           @NotNull Set<UUID> moderators,
                                           @NotNull Set<UUID> members) {
            return new Snapshot(id, name, tag, null, leaders, moderators, members,
                    0, 0, 0, 0L, 0, Set.of(), Set.of());
        }
    }
}
