package net.exylia.lib.ragdoll.internal;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonArray;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the pieces a skin is cut into into textures Mojang hosts, through
 * Exylia Skins, once ever for everybody.
 *
 * <h2>Why a service at all</h2>
 * A client only draws a head texture that lives on Mojang's texture server,
 * and the only way a picture gets there is by a Minecraft account wearing it.
 * Exylia Skins (<a href="https://skins.exylia.net">skins.exylia.net</a>) keeps a
 * pool of accounts doing exactly that and a global cache of everything they
 * made, free and with no key, so every server gets real-skin bodies with
 * nothing to configure.
 *
 * <h2>Why it is cheap</h2>
 * A piece is named by the hash of its pixels, computed the same way on both
 * sides ({@link SkinCubes#hash}), so it is asked for with no picture first:
 * whatever any server ever made, a default Steve sleeve or the shoes a
 * thousand skins share, answers at once and costs no upload. Only the pieces
 * nobody has made are sent, one skin per request. Every answer is also written
 * to the database of each plugin that shows ragdolls ({@link RagdollTextures}),
 * so a restart asks for nothing.
 *
 * <h2>Never hammering</h2>
 * One worker, one request at a time. A piece still waiting in the service's
 * queue is asked about again no sooner than the service says; a refusal waits
 * the {@code Retry-After} it carries, and an outage waits longer each time it
 * repeats. Pieces stay drawn in blocks until their texture is ready.
 */
@ApiStatus.Internal
public final class SkinTextureQueue {

    private static final URI TEXTURES = URI.create("https://skins-api.exylia.net/v1/textures");

    /** The most pictures one request may carry, and the most hashes one lookup may ask for. */
    static final int MAX_IMAGES = 32;
    static final int MAX_HASHES = 64;

    /** The first wait after a refusal that gave no wait of its own, or an outage. */
    static final long BACKOFF_MS = 5_000L;

    /** The longest an outage ever makes the worker wait before asking again. */
    static final long BACKOFF_CAP_MS = 5 * 60_000L;

    /** The longest a refusal is believed: the hourly upload cap resets within the hour. */
    static final long RETRY_CAP_MS = 60 * 60_000L;

    /** How soon a waiting piece is asked about when the service names no time. */
    private static final long POLL_MS = 20_000L;

    /**
     * A piece still not ready after this long is let go for now. Its skin is
     * prepared again within minutes, which asks by hash, so nothing is lost
     * and nothing is uploaded twice: the service already has it queued.
     */
    private static final long WAIT_MS = 10 * 60_000L;

    /**
     * How long a piece that was not made waits before it is asked for again.
     * Short enough that a body is whole within minutes, long enough that a
     * picture the service truly refuses is not sent every few seconds.
     */
    private static final Duration RETRY = Duration.ofMinutes(10);

    /** The least time between two warnings in the console, so a refusal that repeats is one line, not a flood. */
    private static final long WARN_EVERY_MS = 10 * 60_000L;

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    /** Hashes waiting, being asked for or being made, so twenty deaths in one skin ask once. */
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();

    /**
     * Hashes the service did not make. Sending one again requeues it, so they
     * expire after {@link #RETRY} and the skin, prepared again within minutes
     * of that, gives each piece another chance on its own.
     */
    private static final Set<String> FAILED = Collections.newSetFromMap(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(RETRY)
            .<String, Boolean>build()
            .asMap());

    private static final LinkedBlockingQueue<Batch> PENDING = new LinkedBlockingQueue<>();

    /** When the console was last warned, so it is at most once every {@link #WARN_EVERY_MS}. */
    private static volatile long lastWarned;
    private static volatile Thread worker;
    private static volatile Logger logger = Logger.getLogger("ExyliaLib");

    /** How long the next outage makes the worker wait. Only the worker touches it. */
    private static long backoff = BACKOFF_MS;

    private SkinTextureQueue() {
    }

    /**
     * The pieces of one skin, as asked for.
     *
     * @param cubes   the pieces with a design nobody here has a texture for
     * @param skin    the Mojang texture id of the skin they were cut from, or {@code null}
     * @param quality how finely it was cut
     */
    private record Batch(List<SkinCubes.Cube> cubes, @Nullable String skin, SkinCubes.Quality quality) {
    }

    /**
     * One texture as the service describes it.
     *
     * @param hash   the piece's hash
     * @param status {@code ready}, {@code queued}, {@code uploading}, {@code failed} or {@code unknown}
     * @param id     Mojang's texture id once ready, else {@code null}
     * @param error  why it failed, when it did
     */
    record Texture(String hash, String status, @Nullable String id, @Nullable String error) {

        boolean ready() {
            return "ready".equals(status) && id != null;
        }

        /** Whether only an upload makes it: nobody asked for it, or the last try failed. */
        boolean wanted() {
            return "unknown".equals(status) || "failed".equals(status);
        }
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
        Thread thread = new Thread(() -> run(agent), "ExyliaLib-SkinTextures");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    /** Stops the worker, on shutdown. Whatever was waiting is asked for again next run. */
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
     * Asks for every piece of a skin with a design that is not already known,
     * waiting or failed recently.
     *
     * @param cubes   the pieces of one skin
     * @param skin    the Mojang texture id of the skin, or {@code null} when it has none
     * @param quality how finely it was cut, recorded by the service beside the pieces
     */
    public static void submit(List<SkinCubes.Cube> cubes, @Nullable String skin, SkinCubes.Quality quality) {
        List<SkinCubes.Cube> wanted = new ArrayList<>();
        for (SkinCubes.Cube cube : cubes) {
            if (cube.plain() == null && cube.image() != null && RagdollTextures.known(cube.hash()) == null
                    && !FAILED.contains(cube.hash()) && QUEUED.add(cube.hash())) {
                wanted.add(cube);
            }
        }
        // A skin is at most eighteen pieces; the split only guards the limit.
        for (int from = 0; from < wanted.size(); from += MAX_IMAGES) {
            PENDING.add(new Batch(List.copyOf(wanted.subList(from, Math.min(wanted.size(), from + MAX_IMAGES))),
                    skin, quality));
        }
    }

    private static void run(String agent) {
        // Pieces the service is making, by hash, and when each is given up on for now.
        Map<String, Long> waiting = new LinkedHashMap<>();
        long nextPoll = 0L;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                Batch batch = waiting.isEmpty()
                        ? PENDING.take()
                        : PENDING.poll(Math.max(0L, nextPoll - now), TimeUnit.MILLISECONDS);
                try {
                    if (batch != null) {
                        long retry = ask(batch, waiting, agent);
                        nextPoll = Math.max(nextPoll, System.currentTimeMillis() + retry);
                    } else if (!waiting.isEmpty()) {
                        nextPoll = System.currentTimeMillis() + poll(waiting, agent);
                    }
                } catch (IOException | RuntimeException refused) {
                    // Only an answer no wait fixes ends up here (outages are
                    // waited out in send), so the pieces are kept out for a
                    // while rather than asked for at every death. A runtime
                    // failure is caught too: it would otherwise end the one
                    // worker, and every later skin would wait forever.
                    refused("Exylia Skins refused ragdoll skin pieces: " + refused.getMessage());
                    logger.log(Level.FINE, "Exylia Skins refused ragdoll skin pieces", refused);
                    if (batch != null) {
                        for (SkinCubes.Cube cube : batch.cubes()) {
                            if (!waiting.containsKey(cube.hash())) {
                                FAILED.add(cube.hash());
                                QUEUED.remove(cube.hash());
                            }
                        }
                    }
                    nextPoll = System.currentTimeMillis() + BACKOFF_CAP_MS;
                }
                giveUp(waiting, System.currentTimeMillis());
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Asks for one skin's pieces by hash, then uploads only what nobody has.
     *
     * @return how long to wait before asking about the pieces still being made
     */
    private static long ask(Batch batch, Map<String, Long> waiting, String agent)
            throws IOException, InterruptedException {
        Map<String, SkinCubes.Cube> asked = new LinkedHashMap<>();
        for (SkinCubes.Cube cube : batch.cubes()) {
            if (RagdollTextures.known(cube.hash()) == null) {
                asked.put(cube.hash(), cube);
            } else {
                QUEUED.remove(cube.hash());
            }
        }
        if (asked.isEmpty()) {
            return 0L;
        }
        String body = send(lookup(asked.keySet()), agent);
        long retry = retryAfterMillis(body);
        List<SkinCubes.Cube> upload = new ArrayList<>();
        for (Texture texture : textures(body)) {
            SkinCubes.Cube cube = asked.remove(texture.hash());
            if (cube == null) {
                continue;
            }
            if (texture.wanted()) {
                upload.add(cube);
            } else {
                settle(texture, waiting);
            }
        }
        // Anything the answer left out is asked about again with the rest.
        asked.keySet().forEach(hash -> waiting.putIfAbsent(hash, System.currentTimeMillis() + WAIT_MS));
        if (!upload.isEmpty()) {
            body = send(HttpRequest.newBuilder(TEXTURES)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(uploadBody(upload, batch.skin(), batch.quality()))), agent);
            retry = Math.max(retry, retryAfterMillis(body));
            Set<String> sent = new HashSet<>();
            upload.forEach(cube -> sent.add(cube.hash()));
            for (Texture texture : textures(body)) {
                if (sent.remove(texture.hash())) {
                    settle(texture, waiting);
                } else {
                    // The service hashed a picture differently: every piece
                    // would be uploaded again each time its skin is prepared.
                    refused("Exylia Skins named a ragdoll skin piece by a hash ExyliaLib did not send;"
                            + " the hash in protocol.md and SkinCubes.hash disagree.");
                }
            }
            sent.forEach(hash -> waiting.putIfAbsent(hash, System.currentTimeMillis() + WAIT_MS));
        }
        return retry;
    }

    /** Asks about every piece still being made, a lookup's worth at a time. */
    private static long poll(Map<String, Long> waiting, String agent) throws IOException, InterruptedException {
        long retry = 0L;
        List<String> hashes = new ArrayList<>(waiting.keySet());
        for (int from = 0; from < hashes.size(); from += MAX_HASHES) {
            String body = send(lookup(hashes.subList(from, Math.min(hashes.size(), from + MAX_HASHES))), agent);
            retry = Math.max(retry, retryAfterMillis(body));
            for (Texture texture : textures(body)) {
                if (waiting.containsKey(texture.hash())) {
                    settle(texture, waiting);
                }
            }
        }
        return retry;
    }

    /** Acts on what the service said about one piece: kept, dropped, or still waited for. */
    private static void settle(Texture texture, Map<String, Long> waiting) {
        String hash = texture.hash();
        if (texture.ready()) {
            RagdollTextures.arrived(hash, texture.id());
        } else if (texture.wanted()) {
            // A piece that failed, or one the service lost: tried again once
            // the failure expires and its skin is prepared again.
            if ("failed".equals(texture.status())) {
                refused("Exylia Skins could not make a ragdoll skin piece: " + texture.error());
            }
            FAILED.add(hash);
        } else {
            waiting.putIfAbsent(hash, System.currentTimeMillis() + WAIT_MS);
            return;
        }
        waiting.remove(hash);
        QUEUED.remove(hash);
    }

    private static void giveUp(Map<String, Long> waiting, long now) {
        waiting.entrySet().removeIf(entry -> {
            boolean late = entry.getValue() <= now;
            if (late) {
                QUEUED.remove(entry.getKey());
            }
            return late;
        });
    }

    private static HttpRequest.Builder lookup(Iterable<String> hashes) {
        String joined = URLEncoder.encode(String.join(",", hashes), StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create(TEXTURES + "?hashes=" + joined + "&variant=classic")).GET();
    }

    /**
     * Sends a request until it is answered, waiting out every refusal and outage.
     *
     * @return the body of a {@code 200}
     * @throws IOException when the service answers something no wait will fix,
     *                     such as a {@code 400}
     */
    private static String send(HttpRequest.Builder request, String agent) throws IOException, InterruptedException {
        HttpRequest built = request.timeout(TIMEOUT).header("User-Agent", agent).build();
        while (true) {
            int status;
            String body;
            String retryAfter = null;
            try {
                HttpResponse<String> response = HTTP.send(built, HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                body = response.body();
                retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            } catch (IOException unreachable) {
                status = 0;
                body = "";
                logger.log(Level.FINE, "Exylia Skins could not be reached", unreachable);
            }
            if (status == 200) {
                backoff = BACKOFF_MS;
                return body;
            }
            if (status != 0 && status != 429 && status < 500) {
                throw new IOException("Exylia Skins answered " + status + ": " + body);
            }
            long wait = waitMillis(status, retryAfter, backoff);
            if (status != 429 || retryAfter == null) {
                backoff = nextBackoff(backoff);
            }
            Thread.sleep(wait);
        }
    }

    /**
     * How long to wait before sending again after an answer that was not a
     * {@code 200}: a {@code 429}'s own {@code Retry-After}, never less than a
     * second, or the current backoff when it names none or the service is down.
     *
     * @param status     the status, or {@code 0} when there was no answer
     * @param retryAfter the {@code Retry-After} header, in seconds, or {@code null}
     * @param backoff    the backoff now in force
     */
    static long waitMillis(int status, @Nullable String retryAfter, long backoff) {
        if (status == 429 && retryAfter != null) {
            try {
                return Math.max(1_000L, Math.min(RETRY_CAP_MS, Long.parseLong(retryAfter.trim()) * 1_000L));
            } catch (NumberFormatException unreadable) {
                // An HTTP date rather than seconds: the backoff is as good.
            }
        }
        return backoff;
    }

    /** The backoff after one more failure: doubled, up to its cap. */
    static long nextBackoff(long backoff) {
        return Math.min(BACKOFF_CAP_MS, Math.max(BACKOFF_MS, backoff * 2));
    }

    /** The request asking to make some pieces. */
    static String uploadBody(List<SkinCubes.Cube> cubes, @Nullable String skin, SkinCubes.Quality quality)
            throws IOException {
        JsonArray images = new JsonArray();
        for (SkinCubes.Cube cube : cubes) {
            JsonObject image = new JsonObject();
            image.addProperty("png", Base64.getEncoder().encodeToString(png(cube)));
            image.addProperty("variant", "classic");
            images.add(image);
        }
        JsonObject body = new JsonObject();
        body.add("images", images);
        body.addProperty("signed", false);
        if (skin != null) {
            body.addProperty("skin", skin);
        }
        body.addProperty("quality", quality.name().toLowerCase(Locale.ROOT));
        return body.toString();
    }

    private static byte[] png(SkinCubes.Cube cube) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(cube.image(), "png", bytes);
        return bytes.toByteArray();
    }

    /**
     * Every texture in an answer, skipping any entry without a hash and a status.
     *
     * @param body an answer from {@code /v1/textures}
     * @return the textures, empty when the body is not one
     */
    static List<Texture> textures(String body) {
        List<Texture> textures = new ArrayList<>();
        try {
            if (!(JsonParser.parseString(body) instanceof JsonObject root)
                    || !(root.get("textures") instanceof JsonArray array)) {
                return textures;
            }
            for (JsonElement element : array) {
                if (element instanceof JsonObject texture) {
                    String hash = string(texture, "hash");
                    String status = string(texture, "status");
                    String id = string(texture, "id");
                    if (hash != null && status != null) {
                        textures.add(new Texture(hash, status,
                                id == null || id.isEmpty() || id.length() > 64 ? null : id, string(texture, "error")));
                    }
                }
            }
        } catch (RuntimeException malformed) {
            // A proxy's error page or a cut-off answer: nothing in it is usable.
        }
        return textures;
    }

    /**
     * How long the service asks to wait before the next lookup of pieces still
     * being made, or the default when it says nothing.
     */
    static long retryAfterMillis(String body) {
        try {
            if (JsonParser.parseString(body) instanceof JsonObject root) {
                JsonElement value = root.get("retryAfter");
                if (value != null && value.isJsonPrimitive()) {
                    return Math.max(1_000L, (long) (value.getAsDouble() * 1_000));
                }
            }
        } catch (RuntimeException malformed) {
            // Falls through to the default.
        }
        return POLL_MS;
    }

    /**
     * Says that pieces were not made: in the console at most once every ten
     * minutes, and at fine level every other time, so a server whose bodies
     * stay in blocks has something to go on without a flood.
     */
    private static void refused(String what) {
        long now = System.currentTimeMillis();
        if (now - lastWarned >= WARN_EVERY_MS) {
            lastWarned = now;
            logger.warning(what + " Bodies wearing it stay in blocks; it is asked for again in "
                    + RETRY.toMinutes() + " minutes.");
        } else {
            logger.fine(what);
        }
    }

    private static @Nullable String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
