package net.exylia.lib.util.combat.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.util.combat.CombatBridge;
import net.exylia.lib.util.combat.CombatStats;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The combat module's working parts.
 *
 * <p>One provider is active at a time, chosen the same way the clan module
 * chooses one: a registered bridge beats automatic detection, and detection
 * takes the first plugin that is actually installed.
 *
 * <h2>What is cached, and what is not</h2>
 * Only the tag: "is this player fighting" is asked on damage, on movement, on
 * every scoreboard refresh, and the answer is the same for a whole second of
 * game time. The remaining time is <em>not</em> cached — it is a countdown, and
 * a cached countdown is a number that sits still. Neither is a write: tagging a
 * player through a stale value would tag them for a fight that already ended.
 */
public final class CombatRuntime {

    /**
     * How long a tag answer is reused.
     *
     * <p>Short on purpose. A tag lasts fifteen seconds or so and the cost of
     * being half a second late is nothing, while the cost of asking another
     * plugin's map on every damage event is the reason this cache exists.
     */
    private static final Duration TAG_TTL = Duration.ofMillis(500);

    private static final Cache<UUID, Boolean> TAGGED = Caffeine.newBuilder()
            .expireAfterWrite(TAG_TTL)
            .maximumSize(2048)
            .build();

    /** Stats change on a kill, not on a tick, so they can be held longer. */
    private static final Cache<UUID, Optional<CombatStats>> STATS = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(2048)
            .build();

    private static final Map<CombatBridge, Integer> BRIDGES = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private static volatile CombatProvider active;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private CombatRuntime() {
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    /** Finds a combat plugin. Called by ExyliaLib at startup. */
    public static void init(Plugin plugin) {
        logger = plugin.getLogger();
        detect();
    }

    private static void detect() {
        synchronized (LOCK) {
            if (!BRIDGES.isEmpty()) {
                Map.Entry<CombatBridge, Integer> best =
                        Collections.max(BRIDGES.entrySet(), Map.Entry.comparingByValue());
                active = new BridgeAdapter(best.getKey());
                logger.info("Combat: using registered bridge '" + best.getKey().name() + "'.");
                return;
            }
            for (CombatProvider.Factory factory : List.<CombatProvider.Factory>of(
                    DeluxeCombatProvider::tryCreate,
                    PvpManagerProvider::tryCreate)) {
                CombatProvider provider = factory.tryCreate();
                if (provider != null && provider.enabled()) {
                    active = provider;
                    logger.info("Combat: detected '" + provider.name() + "'.");
                    return;
                }
            }
            active = null;
        }
    }

    public static void registerBridge(CombatBridge bridge, int priority) {
        synchronized (LOCK) {
            BRIDGES.put(bridge, priority);
            CombatBridge best = Collections.max(BRIDGES.entrySet(),
                    Map.Entry.comparingByValue()).getKey();
            // A bridge climbs over both the previous one and detection: a
            // server that registered one wants theirs, not ours.
            active = new BridgeAdapter(best);
            invalidate();
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            active = null;
            BRIDGES.clear();
            invalidate();
        }
    }

    /** Forgets a player who left. */
    public static void forget(UUID player) {
        TAGGED.invalidate(player);
        STATS.invalidate(player);
    }

    public static void invalidate() {
        TAGGED.invalidateAll();
        STATS.invalidateAll();
    }

    /** For tests: forces a provider in place of detection. */
    static void install(CombatProvider provider) {
        synchronized (LOCK) {
            active = provider;
            BRIDGES.clear();
            invalidate();
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public static boolean isTagged(Player player) {
        CombatProvider provider = active;
        if (provider == null) {
            return false;
        }
        Boolean cached = TAGGED.get(player.getUniqueId(),
                id -> ask(() -> provider.isTagged(player), false));
        return Boolean.TRUE.equals(cached);
    }

    public static Duration remaining(Player player) {
        CombatProvider provider = active;
        if (provider == null) {
            return Duration.ZERO;
        }
        Duration left = ask(() -> provider.remaining(player), Duration.ZERO);
        return left == null || left.isNegative() ? Duration.ZERO : left;
    }

    public static Optional<Player> opponentOf(Player player) {
        CombatProvider provider = active;
        if (provider == null) {
            return Optional.empty();
        }
        return ask(() -> provider.opponentOf(player), Optional.empty());
    }

    public static void tag(Player target, Player attacker, Duration duration) {
        CombatProvider provider = active;
        if (provider == null) {
            return;
        }
        ask(() -> {
            provider.tag(target, attacker, duration);
            return null;
        }, null);
        // The player's state just changed, and a cached "not tagged" would
        // outlive the tag that was the whole point of the call.
        TAGGED.invalidate(target.getUniqueId());
    }

    public static void untag(Player player) {
        CombatProvider provider = active;
        if (provider == null) {
            return;
        }
        ask(() -> {
            provider.untag(player);
            return null;
        }, null);
        TAGGED.invalidate(player.getUniqueId());
    }

    public static boolean isProtected(Player player) {
        CombatProvider provider = active;
        return provider != null && ask(() -> provider.isProtected(player), false);
    }

    public static boolean isPvpEnabled(Player player) {
        CombatProvider provider = active;
        // Fails open, here and in every provider: an integration that broke
        // must not be the reason nobody on the server can fight.
        return provider == null || ask(() -> provider.isPvpEnabled(player), true);
    }

    public static void setPvpEnabled(Player player, boolean enabled) {
        CombatProvider provider = active;
        if (provider != null) {
            ask(() -> {
                provider.setPvpEnabled(player, enabled);
                return null;
            }, null);
        }
    }

    public static boolean canAttack(Player attacker, Player defender) {
        CombatProvider provider = active;
        return provider == null || ask(() -> provider.canAttack(attacker, defender), true);
    }

    public static Optional<CombatStats> statsOf(Player player) {
        CombatProvider provider = active;
        if (provider == null) {
            return Optional.empty();
        }
        return STATS.get(player.getUniqueId(),
                id -> ask(() -> provider.statsOf(player), Optional.empty()));
    }

    public static String providerName() {
        CombatProvider provider = active;
        return provider == null ? "" : provider.name();
    }

    public static boolean isSupported() {
        return active != null;
    }

    /**
     * Asks the combat plugin, falling back when it throws.
     *
     * <p>Every fallback is what a server with no combat plugin would answer, so
     * a broken integration degrades to "nobody is fighting" instead of taking
     * the damage event down with it.
     */
    private static <T> T ask(java.util.function.Supplier<T> question, T fallback) {
        try {
            return question.get();
        } catch (Throwable broken) {
            logger.warning("The combat plugin failed, so it was ignored: " + broken);
            return fallback;
        }
    }

    /** Wraps a registered bridge in the internal shape. */
    private record BridgeAdapter(CombatBridge bridge) implements CombatProvider {

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public String name() {
            return bridge.name();
        }

        @Override
        public boolean isTagged(Player player) {
            return bridge.isTagged(player);
        }

        @Override
        public Duration remaining(Player player) {
            return bridge.remaining(player);
        }

        @Override
        public Optional<Player> opponentOf(Player player) {
            return bridge.opponentOf(player);
        }

        @Override
        public void tag(Player target, Player attacker, Duration duration) {
            bridge.tag(target, attacker, duration);
        }

        @Override
        public void untag(Player player) {
            bridge.untag(player);
        }

        @Override
        public boolean isProtected(Player player) {
            return bridge.isProtected(player);
        }

        @Override
        public boolean isPvpEnabled(Player player) {
            return bridge.isPvpEnabled(player);
        }

        @Override
        public void setPvpEnabled(Player player, boolean enabled) {
            bridge.setPvpEnabled(player, enabled);
        }

        @Override
        public boolean canAttack(Player attacker, Player defender) {
            return bridge.canAttack(attacker, defender);
        }

        @Override
        public Optional<CombatStats> statsOf(Player player) {
            return bridge.statsOf(player);
        }
    }
}
