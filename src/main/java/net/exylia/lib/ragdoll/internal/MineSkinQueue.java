package net.exylia.lib.ragdoll.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the cubes a skin is cut into into textures Mojang hosts, through
 * MineSkin, one at a time and once ever.
 *
 * <h2>Why a third party</h2>
 * A client only draws a head texture that lives on Mojang's texture server,
 * and the only way a picture gets there is by a Minecraft account wearing it.
 * MineSkin keeps a pool of accounts doing exactly that and hands back the
 * signed property. Without a key nothing here runs and bodies stay in blocks.
 *
 * <h2>Why it never has to happen twice</h2>
 * A texture MineSkin produced is permanent, and a cube is keyed by the hash of
 * its pixels. Every answer is appended to a file, so a skin is uploaded the
 * first time anybody wearing it joins and never again &mdash; not after a
 * restart, and not for the next player whose sleeves happen to be the same.
 */
@ApiStatus.Internal
public final class MineSkinQueue {

    private static final URI QUEUE = URI.create("https://api.mineskin.org/v2/queue");

    /** The free plan allows twenty a minute; a little over three seconds apart never trips it. */
    private static final long PACE_MS = 3_100L;

    /** How long to back off when told to without being told for how long. */
    private static final long RETRY_MS = 10_000L;

    /** MineSkin asks for a job to be checked at most once a second. */
    private static final long POLL_MS = 1_000L;

    /** A job still not done after this long is abandoned for this run. */
    private static final long JOB_TIMEOUT_MS = 120_000L;

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    /** Every texture known, by cube hash: what the file held plus what arrived since. */
    private static final Map<String, String> KNOWN = new ConcurrentHashMap<>();

    /** Hashes waiting or being uploaded, so twenty deaths in one skin queue each cube once. */
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();

    /** Hashes MineSkin refused this run; asking again would be refused again. */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    // ponytail: one global queue worked strictly in order at the free plan's
    // pace, so a burst of brand-new skins takes minutes to finish. A paid key
    // would want the pace read from the key's own limit instead.
    private static final LinkedBlockingQueue<SkinCubes.Cube> PENDING = new LinkedBlockingQueue<>();

    private static volatile String key = "";
    private static volatile boolean rejected;
    private static volatile boolean warned;
    private static volatile Thread worker;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    private MineSkinQueue() {
    }

    /**
     * Sets the MineSkin key, from the library's config.
     *
     * <p>A new key clears a refusal, so fixing a typo in the config and
     * reloading is all it takes.
     *
     * @param value the key, or empty for none
     */
    public static void key(@Nullable String value) {
        String cleaned = value == null ? "" : value.trim();
        if (!cleaned.equals(key)) {
            key = cleaned;
            rejected = false;
            FAILED.clear();
        }
    }

    /** Whether a key is set and has not been refused. */
    public static boolean enabled() {
        return !key.isEmpty() && !rejected;
    }

    /**
     * Reads what earlier runs produced and starts the one worker.
     *
     * @param plugin  the library, for its data folder, version and logger
     * @param arrived run on the worker thread whenever textures become known
     */
    public static synchronized void start(Plugin plugin, Runnable arrived) {
        if (worker != null) {
            return;
        }
        logger = plugin.getLogger();
        Path file = plugin.getDataFolder().toPath().resolve("ragdoll-cubes.txt");
        // getDescription rather than Paper's getPluginMeta, which Spigot does
        // not have: this runs while the library is enabling.
        String agent = "ExyliaLib/" + plugin.getDescription().getVersion();
        Thread thread = new Thread(() -> run(file, agent, arrived), "ExyliaLib-MineSkin");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    /** Stops the worker, on shutdown. Whatever was queued is asked for again next run. */
    public static synchronized void stop() {
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
        PENDING.clear();
        QUEUED.clear();
    }

    /**
     * Asks for every cube not already known, queued or refused.
     *
     * @param cubes the cubes of one skin
     */
    public static void submit(List<SkinCubes.Cube> cubes) {
        if (!enabled()) {
            return;
        }
        for (SkinCubes.Cube cube : cubes) {
            if (!KNOWN.containsKey(cube.hash()) && !FAILED.contains(cube.hash())
                    && QUEUED.add(cube.hash())) {
                PENDING.add(cube);
            }
        }
    }

    /** The texture property for a cube, or {@code null} while it has none. */
    public static @Nullable String known(String hash) {
        return KNOWN.get(hash);
    }

    private static void run(Path file, String agent, Runnable arrived) {
        load(file);
        arrived.run();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                SkinCubes.Cube cube = PENDING.take();
                try {
                    if (enabled() && !KNOWN.containsKey(cube.hash())) {
                        long wait = upload(cube, agent, file, arrived);
                        Thread.sleep(Math.max(PACE_MS, wait));
                    }
                } catch (IOException | RuntimeException unreachable) {
                    // A runtime failure is caught too: it would otherwise end
                    // the one worker, and every later skin would wait forever.
                    logger.log(Level.FINE, "Could not upload a ragdoll cube to MineSkin", unreachable);
                    FAILED.add(cube.hash());
                } finally {
                    QUEUED.remove(cube.hash());
                }
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Uploads one cube and waits for its texture.
     *
     * @return how long MineSkin asked to wait before the next upload, or zero
     */
    private static long upload(SkinCubes.Cube cube, String agent, Path file, Runnable arrived)
            throws IOException, InterruptedException {
        String boundary = "exylia" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(QUEUE)
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("User-Agent", agent)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, png(cube))))
                .build();
        while (true) {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            long next = nextMillis(body);
            switch (response.statusCode()) {
                case 200, 202 -> {
                    String texture = texture(body);
                    String job = string(body, "job", "id");
                    if (texture == null && job != null) {
                        texture = poll(job, agent);
                    }
                    if (texture != null) {
                        accept(cube.hash(), texture, file);
                        arrived.run();
                    } else {
                        FAILED.add(cube.hash());
                    }
                    return next;
                }
                case 401, 403 -> {
                    rejected = true;
                    logger.warning("MineSkin refused the mineskin-key in config.yml; ragdoll bodies"
                            + " stay drawn in blocks until it is fixed and the library reloaded.");
                    return 0L;
                }
                case 429 -> Thread.sleep(next > 0 ? next : RETRY_MS);
                default -> {
                    // Once per run at warning level: a refusal that repeats for
                    // every cube, such as a plan that does not allow unlisted
                    // skins, would otherwise leave bodies in blocks with no clue.
                    if (!warned) {
                        warned = true;
                        logger.warning("MineSkin answered " + response.statusCode()
                                + " for a ragdoll cube; bodies stay in blocks. " + body);
                    }
                    logger.fine("MineSkin answered " + response.statusCode() + " for a ragdoll cube: " + body);
                    FAILED.add(cube.hash());
                    return next;
                }
            }
        }
    }

    /** Checks on a job once a second until it has a texture, has failed, or has taken too long. */
    private static @Nullable String poll(String job, String agent) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(QUEUE.resolve("queue/" + job))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("User-Agent", agent)
                .GET()
                .build();
        long deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (response.statusCode() == 429) {
                Thread.sleep(Math.max(0L, nextMillis(body)));
                continue;
            }
            if (response.statusCode() != 200) {
                return null;
            }
            String texture = texture(body);
            if (texture != null) {
                return texture;
            }
            if ("failed".equals(string(body, "job", "status"))) {
                return null;
            }
        }
        return null;
    }

    private static void accept(String hash, String texture, Path file) {
        KNOWN.put(hash, texture);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, hash + '\t' + texture + '\n', StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            // Kept in memory either way; the file only saves the next restart
            // from asking again.
            logger.log(Level.WARNING, "Could not save a ragdoll cube texture to " + file, unwritable);
        }
    }

    private static void load(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab < line.length() - 1) {
                    KNOWN.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException unreadable) {
            logger.log(Level.WARNING, "Could not read " + file + "; ragdoll cubes will be uploaded again",
                    unreadable);
        }
    }

    private static byte[] png(SkinCubes.Cube cube) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(cube.image(), "png", bytes);
        return bytes.toByteArray();
    }

    /** A form with the picture and the three options, written out by hand. */
    static byte[] multipart(String boundary, byte[] png) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "visibility", "unlisted");
        field(body, boundary, "variant", "classic");
        field(body, boundary, "name", "exylia-ragdoll");
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"cube.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(png);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The texture property in an answer, or {@code null} while it has none. */
    static @Nullable String texture(String body) {
        return string(body, "skin", "texture", "data", "value");
    }

    /** How long MineSkin asked to wait before the next request, or zero. */
    static long nextMillis(String body) {
        String relative = string(body, "rateLimit", "next", "relative");
        try {
            return relative == null ? 0L : Math.max(0L, (long) Double.parseDouble(relative));
        } catch (NumberFormatException unreadable) {
            return 0L;
        }
    }

    /** A string at a path of object fields, or {@code null} when any step is missing. */
    static @Nullable String string(String body, String... path) {
        try {
            JsonElement at = JsonParser.parseString(body);
            for (String step : path) {
                if (!(at instanceof JsonObject object)) {
                    return null;
                }
                at = object.get(step);
            }
            return at != null && at.isJsonPrimitive() ? at.getAsString() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
