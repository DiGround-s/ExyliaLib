package net.exylia.lib.skull.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.skull.SkullSource;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The one place heads are resolved and remembered.
 *
 * <p>Shared by every plugin, because the library is installed once: two
 * plugins asking for the same head ask Mojang once between them, not once
 * each. That is the whole reason this belongs in ExyliaLib rather than being
 * copied into each plugin.
 *
 * <h2>The layers, cheapest first</h2>
 * <ol>
 *   <li><b>Texture cache.</b> A source key to a base64 texture. Textures are
 *       small strings, so tens of thousands cost little.
 *   <li><b>Online players.</b> A connected player already carries their skin;
 *       asking Mojang for it would be absurd.
 *   <li><b>Disk.</b> Survives restarts, so the first menu after a reboot is
 *       instant instead of a burst of HTTP.
 *   <li><b>Mojang.</b> Off the main thread, rate-limited, and shared: forty
 *       menu slots asking for the same player make one request.
 * </ol>
 *
 * <p>Item stacks are deliberately <em>not</em> cached, only textures. An
 * {@code ItemStack} is mutable and ten times the size of the string it is
 * built from, and building one is cheap; caching them, as ExyliaCommons did,
 * meant every read had to defensively clone anyway.
 */
public final class SkullRuntime {

    /** Textures by source key. The hot path, and the one that must never miss. */
    private static final Cache<String, String> TEXTURES = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterAccess(Duration.ofHours(6))
            .build();

    /**
     * Names that Mojang has no player for.
     *
     * <p>Kept separately and briefly: without it, a menu built from a typo'd
     * name asks Mojang for it every single time the menu opens.
     */
    private static final Cache<String, Boolean> UNKNOWN = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    /**
     * Lookups already in flight, by source key.
     *
     * <p>The single most valuable thing here. A menu of forty heads for the
     * same player, or forty players opening the same menu at once, collapses
     * into one request; ExyliaCommons had this too, and it is the reason it
     * never hammered Mojang.
     */
    private static final Map<String, CompletableFuture<String>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private static volatile Plugin owner;
    private static volatile TaskScheduler scheduler;
    private static volatile Lookup mojang;
    private static volatile SkullStore store;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private SkullRuntime() {
    }

    /**
     * Starts the module. Called once, by the library itself.
     *
     * @param plugin the library plugin
     */
    public static void init(Plugin plugin) {
        owner = plugin;
        scheduler = Tasks.of(plugin);
        logger = plugin.getLogger();
        mojang = new MojangApi(Duration.ofSeconds(5),
                Duration.ofMinutes(10).toMillis(),
                Duration.ofMinutes(1).toMillis(),
                plugin.getLogger());
        store = new SkullStore(plugin.getDataFolder().toPath().resolve("skull-cache.txt"),
                plugin.getLogger());
        // Reading the file is I/O, and startup is not the place for it.
        scheduler.runAsync(() -> store.load(TEXTURES));
    }

    /** Writes the cache out and clears memory. */
    public static void shutdown() {
        SkullStore current = store;
        if (current != null) {
            // Inline, not scheduled: the server is going down and a queued
            // task would never run.
            current.save(TEXTURES);
        }
        TEXTURES.invalidateAll();
        UNKNOWN.invalidateAll();
        IN_FLIGHT.clear();
    }

    /** The scheduler heads hop back onto. */
    static TaskScheduler scheduler() {
        return scheduler;
    }

    static Logger logger() {
        return logger;
    }

    /** Returns whether the module is running. */
    public static boolean isReady() {
        return owner != null;
    }

    /**
     * Returns a texture already known, without asking anyone.
     *
     * @param source what to look up
     * @return the texture, or {@code null} when it is not known yet
     */
    public static String cached(SkullSource source) {
        String key = source.key();
        return switch (source) {
            // These two carry their own texture: nothing to look up, ever.
            case SkullSource.Texture texture -> texture.base64();
            case SkullSource.Url url -> TEXTURES.get(key, ignored -> Textures.fromUrl(url.url()));
            case SkullSource.PlayerName name -> fromMemoryOrOnline(key, name.name(), null);
            case SkullSource.PlayerId id -> fromMemoryOrOnline(key, null, id.id());
        };
    }

    /**
     * Looks in memory, then at who is connected.
     *
     * <p>An online player's skin is already on this server; fetching it over
     * the network would be a round trip for something sitting in RAM.
     */
    private static String fromMemoryOrOnline(String key, String name, UUID id) {
        String known = TEXTURES.getIfPresent(key);
        if (known != null) {
            return known;
        }
        String live = OnlineSkins.textureOf(name, id);
        if (live != null) {
            TEXTURES.put(key, live);
            return live;
        }
        return null;
    }

    /**
     * Resolves a source, fetching if it has to.
     *
     * <p>The returned future completes on whatever thread finished the work;
     * callers that touch the world hop back themselves.
     *
     * @param source what to look up
     * @return the texture, completing with {@code null} when there is none
     */
    public static CompletableFuture<String> resolve(SkullSource source) {
        String known = cached(source);
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        if (!source.needsLookup()) {
            return CompletableFuture.completedFuture(null);
        }
        String key = source.key();
        if (UNKNOWN.getIfPresent(key) != null) {
            // Asked recently, and Mojang had never heard of them.
            return CompletableFuture.completedFuture(null);
        }
        // computeIfAbsent, so two callers racing for the same head produce one
        // request and both wait on it.
        return IN_FLIGHT.computeIfAbsent(key, ignored -> startLookup(source, key));
    }

    private static CompletableFuture<String> startLookup(SkullSource source, String key) {
        CompletableFuture<String> future = new CompletableFuture<>();
        TaskScheduler tasks = scheduler;
        if (tasks == null) {
            // The library is not running. Nothing to fetch with.
            future.complete(null);
            return future;
        }
        tasks.runAsync(() -> {
            String texture = null;
            try {
                texture = fetch(source);
            } finally {
                if (texture != null) {
                    TEXTURES.put(key, texture);
                    SkullStore current = store;
                    if (current != null) {
                        current.remember(key, texture);
                    }
                } else if (!mojang.isBackedOff()) {
                    // Only remember a miss when we actually got an answer: a
                    // rate-limited lookup is not evidence the player is fake.
                    UNKNOWN.put(key, Boolean.TRUE);
                }
                IN_FLIGHT.remove(key);
                future.complete(texture);
            }
        });
        return future;
    }

    /** The network part, always off the main thread. */
    private static String fetch(SkullSource source) {
        Lookup api = mojang;
        if (api == null) {
            return null;
        }
        return switch (source) {
            case SkullSource.PlayerId id -> api.textureOf(id.id());
            case SkullSource.PlayerName name -> {
                UUID id = offlineId(name.name());
                if (id == null) {
                    id = api.idOf(name.name());
                }
                yield id == null ? null : api.textureOf(id);
            }
            // Neither of these can reach here: both are answered from cached().
            case SkullSource.Texture texture -> texture.base64();
            case SkullSource.Url url -> Textures.fromUrl(url.url());
        };
    }

    /**
     * The id of someone who has played here before.
     *
     * <p>Saves the name-to-id request outright for any returning player, which
     * on an established server is most of them.
     */
    private static UUID offlineId(String name) {
        try {
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
        } catch (Exception noServer) {
            return null;
        }
    }

    /** Forgets one entry, in memory and on disk. */
    public static void invalidate(SkullSource source) {
        String key = source.key();
        TEXTURES.invalidate(key);
        UNKNOWN.invalidate(key);
        SkullStore current = store;
        if (current != null) {
            current.forget(key);
        }
    }

    /** Forgets everything. */
    public static void invalidateAll() {
        TEXTURES.invalidateAll();
        UNKNOWN.invalidateAll();
        IN_FLIGHT.clear();
        SkullStore current = store;
        if (current != null) {
            current.clear();
        }
        Lookup api = mojang;
        if (api != null) {
            api.clearBackoff();
        }
    }

    /** How many textures are held in memory. */
    public static long size() {
        return TEXTURES.estimatedSize();
    }

    /** How many lookups are in flight. */
    public static int pending() {
        return IN_FLIGHT.size();
    }

    /** Whether Mojang lookups are currently paused. */
    public static boolean isBackedOff() {
        Lookup api = mojang;
        return api != null && api.isBackedOff();
    }

    /** How long the pause still has to run, in millis. */
    public static long backoffRemaining() {
        Lookup api = mojang;
        return api == null ? 0L : api.backoffRemaining();
    }

    /** Test seam: drives the module without a server. */
    public static void installForTests(Plugin plugin, Lookup api, SkullStore testStore) {
        owner = plugin;
        scheduler = Tasks.of(plugin);
        logger = plugin.getLogger();
        mojang = api;
        store = testStore;
        TEXTURES.invalidateAll();
        UNKNOWN.invalidateAll();
        IN_FLIGHT.clear();
    }

    /** Test seam: pre-seeds a texture without going near the network. */
    public static void seed(SkullSource source, String texture) {
        TEXTURES.put(source.key(), texture);
    }
}
