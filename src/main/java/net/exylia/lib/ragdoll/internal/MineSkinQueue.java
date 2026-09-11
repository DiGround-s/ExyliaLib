package net.exylia.lib.ragdoll.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.skull.internal.Textures;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the pieces a skin is cut into into textures Mojang hosts, through
 * MineSkin, one at a time and once ever.
 *
 * <h2>Why a third party</h2>
 * A client only draws a head texture that lives on Mojang's texture server,
 * and the only way a picture gets there is by a Minecraft account wearing it.
 * MineSkin keeps a pool of accounts doing exactly that and hands back the
 * property. Without a key nothing here runs and bodies stay in blocks.
 *
 * <h2>Why it never has to happen twice</h2>
 * A texture MineSkin produced is permanent, and a piece is keyed by the hash of
 * its pixels. Every answer is written to the database of each plugin that
 * shows ragdolls ({@link RagdollTextures}), so a skin is uploaded the first
 * time anybody wearing it joins and never again &mdash; not after a restart,
 * not on another server sharing that database, and not for the next player
 * whose sleeves happen to be the same.
 *
 * <h2>Within the plan's limits</h2>
 * The free plan allows twenty uploads a minute and a hundred an hour. The
 * minute is kept by pacing. The hour is read from every answer, and once it is
 * spent the worker sleeps until MineSkin says it resets rather than asking
 * again every few seconds, which would only ever be refused.
 */
@ApiStatus.Internal
public final class MineSkinQueue {

    private static final URI QUEUE = URI.create("https://api.mineskin.org/v2/queue");

    /** The free plan allows twenty a minute; a little over three seconds apart never trips it. */
    private static final long PACE_MS = 3_100L;

    /** The first wait after being told to slow down without being told for how long. */
    private static final long BACKOFF_MS = 10_000L;

    /** The longest a refusal ever makes the worker wait before asking again. */
    private static final long BACKOFF_CAP_MS = 15 * 60_000L;

    /** MineSkin asks for a job to be checked at most once a second. */
    private static final long POLL_MS = 1_000L;

    /** A job still not done after this long is abandoned for this run. */
    private static final long JOB_TIMEOUT_MS = 120_000L;

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    /** Hashes waiting or being uploaded, so twenty deaths in one skin queue each piece once. */
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();

    /** Hashes MineSkin refused this run; asking again would be refused again. */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    // ponytail: one global queue worked strictly in order at the free plan's
    // pace, so a burst of brand-new skins takes as many hours as it takes. A
    // paid key would want the pace read from the key's own grants instead.
    private static final LinkedBlockingQueue<SkinCubes.Cube> PENDING = new LinkedBlockingQueue<>();

    private static volatile String key = "";
    private static volatile boolean rejected;
    private static volatile boolean warned;
    private static volatile Thread worker;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    /** How long the next refusal without a reason makes the worker wait. Only the worker touches it. */
    private static long backoff = BACKOFF_MS;

    /** The hourly reset last announced, so each pause is said once. Only the worker touches it. */
    private static long announced;

    private MineSkinQueue() {
    }

    /**
     * Sets the MineSkin key, from the library's config.
     *
     * <p>A new key clears a refusal and its warning, so fixing a typo in the
     * config and reloading is all it takes.
     *
     * @param value the key, or empty for none
     * @return whether it is a different key from the one in use
     */
    public static boolean key(@Nullable String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.equals(key)) {
            return false;
        }
        key = cleaned;
        rejected = false;
        warned = false;
        FAILED.clear();
        return true;
    }

    /** Whether a key is set and has not been refused. */
    public static boolean enabled() {
        return !key.isEmpty() && !rejected;
    }

    /**
     * Starts the one worker.
     *
     * @param plugin the library, for its version and logger
     */
    public static synchronized void start(Plugin plugin) {
        if (worker != null) {
            return;
        }
        logger = plugin.getLogger();
        // getDescription rather than Paper's getPluginMeta, which Spigot does
        // not have: this runs while the library is enabling.
        String agent = "ExyliaLib/" + plugin.getDescription().getVersion();
        Thread thread = new Thread(() -> run(agent), "ExyliaLib-MineSkin");
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
     * Asks for every piece with a design that is not already known, queued or
     * refused, in the order given.
     *
     * @param cubes the pieces of one skin
     */
    public static void submit(List<SkinCubes.Cube> cubes) {
        if (!enabled()) {
            return;
        }
        for (SkinCubes.Cube cube : cubes) {
            if (cube.plain() == null && RagdollTextures.known(cube.hash()) == null
                    && !FAILED.contains(cube.hash()) && QUEUED.add(cube.hash())) {
                PENDING.add(cube);
            }
        }
    }

    private static void run(String agent) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                SkinCubes.Cube cube = PENDING.take();
                try {
                    if (enabled() && RagdollTextures.known(cube.hash()) == null) {
                        long wait = upload(cube, agent);
                        Thread.sleep(Math.max(PACE_MS, wait));
                    }
                } catch (IOException | RuntimeException unreachable) {
                    // A runtime failure is caught too: it would otherwise end
                    // the one worker, and every later skin would wait forever.
                    logger.log(Level.FINE, "Could not upload a ragdoll skin piece to MineSkin", unreachable);
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
     * Uploads one piece and waits for its texture.
     *
     * @return how long to wait before the next upload, or zero
     */
    private static long upload(SkinCubes.Cube cube, String agent) throws IOException, InterruptedException {
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
            long now = System.currentTimeMillis();
            long wait = Math.max(nextMillis(body), spentMillis(body, now));
            announce(body);
            switch (response.statusCode()) {
                case 200, 202 -> {
                    backoff = BACKOFF_MS;
                    String texture = texture(body);
                    String job = string(body, "job", "id");
                    if (texture == null && job != null) {
                        texture = poll(job, agent);
                    }
                    String id = texture == null ? null : textureId(texture);
                    if (id != null) {
                        RagdollTextures.arrived(cube.hash(), id);
                    } else {
                        FAILED.add(cube.hash());
                    }
                    return wait;
                }
                case 401, 403 -> {
                    rejected = true;
                    logger.warning("MineSkin refused the mineskin-key in config.yml; ragdoll bodies"
                            + " stay drawn in blocks until it is fixed and the library reloaded.");
                    return 0L;
                }
                case 429 -> {
                    // Never a few seconds against a spent hour: the longest of
                    // what MineSkin said, the window's own reset and a backoff
                    // that doubles for as long as the refusals keep coming.
                    long held = Math.max(wait, backoff);
                    backoff = Math.min(backoff * 2, BACKOFF_CAP_MS);
                    Thread.sleep(held);
                }
                default -> {
                    // Once per key at warning level: a refusal that repeats for
                    // every piece, such as a plan that does not allow unlisted
                    // skins, would otherwise leave bodies in blocks with no clue.
                    if (!warned) {
                        warned = true;
                        logger.warning("MineSkin answered " + response.statusCode()
                                + " for a ragdoll skin piece; bodies stay in blocks. " + body);
                    }
                    logger.fine("MineSkin answered " + response.statusCode() + " for a ragdoll skin piece: " + body);
                    FAILED.add(cube.hash());
                    return wait;
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
                Thread.sleep(Math.max(nextMillis(body), spentMillis(body, System.currentTimeMillis())));
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

    /** Says once, in the console, that the hour is spent and when uploads start again. */
    private static void announce(String body) {
        long reset = resetMillis(body, "hour");
        if (reset > 0 && reset != announced) {
            announced = reset;
            logger.info("The hourly MineSkin limit was reached; ragdoll skins resume at "
                    + CLOCK.format(LocalTime.ofInstant(Instant.ofEpochMilli(reset), ZoneId.systemDefault())) + ".");
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

    /**
     * Mojang's id for a texture property: the end of its address.
     *
     * @return the id, or {@code null} when the property carries no usable address
     */
    static @Nullable String textureId(String property) {
        String url = Textures.urlOf(property);
        if (url == null) {
            return null;
        }
        String id = url.substring(url.lastIndexOf('/') + 1);
        return id.isEmpty() || id.length() > 64 ? null : id;
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

    /**
     * How long until a spent window resets, the longer of the minute and the
     * hour, or zero when neither is spent or neither is reported.
     *
     * @param body an answer from MineSkin
     * @param now  the current time, in epoch milliseconds
     */
    static long spentMillis(String body, long now) {
        long reset = Math.max(resetMillis(body, "minute"), resetMillis(body, "hour"));
        // A second past the reset, so the first request after it is not the
        // last one of the old window by a clock a little ahead of MineSkin's.
        return reset > 0 ? Math.max(0L, reset + 1_000L - now) : 0L;
    }

    /**
     * When a window resets if it has nothing left, in epoch milliseconds.
     *
     * @param window {@code minute} or {@code hour}
     * @return the reset, or zero when the window is not spent or not reported
     */
    static long resetMillis(String body, String window) {
        String remaining = string(body, "rateLimit", "limit", window, "remaining");
        String reset = string(body, "rateLimit", "limit", window, "reset");
        if (remaining == null || reset == null) {
            return 0L;
        }
        try {
            // MineSkin sends the reset in epoch seconds.
            return Double.parseDouble(remaining) > 0 ? 0L : (long) (Double.parseDouble(reset) * 1_000);
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
