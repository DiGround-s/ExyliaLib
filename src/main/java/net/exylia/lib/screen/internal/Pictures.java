package net.exylia.lib.screen.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.screen.Node;
import net.exylia.lib.skull.Skulls;
import net.exylia.lib.skull.internal.Textures;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pictures a screen's {@code <img>} draws: PNG files next to the markup, and players' bodies from their skins.
 * Both are read once and kept; a skin still downloading is {@code null} and the screen's next second draws it.
 */
public final class Pictures {

    /** A decoded picture: what {@link Node#image(int, int, int[])} takes. */
    public record Picture(int width, int height, int[] argb) {
        public Node node() {
            return Node.image(width, height, argb);
        }
    }

    /** Files are read again after a while, so an owner's edit shows without a restart. */
    private static final Cache<Path, Picture> FILES = Caffeine.newBuilder()
            .maximumSize(256).expireAfterWrite(Duration.ofSeconds(30)).build();
    private static final Cache<String, Picture> BODIES = Caffeine.newBuilder()
            .maximumSize(512).expireAfterAccess(Duration.ofMinutes(30)).build();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Pictures() {
    }

    /**
     * A PNG under {@code dir}. Never reaches outside it, whatever the path says.
     *
     * @throws IOException when it is missing, outside the folder, not a picture or too big
     */
    public static Picture file(Path dir, String path) throws IOException {
        Path base = dir.toAbsolutePath().normalize();
        Path file = base.resolve(path).normalize();
        if (!file.startsWith(base)) throw new IOException(path + " is outside " + base.getFileName());
        Picture known = FILES.getIfPresent(file);
        if (known != null) return known;
        if (!Files.isRegularFile(file)) throw new IOException(path + " does not exist");
        BufferedImage image;
        try (InputStream in = Files.newInputStream(file)) {
            image = ImageIO.read(in);
        }
        if (image == null) throw new IOException(path + " is not a PNG");
        if (image.getWidth() > Node.MAX_IMAGE || image.getHeight() > Node.MAX_IMAGE) {
            throw new IOException(path + " is " + image.getWidth() + "x" + image.getHeight() + ", the most is "
                    + Node.MAX_IMAGE + " a side");
        }
        Picture picture = of(image);
        FILES.put(file, picture);
        return picture;
    }

    /** A player's body seen from the front, 16 by 32, or {@code null} while their skin is on its way. */
    public static @Nullable Picture body(String player) {
        String texture = Skulls.player(player).build().texture();
        String url = texture == null ? null : Textures.urlOf(texture);
        if (url == null) return null;
        Picture known = BODIES.getIfPresent(url);
        if (known != null) return known;
        if (IN_FLIGHT.add(url)) {
            boolean slim = Textures.slim(texture);
            HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).build(),
                            HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        try (InputStream in = response.body()) {
                            BufferedImage skin = response.statusCode() == 200 ? ImageIO.read(in) : null;
                            if (skin != null && skin.getWidth() == 64 && skin.getHeight() >= 32) {
                                BODIES.put(url, front(skin, slim));
                            }
                        } catch (IOException ignored) {
                            // Drawn as an empty box; the next look asks again.
                        }
                    })
                    .whenComplete((done, failure) -> IN_FLIGHT.remove(url));
        }
        return null;
    }

    /** A player's face with its outer layer, 8 by 8, or {@code null} while their skin is on its way. */
    public static @Nullable Picture face(String player) {
        Picture body = body(player);
        if (body == null) return null;
        int[] out = new int[64];
        for (int y = 0; y < 8; y++) System.arraycopy(body.argb(), y * 16 + 4, out, y * 8, 8);
        return new Picture(8, 8, out);
    }

    /**
     * A head and shoulders in quiet greys, for a player whose skin is not known (yet, or ever: a name no account
     * has). Drawn at the size the real picture would be, so nothing moves when the skin lands.
     */
    public static Picture stranger(boolean body) {
        String[] mask = body ? STRANGER_BODY : STRANGER_FACE;
        int w = mask[0].length();
        int[] out = new int[w * mask.length];
        for (int y = 0; y < mask.length; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = switch (mask[y].charAt(x)) {
                    case 'H' -> 0xFF3A3248;
                    case 'S' -> 0xFF4A4060;
                    case 'E' -> 0xFF1C1726;
                    default -> 0;
                };
            }
        }
        return new Picture(w, mask.length, out);
    }

    private static final String[] STRANGER_FACE = {
            "HHHHHHHH", "HSSSSSSH", "SSSSSSSS", "SEESSEES", "SSSSSSSS", "SSSSSSSS", "SSSEESSS", "SSSSSSSS"};
    private static final String[] STRANGER_BODY;

    static {
        String[] body = new String[32];
        for (int y = 0; y < 8; y++) body[y] = "...." + STRANGER_FACE[y] + "....";
        for (int y = 8; y < 20; y++) body[y] = "HHHHHHHHHHHHHHHH";
        for (int y = 20; y < 32; y++) body[y] = "....HHHHHHHH....";
        STRANGER_BODY = body;
    }

    static Picture of(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return new Picture(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    }

    /**
     * The front of a skin, as the player stands facing you: the wearer's right arm on the left. Each part's outer
     * layer is laid over it; an old 64 by 32 skin mirrors its right limbs for the left ones.
     */
    static Picture front(BufferedImage skin, boolean slim) {
        int arm = slim ? 3 : 4;
        int[] out = new int[16 * 32];
        boolean modern = skin.getHeight() >= 64;
        // Head, then body, then the limbs: x, y on the skin, size, where it goes, and whether to mirror it.
        copy(skin, out, 8, 8, 8, 8, 4, 0, false);
        copy(skin, out, 40, 8, 8, 8, 4, 0, false);
        copy(skin, out, 20, 20, 8, 12, 4, 8, false);
        if (modern) copy(skin, out, 20, 36, 8, 12, 4, 8, false);
        // Right arm and right leg (on the left of the picture).
        copy(skin, out, 44, 20, arm, 12, 4 - arm, 8, false);
        if (modern) copy(skin, out, 44, 36, arm, 12, 4 - arm, 8, false);
        copy(skin, out, 4, 20, 4, 12, 4, 20, false);
        if (modern) copy(skin, out, 4, 36, 4, 12, 4, 20, false);
        // Left arm and left leg.
        if (modern) {
            copy(skin, out, 36, 52, arm, 12, 12, 8, false);
            copy(skin, out, 52, 52, arm, 12, 12, 8, false);
            copy(skin, out, 20, 52, 4, 12, 8, 20, false);
            copy(skin, out, 4, 52, 4, 12, 8, 20, false);
        } else {
            copy(skin, out, 44, 20, arm, 12, 12, 8, true);
            copy(skin, out, 4, 20, 4, 12, 8, 20, true);
        }
        return new Picture(16, 32, out);
    }

    /** Lays a skin region over {@code out}; see-through pixels of an outer layer leave what is under them. */
    private static void copy(BufferedImage skin, int[] out, int sx, int sy, int w, int h, int dx, int dy, boolean mirror) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = skin.getRGB(sx + (mirror ? w - 1 - x : x), sy + y);
                if ((argb >>> 24) < 128) continue;
                int tx = dx + x;
                int ty = dy + y;
                if (tx >= 0 && tx < 16 && ty >= 0 && ty < 32) out[ty * 16 + tx] = argb | 0xFF000000;
            }
        }
    }
}
