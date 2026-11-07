package net.exylia.lib.screen.internal;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.plugin.Plugin;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Items on a screen drawn as their own inventory sprite, in pixels, instead of as a 3D item.
 *
 * <p>An item display is shaded by the world's two fixed lights, and a screen turns with the viewer's head: the
 * item would brighten and darken as they look around. A sprite is text, which no light touches. The sprites are
 * the game's own, read once from the client jar Mojang serves (as a launcher does) into the library's folder;
 * until they are there, and for an item with no flat sprite (a shield, a chest), the 3D item is drawn.
 */
public final class VanillaSprites {

    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final Map<String, Pictures.Picture> SPRITES = new ConcurrentHashMap<>();
    private static final Pictures.Picture NONE = new Pictures.Picture(0, 0, new int[0]);
    /** What {@link #of(ItemStack, TaskScheduler, Runnable)} answers while the sprite is read: draw nothing yet. */
    static final Pictures.Picture LOADING = new Pictures.Picture(0, 0, new int[0]);
    private static final java.util.Set<String> READING = ConcurrentHashMap.newKeySet();
    /** Counts what arrived (the folder, a sprite), so a screen whose tree did not change still draws it. */
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile Path folder;

    private VanillaSprites() {
    }

    /** The sprite an item is drawn with, or {@code null} to draw the item itself. */
    public static @Nullable Pictures.Picture of(ItemStack item) {
        Path dir = ready();
        if (dir == null || item == null) return null;
        Material type = item.getType();
        if (type == Material.PLAYER_HEAD || type.isAir()) return null;
        int tint = tint(item);
        String key = type.getKey().getKey() + "#" + tint;
        Pictures.Picture known = SPRITES.computeIfAbsent(key, k -> load(dir, type.getKey().getKey(), tint));
        return known == NONE ? null : known;
    }

    /**
     * As {@link #of(ItemStack)}, without reading the disk on the calling thread: a sprite not read yet is read on
     * an async task, {@link #LOADING} is answered meanwhile, and {@code loaded} runs once it is there.
     */
    static @Nullable Pictures.Picture of(ItemStack item, TaskScheduler tasks, Runnable loaded) {
        Path dir = ready();
        if (dir == null || item == null) return null;
        Material type = item.getType();
        if (type == Material.PLAYER_HEAD || type.isAir()) return null;
        int tint = tint(item);
        String key = type.getKey().getKey() + "#" + tint;
        Pictures.Picture known = SPRITES.get(key);
        if (known != null) return known == NONE ? null : known;
        if (READING.add(key)) {
            tasks.runAsync(() -> {
                try {
                    SPRITES.computeIfAbsent(key, k -> load(dir, type.getKey().getKey(), tint));
                } finally {
                    READING.remove(key);
                    GENERATION.incrementAndGet();
                }
                loaded.run();
            });
        }
        return LOADING;
    }

    /** Changes whenever a sprite or the sprite folder arrives. */
    static int generation() {
        return GENERATION.get();
    }

    private static int tint(ItemStack item) {
        if (item.getItemMeta() instanceof LeatherArmorMeta leather) return leather.getColor().asRGB();
        if (item.getItemMeta() instanceof PotionMeta potion) {
            if (potion.getColor() != null) return potion.getColor().asRGB();
            var type = potion.getBasePotionType();
            if (type != null && !type.getPotionEffects().isEmpty()) {
                return type.getPotionEffects().get(0).getType().getColor().asRGB();
            }
            return 0x385DC6;
        }
        String name = item.getType().getKey().getKey();
        if (name.endsWith("leaves") && !name.startsWith("cherry") && !name.contains("azalea")) return 0x48B518;
        if (name.equals("vine") || name.equals("lily_pad") || name.equals("fern") || name.equals("short_grass")) return 0x48B518;
        return -1;
    }

    private static Pictures.Picture load(Path dir, String name, int tint) {
        try {
            BufferedImage base = first(dir, "item/" + name, "block/" + name, "block/" + name + "_side",
                    "block/" + name + "_front", "block/" + name + "_top");
            if (base == null) return NONE;
            int[] argb = pixels(base);
            BufferedImage overlay = read(dir, "item/" + name + "_overlay");
            if (tint >= 0) {
                if (overlay != null) {
                    // Leather and potions: the base is the tinted part, the overlay is laid over untinted, or
                    // (potions) the overlay is the tinted liquid over the bottle.
                    boolean liquid = name.contains("potion") || name.equals("tipped_arrow");
                    int[] over = pixels(overlay);
                    if (liquid) {
                        tintAll(over, tint);
                        lay(argb, over);
                    } else {
                        tintAll(argb, tint);
                        lay(argb, over);
                    }
                } else {
                    tintAll(argb, tint);
                }
            } else if (overlay != null) {
                lay(argb, pixels(overlay));
            }
            int side = Math.min(base.getWidth(), base.getHeight());
            return new Pictures.Picture(side, side, argb);
        } catch (IOException | RuntimeException unreadable) {
            return NONE;
        }
    }

    /** The first square frame: animated sprites are frames stacked downwards. */
    private static int[] pixels(BufferedImage image) {
        int side = Math.min(image.getWidth(), image.getHeight());
        return image.getRGB(0, 0, side, side, null, 0, side);
    }

    private static void tintAll(int[] argb, int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            argb[i] = (p & 0xFF000000) | ((p >> 16 & 0xFF) * r / 255) << 16 | ((p >> 8 & 0xFF) * g / 255) << 8
                    | (p & 0xFF) * b / 255;
        }
    }

    private static void lay(int[] under, int[] over) {
        for (int i = 0; i < Math.min(under.length, over.length); i++) {
            if ((over[i] >>> 24) >= 128) under[i] = over[i];
        }
    }

    private static @Nullable BufferedImage first(Path dir, String... names) throws IOException {
        for (String name : names) {
            BufferedImage image = read(dir, name);
            if (image != null) return image;
        }
        return null;
    }

    private static @Nullable BufferedImage read(Path dir, String name) throws IOException {
        Path file = dir.resolve(name + ".png");
        if (!Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            return ImageIO.read(in);
        }
    }

    /** The sprite folder once it is complete; the first call starts fetching it in the background. */
    private static @Nullable Path ready() {
        Path dir = folder;
        if (dir != null) return dir;
        if (STARTED.compareAndSet(false, true)) {
            Plugin library = Bukkit.getPluginManager().getPlugin("ExyliaLib");
            if (library == null) return null;
            String version = Bukkit.getMinecraftVersion();
            Path target = library.getDataFolder().toPath().resolve("cache/sprites-" + version);
            if (Files.isRegularFile(target.resolve(".complete"))) {
                folder = target;
                return target;
            }
            Thread fetcher = new Thread(() -> {
                try {
                    fetch(version, target);
                    folder = target;
                    GENERATION.incrementAndGet();
                } catch (IOException | InterruptedException | RuntimeException failure) {
                    library.getLogger().log(Level.INFO, "Screens: could not fetch the item sprites (" + failure
                            + "); items are drawn as 3D items.");
                }
            }, "ExyliaLib sprites");
            fetcher.setDaemon(true);
            fetcher.start();
        }
        return null;
    }

    /** Reads the item and block textures out of the version's client jar, streamed, never stored whole. */
    private static void fetch(String version, Path target) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10)).build();
        String manifest = get(http, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        String versionUrl = null;
        Matcher entry = Pattern.compile("\\{\"id\":\\s*\"" + Pattern.quote(version) + "\"[^}]*?\"url\":\\s*\"([^\"]+)\"")
                .matcher(manifest);
        if (entry.find()) versionUrl = entry.group(1);
        if (versionUrl == null) throw new IOException("no version " + version + " in the manifest");
        String meta = get(http, versionUrl);
        int client = meta.indexOf("\"client\"");
        Matcher jar = URL.matcher(meta);
        if (client < 0 || !jar.find(client)) throw new IOException("no client jar for " + version);
        Path staging = target.resolveSibling(target.getFileName() + ".part");
        Files.createDirectories(staging);
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(jar.group(1)))
                .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("client jar answered " + response.statusCode());
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(response.body())) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                for (String kind : new String[]{"item", "block"}) {
                    String prefix = "assets/minecraft/textures/" + kind + "/";
                    if (name.startsWith(prefix) && name.endsWith(".png") && name.indexOf('/', prefix.length()) < 0) {
                        Path out = staging.resolve(kind).resolve(name.substring(prefix.length()).toLowerCase(Locale.ROOT));
                        Files.createDirectories(out.getParent());
                        Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                        count++;
                    }
                }
            }
        }
        if (count < 100) throw new IOException("only " + count + " textures in the client jar");
        Files.writeString(staging.resolve(".complete"), version);
        if (Files.exists(target)) {
            try (var old = Files.walk(target)) {
                old.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(url + " answered " + response.statusCode());
        return response.body();
    }
}
