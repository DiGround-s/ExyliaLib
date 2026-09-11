package net.exylia.lib.ragdoll.internal;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollSkin;
import net.exylia.lib.skull.internal.Textures;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a player's skin into what a body is drawn with, once: its colours at
 * once, and its real pixels as soon as they have textures.
 *
 * <h2>Why this has to be ready before the kill</h2>
 * A skin is a PNG on Mojang's texture server, and reading one is a network
 * round trip. An effect that waits for it plays after the fight has moved on,
 * so nothing here is ever waited for: a skin that is not in memory yet is
 * fetched in the background and the body is drawn from the fallback until it
 * arrives. Every skin is therefore warmed when its owner joins, which is a
 * quiet request minutes before anybody dies in it.
 *
 * <p>The same goes for the pieces that draw a body in its real skin: the
 * moment the picture is read they are cut at the configured quality, looked up
 * in the databases that keep them, and whatever nobody has made yet is queued
 * with {@link MineSkinQueue}. Every death before a piece arrives draws that
 * piece in blocks, never a wait.
 *
 * <p>Keyed by texture URL rather than by player, so a hundred players wearing
 * the same skin decode one picture, and a player who changes skin is a new key
 * rather than a stale one. Bounded, because a server sees far more skins over
 * a day than it has players at once.
 */
@ApiStatus.Internal
public final class SkinCache {

    /** Long enough for a slow CDN, short enough that nothing piles up. */
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private static final Cache<String, RagdollSkin> BY_TEXTURE = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /** Textures being fetched, so twenty deaths do not become twenty requests. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * The generation each skin was last prepared in, by URL.
     *
     * <p>Expires on its own, so a skin whose pieces are still on their way is
     * looked up again every few minutes rather than at every death.
     */
    private static final Cache<String, Integer> PREPARED = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /** Bumped whenever what a skin is prepared against changes: the key, the quality, a new store. */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    /** What a body is drawn in until its real skin has been read. */
    private static final RagdollSkin FALLBACK = defaultSkin();

    private static volatile SkinCubes.Quality quality = SkinCubes.Quality.NORMAL;
    private static volatile boolean unknownQualityReported;
    private static volatile TaskScheduler scheduler;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private SkinCache() {
    }

    /** Wires the cache to a scheduler and starts the uploads. Called by ExyliaLib at startup. */
    public static void init(Plugin plugin) {
        scheduler = Tasks.of(plugin);
        logger = plugin.getLogger();
        RagdollTextures.logger(plugin.getLogger());
        MineSkinQueue.start(plugin);
    }

    /**
     * Forgets every decoded skin, on shutdown.
     *
     * <p>The textures already made stay in the databases that keep them: they
     * are permanent, and forgetting them would only mean uploading them again.
     */
    public static void clear() {
        MineSkinQueue.stop();
        BY_TEXTURE.invalidateAll();
        PREPARED.invalidateAll();
        IN_FLIGHT.clear();
    }

    /**
     * Sets how finely a skin is cut, from the library's config.
     *
     * <p>A value that is none of the three draws at {@code normal} and is said
     * once, the same as any other unreadable config value.
     *
     * @param value {@code high}, {@code normal} or {@code low}
     * @return whether the quality in use changed
     */
    public static boolean quality(@Nullable String value) {
        SkinCubes.Quality read = SkinCubes.Quality.of(value);
        if (read == null) {
            read = SkinCubes.Quality.NORMAL;
            if (!unknownQualityReported) {
                unknownQualityReported = true;
                logger.warning("Ragdolls: ragdoll-skin-quality in config.yml is not high, normal or low;"
                        + " using normal.");
            }
        } else {
            unknownQualityReported = false;
        }
        if (read == quality) {
            return false;
        }
        quality = read;
        return true;
    }

    /** How finely skins are cut now. */
    public static SkinCubes.Quality quality() {
        return quality;
    }

    /** Marks every skin as needing to be prepared again the next time it is needed. */
    static void stale() {
        GENERATION.incrementAndGet();
    }

    /**
     * Prepares the skin of every player online again, after the key or the
     * quality changed, rather than at each of their next deaths.
     */
    public static void rewarm() {
        stale();
        if (Bukkit.getServer() == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            warm(player);
        }
    }

    /**
     * Reads a player's skin into memory if it is not there already.
     *
     * <p>Returns at once either way. Called when a player joins.
     */
    public static void warm(Player player) {
        skinOf(player);
    }

    /**
     * What a player's body is drawn with, or the fallback while it is being read.
     *
     * <p>Never blocks and never returns {@code null}: a body drawn in the wrong
     * colours is a worse effect, a body that does not appear is a bug.
     */
    public static RagdollSkin of(Player player) {
        RagdollSkin skin = skinOf(player);
        return skin == null ? FALLBACK : skin;
    }

    private static RagdollSkin skinOf(Player player) {
        String property = texturesOf(player);
        String url = property == null ? null : Textures.urlOf(property);
        if (url == null) {
            return null;
        }
        RagdollSkin cached = BY_TEXTURE.getIfPresent(url);
        if (cached == null) {
            fetch(url, Textures.slim(property));
            return null;
        }
        Integer prepared = PREPARED.getIfPresent(url);
        TaskScheduler tasks = scheduler;
        if (tasks != null && (prepared == null || prepared != GENERATION.get())) {
            PREPARED.put(url, GENERATION.get());
            tasks.runAsync(() -> prepare(cached));
        }
        return cached;
    }

    /** The texture property carried in a connected player's profile. */
    private static String texturesOf(Player player) {
        try {
            for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName())) {
                    return property.getValue();
                }
            }
        } catch (Throwable noProfileApi) {
            // Spigot, an offline-mode player with no texture, or a profile the
            // server has not filled in. All three mean the same thing here.
            return null;
        }
        return null;
    }

    private static void fetch(String url, boolean slim) {
        TaskScheduler tasks = scheduler;
        if (tasks == null || !IN_FLIGHT.add(url)) {
            return;
        }
        tasks.runAsync(() -> {
            try {
                HttpResponse<byte[]> response = HTTP.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(TIMEOUT)
                                .header("User-Agent", "ExyliaLib")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
                    if (image != null) {
                        RagdollSkin skin = read(image, slim);
                        BY_TEXTURE.put(url, skin);
                        PREPARED.put(url, GENERATION.get());
                        prepare(skin);
                    }
                }
            } catch (Exception unreachable) {
                // One line, not a stack trace: a texture server that is down
                // costs an effect its colours and nothing else.
                logger.log(Level.FINE, "Could not read a skin for a ragdoll effect", unreachable);
            } finally {
                IN_FLIGHT.remove(url);
            }
        });
    }

    /**
     * Cuts a skin at the quality in use, looks its pieces up, and queues
     * whatever nobody has made yet. Off the main thread.
     *
     * <p>Nothing happens while no plugin keeps textures: an upload with nowhere
     * to be kept would have to be made again at the next restart.
     */
    private static void prepare(RagdollSkin skin) {
        if (!RagdollTextures.stored()) {
            return;
        }
        try {
            List<SkinCubes.Cube> cubes = skin.cubes(quality);
            if (cubes != null) {
                RagdollTextures.missing(cubes).thenAccept(MineSkinQueue::submit);
            }
        } catch (RuntimeException uncut) {
            logger.log(Level.FINE, "Could not prepare a skin for a ragdoll effect", uncut);
        }
    }

    /**
     * The default skin's colours, for a body that belongs to nobody in
     * particular: a spectator with no player to wear.
     */
    public static RagdollSkin fallback() {
        return defaultSkin();
    }

    /** Unfolds every part of a skin picture, both layers, into the nets a body is drawn from. */
    private static RagdollSkin read(BufferedImage image, boolean slim) {
        Map<RagdollPart, int[]> nets = new EnumMap<>(RagdollPart.class);
        for (RagdollPart part : RagdollPart.values()) {
            nets.put(part, SkinCubes.net(image, part, slim));
        }
        return new RagdollSkin(nets, image, slim);
    }

    /**
     * The default skin's colours, written down rather than downloaded.
     *
     * <p>What a body is drawn in on the first death after a restart, before
     * anybody's real skin has arrived, and on a server with no internet at all.
     * Nothing here is ever the wrong shape &mdash; only the wrong colour.
     */
    private static RagdollSkin defaultSkin() {
        Map<RagdollPart, int[]> faces = new EnumMap<>(RagdollPart.class);
        faces.put(RagdollPart.HEAD, flat(0xB8845A, RagdollPart.HEAD));
        faces.put(RagdollPart.TORSO, flat(0x00AAAA, RagdollPart.TORSO));
        faces.put(RagdollPart.ARM_RIGHT, flat(0xB8845A, RagdollPart.ARM_RIGHT));
        faces.put(RagdollPart.ARM_LEFT, flat(0xB8845A, RagdollPart.ARM_LEFT));
        faces.put(RagdollPart.LEG_RIGHT, flat(0x3A3A9E, RagdollPart.LEG_RIGHT));
        faces.put(RagdollPart.LEG_LEFT, flat(0x3A3A9E, RagdollPart.LEG_LEFT));
        return new RagdollSkin(faces);
    }

    /** A part's whole net in one colour. */
    private static int[] flat(int rgb, RagdollPart part) {
        int width = Math.round(part.blockWidth() / RagdollPart.PIXEL);
        int height = Math.round(part.blockHeight() / RagdollPart.PIXEL);
        int depth = Math.round(part.blockDepth() / RagdollPart.PIXEL);
        int[] pixels = new int[2 * (depth + width) * (depth + height)];
        java.util.Arrays.fill(pixels, 0xFF000000 | rgb);
        return pixels;
    }
}
