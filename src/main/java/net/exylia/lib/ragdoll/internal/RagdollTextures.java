package net.exylia.lib.ragdoll.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which pieces of which skins already have a texture, and where that is kept.
 *
 * <h2>In the plugin's database, not the library's</h2>
 * The library has no database of its own. Every plugin that shows ragdolls
 * already has one &mdash; its {@code database.yml}, H2 until its owner says
 * otherwise &mdash; so the textures go there, in {@code exylia_ragdoll_skins},
 * the way owed rewards do. A network that points those plugins at one MySQL
 * uploads each skin once for every server on it.
 *
 * <h2>In memory, only what is about to be worn</h2>
 * A server with ten thousand registered players has made textures for all of
 * them, and needs the ones of the few dozen online. The memory side is a
 * bounded cache filled by looking a skin up when its owner joins; the database
 * is what remembers the rest.
 */
@ApiStatus.Internal
public final class RagdollTextures {

    /** The library itself, which has no database to lend. */
    private static final String LIBRARY = "ExyliaLib";

    /** Texture ids by piece fingerprint, for the skins in use. */
    private static final Cache<String, String> KNOWN = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    /** One table per plugin that shows ragdolls, by plugin name. */
    private static final Map<String, Repository<RagdollTextureRow>> STORES = new ConcurrentHashMap<>();

    private static volatile Logger logger = Logger.getLogger(LIBRARY);

    private RagdollTextures() {
    }

    /** Where problems with a store are said. Called by the library at startup. */
    static void logger(Logger value) {
        logger = value;
    }

    /**
     * Keeps textures in a plugin's database from now on.
     *
     * @param plugin a plugin that shows ragdolls
     */
    public static void register(Plugin plugin) {
        String name = plugin.getName();
        if (LIBRARY.equals(name) || STORES.containsKey(name)) {
            return;
        }
        boolean[] added = {false};
        STORES.computeIfAbsent(name, key -> {
            added[0] = true;
            return Databases.of(plugin).repository(RagdollTextureRow.class);
        });
        if (added[0]) {
            // Skins read before there was anywhere to look them up are looked
            // up again the next time they are needed.
            SkinCache.stale();
        }
    }

    /**
     * Keeps textures in the database of a plugin known only by name, such as
     * the owner of a sequence.
     *
     * @param pluginName the plugin's name
     */
    public static void register(@Nullable String pluginName) {
        if (pluginName == null || LIBRARY.equals(pluginName) || STORES.containsKey(pluginName)
                || Bukkit.getServer() == null) {
            return;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin != null && plugin.isEnabled()) {
            register(plugin);
        }
    }

    /** Stops using a plugin's database, when it is disabled. */
    public static void release(String pluginName) {
        STORES.remove(pluginName);
    }

    /** Forgets every store and every texture, on shutdown. */
    public static void releaseAll() {
        STORES.clear();
        KNOWN.invalidateAll();
    }

    /** Whether any plugin keeps textures, which is what makes uploading worth it. */
    public static boolean stored() {
        return !STORES.isEmpty();
    }

    /**
     * The texture of a piece, if it is known and in memory.
     *
     * @param fingerprint the piece's hash
     * @return Mojang's texture id, or {@code null}
     */
    public static @Nullable String known(String fingerprint) {
        return KNOWN.getIfPresent(fingerprint);
    }

    /**
     * Looks up every piece with a design that is not in memory, in every store.
     *
     * <p>Never blocks: every lookup is the database module's own future, and
     * what is found is remembered as it arrives.
     *
     * @param cubes the pieces of one skin
     * @return the pieces nobody has a texture for yet
     */
    public static CompletableFuture<List<SkinCubes.Cube>> missing(List<SkinCubes.Cube> cubes) {
        List<SkinCubes.Cube> unknown = new ArrayList<>();
        for (SkinCubes.Cube cube : cubes) {
            if (cube.plain() == null && KNOWN.getIfPresent(cube.hash()) == null) {
                unknown.add(cube);
            }
        }
        if (unknown.isEmpty() || STORES.isEmpty()) {
            return CompletableFuture.completedFuture(unknown);
        }
        List<CompletableFuture<Void>> finds = new ArrayList<>();
        for (Repository<RagdollTextureRow> store : STORES.values()) {
            for (SkinCubes.Cube cube : unknown) {
                finds.add(store.find(cube.hash())
                        .thenAccept(row -> row.ifPresent(found -> KNOWN.put(found.fingerprint(), found.texture())))
                        .exceptionally(unreadable -> {
                            logger.log(Level.FINE, "Could not look up a ragdoll skin texture", unreadable);
                            return null;
                        }));
            }
        }
        return CompletableFuture.allOf(finds.toArray(CompletableFuture[]::new))
                .thenApply(done -> unknown.stream()
                        .filter(cube -> KNOWN.getIfPresent(cube.hash()) == null)
                        .toList());
    }

    /**
     * A texture MineSkin has just made: remembered, and written to every store.
     *
     * @param fingerprint the piece's hash
     * @param texture     Mojang's texture id
     */
    static void arrived(String fingerprint, String texture) {
        KNOWN.put(fingerprint, texture);
        for (Repository<RagdollTextureRow> store : STORES.values()) {
            store.save(new RagdollTextureRow(fingerprint, texture)).exceptionally(unwritable -> {
                // Still worn this run; only the next restart would upload it again.
                logger.log(Level.WARNING, "Could not keep a ragdoll skin texture in the database", unwritable);
                return null;
            });
        }
    }
}
