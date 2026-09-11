package net.exylia.lib.ragdoll.internal;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollSkin;
import net.exylia.lib.skull.internal.Textures;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a player's skin into what a body is drawn with, once: its colours at
 * once, and its real pixels as soon as MineSkin has made them.
 *
 * <h2>Why this has to be ready before the kill</h2>
 * A skin is a PNG on Mojang's texture server, and reading one is a network
 * round trip. An effect that waits for it plays after the fight has moved on,
 * so nothing here is ever waited for: a skin that is not in memory yet is
 * fetched in the background and the body is drawn from the fallback until it
 * arrives. Every skin is therefore warmed when its owner joins, which is a
 * quiet request minutes before anybody dies in it.
 *
 * <p>The same goes for the cubes that draw a body in its real skin: they are
 * cut the moment the picture is read, queued with {@link MineSkinQueue}, and
 * the skin in this cache is swapped for one carrying them when the last one
 * arrives. Every death before that is a death in blocks, never a wait.
 *
 * <p>Keyed by texture URL rather than by player, so a hundred players wearing
 * the same skin decode one picture, and a player who changes skin is a new key
 * rather than a stale one.
 */
@ApiStatus.Internal
public final class SkinCache {

    /** Long enough for a slow CDN, short enough that nothing piles up. */
    private static final Duration TIMEOUT = Duration.ofSeconds(6);

    private static final Map<String, RagdollSkin> BY_TEXTURE = new ConcurrentHashMap<>();

    /** Textures being fetched, so twenty deaths do not become twenty requests. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** Skins cut into cubes that are still waiting for some of their textures, by URL. */
    private static final Map<String, List<SkinCubes.Cube>> WAITING = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    /** What a body is drawn in until its real skin has been read. */
    private static final RagdollSkin FALLBACK = defaultSkin();

    private static volatile TaskScheduler scheduler;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private SkinCache() {
    }

    /** Wires the cache to a scheduler and starts the cube uploads. Called by ExyliaLib at startup. */
    public static void init(Plugin plugin) {
        scheduler = Tasks.of(plugin);
        logger = plugin.getLogger();
        MineSkinQueue.start(plugin, SkinCache::resolve);
    }

    /**
     * Forgets every decoded skin, on shutdown.
     *
     * <p>The textures already made stay in their file: they are permanent, and
     * forgetting them would only mean uploading them again.
     */
    public static void clear() {
        MineSkinQueue.stop();
        BY_TEXTURE.clear();
        IN_FLIGHT.clear();
        WAITING.clear();
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
        RagdollSkin cached = BY_TEXTURE.get(url);
        if (cached == null) {
            fetch(url, Textures.slim(property));
            return null;
        }
        if (!cached.skinned() && MineSkinQueue.enabled()) {
            List<SkinCubes.Cube> waiting = WAITING.get(url);
            if (waiting != null) {
                // Asks again for whatever a stopped worker or a failed upload
                // left behind; everything already known or queued is skipped.
                MineSkinQueue.submit(waiting);
            } else {
                // Read before a key was set: read again, to be cut this time.
                fetch(url, Textures.slim(property));
            }
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
                        BY_TEXTURE.putIfAbsent(url, read(image, slim));
                        if (MineSkinQueue.enabled()) {
                            List<SkinCubes.Cube> cubes = SkinCubes.cut(image, slim);
                            WAITING.put(url, cubes);
                            MineSkinQueue.submit(cubes);
                            resolve();
                        }
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
     * Hands every waiting skin whose cubes are all known the textures to wear.
     *
     * <p>Run whenever a texture arrives. A handful of skins wait at a time, so
     * looking through all of them is cheaper than keeping an index.
     */
    private static synchronized void resolve() {
        for (Map.Entry<String, List<SkinCubes.Cube>> waiting : WAITING.entrySet()) {
            Map<RagdollPart, String[]> textures = new EnumMap<>(RagdollPart.class);
            boolean complete = true;
            for (SkinCubes.Cube cube : waiting.getValue()) {
                String texture = MineSkinQueue.known(cube.hash());
                if (texture == null) {
                    complete = false;
                    break;
                }
                int columns = cube.part().columns(SkinCubes.DETAIL);
                textures.computeIfAbsent(cube.part(),
                        part -> new String[columns * part.rows(SkinCubes.DETAIL)])
                        [cube.cellY() * columns + cube.cellX()] = texture;
            }
            if (complete) {
                BY_TEXTURE.computeIfPresent(waiting.getKey(), (url, skin) -> skin.withCubes(textures));
                WAITING.remove(waiting.getKey());
            }
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
        return new RagdollSkin(nets);
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
