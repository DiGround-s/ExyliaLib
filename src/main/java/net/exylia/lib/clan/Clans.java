package net.exylia.lib.clan;

import net.exylia.lib.clan.internal.ClanRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry point of the clan module.
 *
 * <p>A plugin that needs to know about clans calls one API and never branches
 * on which clan plugin runs underneath:
 *
 * <pre>{@code
 * boolean sameTeam = Clans.areAllied(attacker, defender);
 *
 * Clans.clanOf(player).ifPresent(clan -> {
 *     for (UUID ally : clan.allies()) {
 *         // broad alert to everyone who would care
 *     }
 * });
 * }</pre>
 *
 * <h2>The provider</h2>
 * One clan plugin is active at a time. The library detects whichever of
 * FactionsUUID, HuskTowns, ZelTeams, RunithClans, UltimateClans, Kingdoms,
 * SimpleClans or ExyliaClans is installed, in that order, so a server running
 * two of them gets the one that owns the most of the player's identity. An
 * external plugin can register its own through the {@link ClanBridge}
 * interface, with a priority that keeps it above automatic detection.

 * <p>Not every plugin has every concept. One without alliances answers no to
 * {@link #areAllied} rather than failing, and a caller never branches on which
 * plugin runs underneath.
 *
 * <h2>Caching</h2>
 * A player's clan is resolved once and remembered for a short time, because
 * these calls sit on hot paths: a damage event, a kill message, a scoreboard
 * line. The clan record is a snapshot, never live data, so holding it does not
 * tether the caller to the plugin's internals.
 *
 * <h2>Threading</h2>
 * Every method here is safe from any thread.
 *
 * @since 1.8.0
 */
public final class Clans {

    private Clans() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------
    // Player lookups
    // ------------------------------------------------------------------

    /** Returns the clan a player belongs to, empty when they have none or no
     * provider is installed. */
    public static @NotNull Optional<Clan> clanOf(@NotNull UUID player) {
        return ClanRuntime.clanOf(player);
    }

    /** Returns the clan a player belongs to. */
    public static @NotNull Optional<Clan> clanOf(@NotNull Player player) {
        return ClanRuntime.clanOf(player);
    }

    /** Returns a clan by its tag. */
    public static @NotNull Optional<Clan> byTag(@NotNull String tag) {
        return ClanRuntime.byTag(tag);
    }

    /** Returns a clan by its id. */
    public static @NotNull Optional<Clan> byId(@NotNull String id) {
        return ClanRuntime.byId(id);
    }

    /** Returns every clan the active provider knows about. */
    public static @NotNull Collection<Clan> all() {
        return ClanRuntime.all();
    }

    // ------------------------------------------------------------------
    // Questions
    // ------------------------------------------------------------------

    /** Returns whether a player belongs to any clan. */
    public static boolean hasClan(@NotNull UUID player) {
        return ClanRuntime.hasClan(player);
    }

    /** Returns whether a player belongs to any clan. */
    public static boolean hasClan(@NotNull Player player) {
        return ClanRuntime.hasClan(player);
    }

    /** Returns the name of the player's clan, empty when they have none. */
    public static @NotNull Optional<String> clanName(@NotNull UUID player) {
        return clanOf(player).map(Clan::name);
    }

    /** Returns the tag of the player's clan, empty when they have none. */
    public static @NotNull Optional<String> clanTag(@NotNull UUID player) {
        return clanOf(player).map(Clan::tag);
    }

    // ------------------------------------------------------------------
    // Relationships
    // ------------------------------------------------------------------

    /** Returns whether two players share a clan. */
    public static boolean areInSameClan(@NotNull UUID first, @NotNull UUID second) {
        return ClanRuntime.areInSameClan(first, second);
    }

    /** Returns whether two players share a clan. */
    public static boolean areInSameClan(@NotNull Player first, @NotNull Player second) {
        return ClanRuntime.areInSameClan(first, second);
    }

    /**
     * Returns whether two players' clans are allied.
     *
     * <p>False when either has no clan, even if the other would have considered
     * them an ally: a player without a clan has no team to honour the alliance.
     */
    public static boolean areAllied(@NotNull UUID first, @NotNull UUID second) {
        return ClanRuntime.areAllied(first, second);
    }

    /** Returns whether two players' clans are allied. */
    public static boolean areAllied(@NotNull Player first, @NotNull Player second) {
        return ClanRuntime.areAllied(first, second);
    }

    /**
     * Returns whether two players' clans are at war.
     *
     * <p>False when either has no clan: war needs two sides.
     */
    public static boolean areRivals(@NotNull UUID first, @NotNull UUID second) {
        return ClanRuntime.areRivals(first, second);
    }

    /** Returns whether two players' clans are at war. */
    public static boolean areRivals(@NotNull Player first, @NotNull Player second) {
        return ClanRuntime.areRivals(first, second);
    }

    /** Returns every member of the player's clan who is online. */
    public static @NotNull Collection<UUID> onlineMembersOf(@NotNull UUID player) {
        return ClanRuntime.onlineMembersOf(player);
    }

    // ------------------------------------------------------------------
    // Provider management
    // ------------------------------------------------------------------

    /**
     * Hands in a data source from outside the library.
     *
     * <p>Call in {@code onEnable()}, before any clan lookups. A registered
     * bridge is preferred over automatic detection, so a server with both
     * SimpleClans and ExyliaClans uses the latter when the plugin says so.
     *
     * @param bridge   the data source
     * @param priority higher beats lower; the built-in detection is 0
     */
    public static void registerBridge(@NotNull ClanBridge bridge, int priority) {
        ClanRuntime.registerBridge(bridge, priority);
    }

    /** Returns the name of the active provider, for diagnostics. */
    public static @NotNull String providerName() {
        return ClanRuntime.providerName();
    }

    /** Returns whether any clan provider is active. */
    public static boolean isSupported() {
        return ClanRuntime.isSupported();
    }

    /** Clears every cache entry. Called when a clan plugin signals a change. */
    public static void invalidate() {
        ClanRuntime.invalidate();
    }
}
