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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a player's skin into the colours a body is drawn in, once.
 *
 * <h2>Why this has to be ready before the kill</h2>
 * A skin is a PNG on Mojang's texture server, and reading one is a network
 * round trip. An effect that waits for it plays after the fight has moved on,
 * so nothing here is ever waited for: a skin that is not in memory yet is
 * fetched in the background and the body is drawn from the fallback until it
 * arrives. Every skin is therefore warmed when its owner joins, which is a
 * quiet request minutes before anybody dies in it.
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

    /** Wires the cache to a scheduler. Called by ExyliaLib at startup. */
    public static void init(Plugin plugin) {
        scheduler = Tasks.of(plugin);
        logger = plugin.getLogger();
    }

    /** Forgets every decoded skin, on shutdown or reload. */
    public static void clear() {
        BY_TEXTURE.clear();
        IN_FLIGHT.clear();
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
     * The colours of a player's skin, or the fallback while it is being read.
     *
     * <p>Never blocks and never returns {@code null}: a body drawn in the wrong
     * colours is a worse effect, a body that does not appear is a bug.
     */
    public static RagdollSkin of(Player player) {
        RagdollSkin skin = skinOf(player);
        return skin == null ? FALLBACK : skin;
    }

    private static RagdollSkin skinOf(Player player) {
        String url = textureUrl(player);
        if (url == null) {
            return null;
        }
        RagdollSkin cached = BY_TEXTURE.get(url);
        if (cached != null) {
            return cached;
        }
        fetch(url);
        return null;
    }

    /** The skin URL carried in a connected player's profile. */
    private static String textureUrl(Player player) {
        try {
            for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName())) {
                    return Textures.urlOf(property.getValue());
                }
            }
        } catch (Throwable noProfileApi) {
            // Spigot, an offline-mode player with no texture, or a profile the
            // server has not filled in. All three mean the same thing here.
            return null;
        }
        return null;
    }

    private static void fetch(String url) {
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
                        BY_TEXTURE.put(url, read(image));
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

    /** Cuts a skin picture into the front face of every part. */
    private static RagdollSkin read(BufferedImage image) {
        boolean legacy = image.getHeight() < 64;
        Map<RagdollPart, int[]> faces = new EnumMap<>(RagdollPart.class);
        for (RagdollPart part : RagdollPart.values()) {
            RagdollPart source = legacy ? part.legacy() : part;
            int width = source.skinWidth();
            int height = source.skinHeight();
            if (source.skinX() + width > image.getWidth()
                    || source.skinY() + height > image.getHeight()) {
                continue;
            }
            int[] pixels = new int[width * height];
            image.getRGB(source.skinX(), source.skinY(), width, height, pixels, 0, width);
            faces.put(part, pixels);
        }
        return new RagdollSkin(faces);
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

    private static int[] flat(int rgb, RagdollPart part) {
        int[] pixels = new int[part.skinWidth() * part.skinHeight()];
        java.util.Arrays.fill(pixels, 0xFF000000 | rgb);
        return pixels;
    }
}
