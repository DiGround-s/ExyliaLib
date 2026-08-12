package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * One clan plugin's data, on its own terms.
 *
 * <p>Every provider implements this once. Each one is confined to its own
 * class, referencing its own plugin's types, so a server whose provider
 * compiled but never loaded is not an error.
 *
 * <p>What a plugin does not support returns an empty optional or an empty set.
 * A provider that has no notion of alliances answers {@link Optional#empty()}
 * to {@link #alliesOf} rather than throwing, because "this clan has no allies"
 * is how the plugin works, not an exceptional condition.
 *
 * @since 1.8.0
 */
public interface ClanProvider {

    /** Returns whether this provider is available right now. */
    boolean enabled();

    /** Returns the name shown in diagnostics. */
    @NotNull String name();

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    /** Returns the clan a player belongs to, empty when they have none. */
    @NotNull Optional<Clan> clanOf(@NotNull UUID player);

    /** Returns the clan a player belongs to, empty when they have none. */
    @NotNull Optional<Clan> clanOf(@NotNull Player player);

    /** Returns a clan by its tag, empty when there is none. */
    @NotNull Optional<Clan> byTag(@NotNull String tag);

    /** Returns a clan by its id, empty when there is none. */
    @NotNull Optional<Clan> byId(@NotNull String id);

    /** Returns every clan the provider knows about. */
    @NotNull Collection<Clan> all();

    // ------------------------------------------------------------------
    // Questions about a player
    // ------------------------------------------------------------------

    /** Returns whether a player belongs to any clan. */
    boolean hasClan(@NotNull UUID player);

    /**
     * Returns the ids of the clans this clan is allied with.
     *
     * <p>Empty when the plugin has no alliances, or when the clan has none.
     */
    @NotNull Collection<String> alliesOf(@NotNull String clanId);

    /**
     * Returns the ids of the clans this clan is at war with.
     *
     * <p>Empty when the plugin has no rivalries, or when the clan has none.
     */
    @NotNull Collection<String> rivalsOf(@NotNull String clanId);

    // ------------------------------------------------------------------
    // Relationships between two players
    // ------------------------------------------------------------------

    /** Returns whether two players belong to the same clan. */
    boolean areInSameClan(@NotNull UUID player, @NotNull UUID other);

    /**
     * Returns whether two players' clans are allied.
     *
     * <p>Default: resolve both clans and compare. Override when the plugin has
     * a cheaper path, such as direct relationship tables.
     */
    default boolean areAllied(@NotNull UUID player, @NotNull UUID other) {
        Optional<Clan> first = clanOf(player);
        Optional<Clan> second = clanOf(other);
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        return first.get().alliedWith(second.get());
    }

    /**
     * Returns whether two players' clans are at war.
     */
    default boolean areRivals(@NotNull UUID player, @NotNull UUID other) {
        Optional<Clan> first = clanOf(player);
        Optional<Clan> second = clanOf(other);
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        return first.get().rivalOf(second.get());
    }

    /**
     * Returns every online player who is a member of the player's clan.
     *
     * <p>Default: get the clan and filter. Override when the plugin has a
     * cheaper direct lookup.
     */
    @NotNull Collection<UUID> onlineMembersOf(@NotNull UUID player);

    // ------------------------------------------------------------------
    // Maintenance
    // ------------------------------------------------------------------

    /** Called when a known player leaves, to clean any per-player state. */
    default void forget(@NotNull UUID player) {
    }

    // ------------------------------------------------------------------
    // Factory for built-in detection
    // ------------------------------------------------------------------

    /**
     * A supplier that can fail without taking the server with it.
     *
     * <p>Built-in providers use this so their plugin's absence is not an error:
     * the constructor itself does not throw; instead it stores whether the
     * plugin is present, and {@link #enabled()} answers truthfully.
     */
    @FunctionalInterface
    interface Factory {
        /** Creates the provider, or returns {@code null} when the plugin is
         * absent or its API could not be reached. */
        @org.jetbrains.annotations.Nullable ClanProvider tryCreate();
    }
}
