package net.exylia.lib.clan.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.clan.Clan;
import net.exylia.lib.clan.ClanBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The clan module's working parts.
 *
 * <p>One provider is active at a time, chosen by priority: registered bridges
 * beat automatic detection. The active one is then used for every lookup.
 *
 * <p>A player's clan is cached for a few seconds, because these calls run on
 * hot paths — a damage event, a kill message, a scoreboard refresh — and
 * asking the underlying plugin on every tick would turn our thin wrapper into
 * the bottleneck it was meant to avoid.
 */
public final class ClanRuntime {

    private static final Duration CACHE_TTL = Duration.ofSeconds(3);

    private static Logger logger = Logger.getLogger("ExyliaLib");

    /** The active provider, guarded by {@link #LOCK}. */
    private static volatile ClanProvider active;

    /** Pairs of (bridge, priority) that were registered but may not be active. */
    private static final Map<ClanBridge, Integer> bridges = new ConcurrentHashMap<>();

    /** Caffeine bounded and timed. Size is per-player lookups; the clan-by-id
     * cache is a separate loading layer inside each provider if needed. */
    private static final Cache<UUID, Optional<Clan>> playerCache = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterWrite(CACHE_TTL)
            .build();

    private static final Object LOCK = new Object();

    private ClanRuntime() {
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    /**
     * Scans for a clan plugin and picks the active provider.
     *
     * <p>Called by ExyliaLib at startup.
     */
    public static void init(Plugin libPlugin) {
        logger = libPlugin.getLogger();
        detect();
    }

    private static void detect() {
        synchronized (LOCK) {
            // Registered bridges beat automatic detection.
            if (!bridges.isEmpty()) {
                var entry = Collections.max(bridges.entrySet(), Map.Entry.comparingByValue());
                active = bridgeAdapter(entry.getKey());
                logger.info("Clans: using registered bridge '"
                        + entry.getKey().name() + "'.");
                return;
            }

            for (ClanProvider.Factory factory : builtIn()) {
                ClanProvider provider = factory.tryCreate();
                if (provider != null && provider.enabled()) {
                    active = provider;
                    logger.info("Clans: detected '" + provider.name() + "'.");
                    return;
                }
            }
            active = null;
        }
    }

    private static List<ClanProvider.Factory> builtIn() {
        List<ClanProvider.Factory> factories = new ArrayList<>();
        factories.add(FactionsProvider::tryCreate);
        factories.add(HuskTownsProvider::tryCreate);
        factories.add(ZelTeamsProvider::tryCreate);
        factories.add(RunithClansProvider::tryCreate);
        factories.add(UltimateClansProvider::tryCreate);
        factories.add(KingdomsProvider::tryCreate);
        factories.add(SimpleClansProvider::tryCreate);
        factories.add(ExyliaClansProvider::tryCreate);
        return factories;
    }

    // ------------------------------------------------------------------
    // Public queries
    // ------------------------------------------------------------------

    public static Optional<Clan> clanOf(UUID player) {
        ClanProvider provider = active;
        if (provider == null) {
            return Optional.empty();
        }
        return playerCache.get(player, provider::clanOf);
    }

    public static Optional<Clan> clanOf(Player player) {
        ClanProvider provider = active;
        if (provider == null) {
            return Optional.empty();
        }
        return playerCache.get(player.getUniqueId(), id -> provider.clanOf(player));
    }

    public static Optional<Clan> byTag(String tag) {
        ClanProvider provider = active;
        return provider == null ? Optional.empty() : provider.byTag(tag);
    }

    public static Optional<Clan> byId(String id) {
        ClanProvider provider = active;
        return provider == null ? Optional.empty() : provider.byId(id);
    }

    public static Collection<Clan> all() {
        ClanProvider provider = active;
        return provider == null ? List.of() : provider.all();
    }

    public static boolean hasClan(UUID player) {
        ClanProvider provider = active;
        return provider != null && provider.hasClan(player);
    }

    public static boolean hasClan(Player player) {
        ClanProvider provider = active;
        return provider != null && provider.hasClan(player.getUniqueId());
    }

    public static boolean areInSameClan(UUID first, UUID second) {
        ClanProvider provider = active;
        return provider != null && provider.areInSameClan(first, second);
    }

    public static boolean areInSameClan(Player first, Player second) {
        ClanProvider provider = active;
        return provider != null && provider.areInSameClan(
                first.getUniqueId(), second.getUniqueId());
    }

    public static boolean areAllied(UUID first, UUID second) {
        ClanProvider provider = active;
        return provider != null && provider.areAllied(first, second);
    }

    public static boolean areAllied(Player first, Player second) {
        ClanProvider provider = active;
        return provider != null && provider.areAllied(
                first.getUniqueId(), second.getUniqueId());
    }

    public static boolean areRivals(UUID first, UUID second) {
        ClanProvider provider = active;
        return provider != null && provider.areRivals(first, second);
    }

    public static boolean areRivals(Player first, Player second) {
        ClanProvider provider = active;
        return provider != null && provider.areRivals(
                first.getUniqueId(), second.getUniqueId());
    }

    public static Collection<UUID> onlineMembersOf(UUID player) {
        ClanProvider provider = active;
        return provider == null ? List.of() : provider.onlineMembersOf(player);
    }

    // ------------------------------------------------------------------
    // Provider management
    // ------------------------------------------------------------------

    public static void registerBridge(ClanBridge bridge, int priority) {
        synchronized (LOCK) {
            bridges.put(bridge, priority);
            // A new bridge climbs over both the previous one and automatic
            // detection.
            if (active == null || bridges.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null) == bridge) {
                active = bridgeAdapter(bridge);
                invalidate();
            }
        }
    }

    public static String providerName() {
        ClanProvider provider = active;
        return provider == null ? "" : provider.name();
    }

    public static boolean isSupported() {
        return active != null;
    }

    public static void invalidate() {
        playerCache.invalidateAll();
    }

    /** Forgets a player who left. */
    public static void forget(UUID player) {
        playerCache.invalidate(player);
        ClanProvider provider = active;
        if (provider != null) {
            safely(() -> provider.forget(player));
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            active = null;
            bridges.clear();
            playerCache.invalidateAll();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Adapts a bridge into the full provider interface. */
    private static ClanProvider bridgeAdapter(ClanBridge bridge) {
        return new BridgeAdapter(bridge);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            logger.warning("A clan provider failed: " + t.getMessage());
        }
    }

    /** For tests: forces a provider in place of detection. */
    static void install(ClanProvider provider) {
        synchronized (LOCK) {
            active = provider;
        }
    }
}
