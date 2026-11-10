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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** A player's body in the round: each part's four sides, by skin url. */
    private static final Map<String, List<Face>> MODELS = new ConcurrentHashMap<>();
    private static final Cache<String, Picture> BODIES = Caffeine.newBuilder()
            .maximumSize(512).expireAfterAccess(Duration.ofMinutes(30)).build();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /** The skins themselves, by url, for dressing a body; and bodies dressed, by url and outfit. */
    private static final Cache<String, Skin> SKINS = Caffeine.newBuilder()
            .maximumSize(256).expireAfterAccess(Duration.ofMinutes(30)).build();
    private static final Cache<String, Dressed> DRESSED = Caffeine.newBuilder()
            .maximumSize(512).expireAfterAccess(Duration.ofMinutes(10)).build();
    private static final Set<String> DRESSING = ConcurrentHashMap.newKeySet();

    private record Skin(BufferedImage image, boolean slim) {
    }

    private record Dressed(List<Face> sides, Picture front) {
    }
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Pictures() {
    }

    /** Pictures already turned into glyphs, shared by every screen, weighed by their pixels. */
    private static final Cache<Glyphs, net.kyori.adventure.text.Component> GLYPHS = Caffeine.newBuilder()
            .maximumWeight(262_144).weigher((Glyphs key, net.kyori.adventure.text.Component value) -> key.argb.length + 1)
            .expireAfterAccess(Duration.ofMinutes(10)).build();

    /** A picture's pixels, compared by content: a node copies its pixels, so the same picture is a new array. */
    private static final class Glyphs {
        final int[] argb;
        final int width;
        final int behind;
        private final int hash;

        Glyphs(int[] argb, int width, int behind) {
            this.argb = argb;
            this.width = width;
            this.behind = behind;
            this.hash = (java.util.Arrays.hashCode(argb) * 31 + width) * 31 + behind;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Glyphs g && g.hash == hash && g.width == width && g.behind == behind
                    && java.util.Arrays.equals(g.argb, argb);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * A picture as lines of full blocks, each run of one colour one component. A pixel more than half see-through
     * takes the colour behind it, a partly see-through one is mixed over it: there is no see-through glyph. Safe
     * from any thread; the same pixels give the same component instance while it stays cached.
     */
    public static net.kyori.adventure.text.Component glyphs(int[] argb, int w, int h, int behind) {
        return GLYPHS.get(new Glyphs(argb, w, behind), key -> build(argb, w, h, behind));
    }

    private static net.kyori.adventure.text.Component build(int[] argb, int w, int h, int behind) {
        net.kyori.adventure.text.TextComponent.Builder out = net.kyori.adventure.text.Component.text();
        for (int y = 0; y < h; y++) {
            if (y > 0) out.append(net.kyori.adventure.text.Component.newline());
            int x = 0;
            while (x < w) {
                boolean clear = (argb[y * w + x] >>> 24) < 128;
                int colour = solid(argb[y * w + x], behind);
                int end = x + 1;
                while (end < w && ((argb[y * w + end] >>> 24) < 128) == clear
                        && (clear || solid(argb[y * w + end], behind) == colour)) end++;
                if (clear) {
                    // A clear pixel is a gap as wide as a block: a space (4) and a bold space (5).
                    out.append(net.kyori.adventure.text.Component.text(" ".repeat(end - x)));
                    out.append(net.kyori.adventure.text.Component.text(" ".repeat(end - x))
                            .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
                } else {
                    out.append(net.kyori.adventure.text.Component.text("█".repeat(end - x),
                            net.kyori.adventure.text.format.TextColor.color(colour & 0xFFFFFF)));
                }
                x = end;
            }
        }
        return out.build();
    }

    private static int solid(int argb, int behind) {
        int alpha = argb >>> 24;
        if (alpha == 255) return argb;
        if (alpha < 128) return behind;
        return ScreenMath.over(argb, behind);
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
                                SKINS.put(url, new Skin(skin, slim));
                                MODELS.put(url, sides(skin, slim));
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

    /**
     * One side of one part of a body: its picture as seen from outside, where its centre is (skin pixels, the
     * body's centre at 0, y up, z towards the viewer) and which way it faces (degrees about y, 0 towards the
     * viewer).
     */
    public record Face(Picture picture, double x, double y, double z, double yaw, double scale) {
        /** A side drawn one skin pixel a picture pixel. */
        public Face(Picture picture, double x, double y, double z, double yaw) {
            this(picture, x, y, z, yaw, 1);
        }
    }

    /** A player's body as sides to stand in the round, or {@code null} while their skin is on its way. */
    public static @Nullable List<Face> model(String player) {
        if (body(player) == null) return null;
        String texture = Skulls.player(player).build().texture();
        String url = texture == null ? null : Textures.urlOf(texture);
        return url == null ? null : MODELS.get(url);
    }

    /**
     * A player's body in the round wearing {@code outfit} ({@link Outfit} words), or {@code null} while their skin
     * is on its way. Until the outfit is dressed (its textures read on an async task) the bare body is answered.
     */
    public static @Nullable List<Face> model(String player, @Nullable String outfit) {
        if (outfit == null || outfit.isBlank()) return model(player);
        Dressed dressed = dressed(player, outfit);
        return dressed != null ? dressed.sides() : model(player);
    }

    /** As {@link #body(String)}, wearing {@code outfit}; the bare front until it is dressed. */
    public static @Nullable Picture body(String player, @Nullable String outfit) {
        if (outfit == null || outfit.isBlank()) return body(player);
        Dressed dressed = dressed(player, outfit);
        return dressed != null ? dressed.front() : body(player);
    }

    private static @Nullable Dressed dressed(String player, String outfit) {
        if (body(player) == null) return null;
        String texture = Skulls.player(player).build().texture();
        String url = texture == null ? null : Textures.urlOf(texture);
        Skin skin = url == null ? null : SKINS.getIfPresent(url);
        if (skin == null || !VanillaSprites.available()) return null;
        String key = url + "|" + outfit;
        Dressed known = DRESSED.getIfPresent(key);
        if (known != null) return known;
        if (DRESSING.add(key)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    List<Face> sides = sides(skin.image(), skin.slim(), Outfit.resolve(outfit));
                    DRESSED.put(key, new Dressed(sides, front(sides)));
                } finally {
                    DRESSING.remove(key);
                }
            });
        }
        return null;
    }

    static List<Face> sides(BufferedImage skin, boolean slim) {
        return sides(skin, slim, Outfit.NONE);
    }

    /**
     * A body's sides with an outfit laid over the skin: each armour piece's layer texture over the parts it
     * covers, in the order the game draws them (leggings, chestplate, boots, helmet), and the held item.
     */
    static List<Face> sides(BufferedImage skin, boolean slim, Outfit outfit) {
        int arm = slim ? 3 : 4;
        boolean modern = skin.getHeight() >= 64;
        // Each part: the skin's own box and its outer layer, then what is worn over it. A modern skin's left limbs
        // have their own place on it; an old one, and every armour texture, mirror the right ones.
        List<Layer> head = layers(skin, 0, 0, 32, 0, 8, false);
        List<Layer> body = layers(skin, 16, 16, modern ? 16 : -1, 32, 8, false);
        List<Layer> rightArm = layers(skin, 40, 16, modern ? 40 : -1, 32, arm, false);
        List<Layer> rightLeg = layers(skin, 0, 16, modern ? 0 : -1, 32, 4, false);
        List<Layer> leftArm = modern ? layers(skin, 32, 48, 48, 48, arm, false) : layers(skin, 40, 16, -1, 0, arm, true);
        List<Layer> leftLeg = modern ? layers(skin, 16, 48, 0, 48, 4, false) : layers(skin, 0, 16, -1, 0, 4, true);
        for (BufferedImage legs : outfit.legs()) {
            body.add(new Layer(legs, 16, 16, 8, false));
            rightLeg.add(new Layer(legs, 0, 16, 4, false));
            leftLeg.add(new Layer(legs, 0, 16, 4, true));
        }
        for (BufferedImage chest : outfit.chest()) {
            body.add(new Layer(chest, 16, 16, 8, false));
            rightArm.add(new Layer(chest, 40, 16, 4, false));
            leftArm.add(new Layer(chest, 40, 16, 4, true));
        }
        for (BufferedImage feet : outfit.feet()) {
            rightLeg.add(new Layer(feet, 0, 16, 4, false));
            leftLeg.add(new Layer(feet, 0, 16, 4, true));
        }
        for (BufferedImage helmet : outfit.head()) {
            head.add(new Layer(helmet, 0, 0, 8, false));
            head.add(new Layer(helmet, 32, 0, 8, false));
        }
        List<Face> out = new ArrayList<>();
        // Its size, where its centre stands, and what is drawn on it.
        part(out, 8, 8, 8, 0, 12, head);
        part(out, 8, 12, 4, 0, 2, body);
        part(out, arm, 12, 4, -(4 + arm / 2.0), 2, rightArm);
        part(out, 4, 12, 4, -2, -10, rightLeg);
        part(out, arm, 12, 4, 4 + arm / 2.0, 2, leftArm);
        part(out, 4, 12, 4, 2, -10, leftLeg);
        Picture held = outfit.hand();
        if (held != null && held.width() > 0) {
            // Upright in the right fist, side on as the game holds it: its handle (the sprite's bottom left) in
            // the hand, its tip forward and up. Ten skin pixels tall, as a held sword is.
            double scale = HELD / Math.max(held.width(), held.height());
            double half = HELD / 2 - 1.5;
            out.add(new Face(held, -(4 + arm / 2.0), -3 + half, half, -90, scale));
        }
        return List.copyOf(out);
    }

    /** How tall a held item stands, in skin pixels. */
    private static final double HELD = 10;

    /** A texture drawn on a box: where the box lies on it, its width there, and whether it is mirrored. */
    private record Layer(BufferedImage image, int u, int v, int w, boolean mirror) {
    }

    private static List<Layer> layers(BufferedImage skin, int u, int v, int ou, int ov, int w, boolean mirror) {
        List<Layer> out = new ArrayList<>();
        out.add(new Layer(skin, u, v, w, mirror));
        if (ou >= 0) out.add(new Layer(skin, ou, ov, w, mirror));
        return out;
    }

    /** A box's four sides, each layer laid over the one before. */
    private static void part(List<Face> out, int w, int h, int d, double x, double y, List<Layer> layers) {
        double[][] place = {{0, d / 2.0, 0}, {-w / 2.0, 0, -90}, {0, -d / 2.0, 180}, {w / 2.0, 0, 90}};
        for (int side = 0; side < 4; side++) {
            int fw = side % 2 == 0 ? w : d;
            int[] argb = new int[fw * h];
            for (Layer layer : layers) {
                // Front, the wearer's right (seen on the viewer's left), back, the wearer's left: where each lies on
                // the texture. A mirrored limb swaps its outer sides and reads each picture backwards.
                int lw = layer.w();
                int[][] at = {{layer.u() + d, layer.v() + d}, {layer.u(), layer.v() + d},
                        {layer.u() + d + lw + d, layer.v() + d}, {layer.u() + d + lw, layer.v() + d}};
                int src = layer.mirror() && (side == 1 || side == 3) ? 4 - side : side;
                // A slim arm in classic armour takes the armour's first columns.
                region(layer.image(), argb, at[src][0], at[src][1], fw, h, layer.mirror(), src % 2 == 0 ? lw : d);
            }
            out.add(new Face(new Picture(fw, h, argb), x + place[side][0], y, place[side][1], place[side][2]));
        }
    }

    private static void region(BufferedImage skin, int[] out, int sx, int sy, int w, int h, boolean mirror, int srcW) {
        int cols = Math.min(w, srcW);
        if (sx < 0 || sy < 0 || sx + cols > skin.getWidth() || sy + h > skin.getHeight()) return;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < cols; x++) {
                int argb = skin.getRGB(sx + (mirror ? srcW - 1 - x : x), sy + y);
                if ((argb >>> 24) >= 128) out[y * w + x] = argb | 0xFF000000;
            }
        }
    }

    /** The front of a body in the round: each part's front side where it stands, 16 by 32. */
    static Picture front(List<Face> sides) {
        int[] out = new int[16 * 32];
        for (Face face : sides) {
            if (face.yaw() != 0 || face.scale() != 1) continue;
            Picture p = face.picture();
            int left = (int) Math.round(8 + face.x() - p.width() / 2.0);
            int top = (int) Math.round(16 - face.y() - p.height() / 2.0);
            for (int y = 0; y < p.height(); y++) {
                for (int x = 0; x < p.width(); x++) {
                    int argb = p.argb()[y * p.width() + x];
                    int tx = left + x, ty = top + y;
                    if ((argb >>> 24) >= 128 && tx >= 0 && tx < 16 && ty >= 0 && ty < 32) out[ty * 16 + tx] = argb;
                }
            }
        }
        return new Picture(16, 32, out);
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
        return front(sides(skin, slim));
    }

}
