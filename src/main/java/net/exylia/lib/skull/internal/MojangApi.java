package net.exylia.lib.skull.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to Mojang, quietly.
 *
 * <p>Done by hand rather than through Paper's {@code PlayerProfile.complete()}
 * for one reason found the hard way: when Mojang rate-limits a server, Paper
 * logs a wall of stack traces for every head in the menu. A rate limit is an
 * ordinary, expected condition — it deserves one line and a back-off, not a
 * crash report per head.
 *
 * <p>Everything here runs off the main thread; the callers are responsible for
 * that, and this class never hops back on its own.
 *
 * <h2>Back-off</h2>
 * A single shared deadline, not a per-request one. Once Mojang says no, every
 * lookup stops asking until the deadline passes: forty menu heads must not
 * turn one 429 into forty more.
 */
public class MojangApi implements Lookup {

    private static final String NAME_TO_ID =
            "https://api.mojang.com/users/profiles/minecraft/";
    private static final String ID_TO_PROFILE =
            "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** Sent so Mojang can see who is calling, which is only polite. */
    private static final String USER_AGENT = "ExyliaLib";

    private final HttpClient http;
    private final Duration timeout;
    private final Logger logger;

    /** When the shared back-off expires, as epoch millis. Zero means open. */
    private final AtomicLong openAt = new AtomicLong();

    /** How long to stay quiet after a rate limit, and after a network error. */
    private final long rateLimitBackoffMillis;
    private final long networkBackoffMillis;

    public MojangApi(Duration timeout, long rateLimitBackoffMillis,
                     long networkBackoffMillis, Logger logger) {
        this.timeout = timeout;
        this.rateLimitBackoffMillis = rateLimitBackoffMillis;
        this.networkBackoffMillis = networkBackoffMillis;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                // HTTP/1.1 on purpose: Mojang's endpoints do not benefit from
                // HTTP/2 here and the negotiation is one more thing to fail.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Returns whether lookups are currently backed off. */
    @Override
    public boolean isBackedOff() {
        return System.currentTimeMillis() < openAt.get();
    }

    /** Returns how long the back-off still has to run, in millis. */
    @Override
    public long backoffRemaining() {
        return Math.max(0, openAt.get() - System.currentTimeMillis());
    }

    /** Clears the back-off. Used by tests and by an explicit cache flush. */
    @Override
    public void clearBackoff() {
        openAt.set(0);
    }

    /**
     * Resolves a player name to a unique id.
     *
     * @param name the player name
     * @return the id, or {@code null} when unknown, rate-limited or offline
     */
    @Override
    public UUID idOf(String name) {
        if (isBackedOff()) {
            return null;
        }
        String body = get(NAME_TO_ID + name, "id lookup for " + name);
        if (body == null) {
            return null;
        }
        String id = Json.string(body, "id");
        return id == null ? null : parseUndashed(id);
    }

    /**
     * Fetches the texture property of a profile.
     *
     * @param id the player's unique id
     * @return the base64 texture, or {@code null} when unavailable
     */
    @Override
    public String textureOf(UUID id) {
        if (isBackedOff()) {
            return null;
        }
        String body = get(ID_TO_PROFILE + undashed(id), "texture lookup for " + id);
        if (body == null) {
            return null;
        }
        String texture = Json.property(body, "textures");
        return Textures.isValid(texture) ? texture : null;
    }

    /**
     * Performs the request and turns every failure into {@code null}.
     *
     * <p>The whole point of this class: a caller asking for a head gets a head
     * or it does not, and a server owner gets one readable line rather than a
     * stack trace per head.
     */
    private String get(String url, String what) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> response.body();
                // No such player, or a profile with no skin. Not an error, and
                // not worth a line in the console.
                case 204, 404 -> null;
                case 429 -> {
                    backOff(rateLimitBackoffMillis, "Mojang is rate-limiting us");
                    yield null;
                }
                default -> {
                    backOff(networkBackoffMillis,
                            "Mojang answered " + response.statusCode() + " to a " + what);
                    yield null;
                }
            };
        } catch (InterruptedException interrupted) {
            // The server is shutting down and cancelled us. Restore the flag
            // and leave without a word: this is not a failure.
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failed) {
            backOff(networkBackoffMillis, "could not reach Mojang (" + failed.getMessage() + ")");
            return null;
        }
    }

    /**
     * Starts, or extends, the shared quiet period.
     *
     * <p>Logged once per back-off rather than once per request: the first head
     * to hit a rate limit says so, and the other thirty-nine stay silent.
     */
    private void backOff(long millis, String why) {
        long until = System.currentTimeMillis() + millis;
        long previous = openAt.getAndUpdate(current -> Math.max(current, until));
        if (previous < System.currentTimeMillis()) {
            logger.log(Level.INFO, "Skulls: {0}; pausing player lookups for {1}s."
                    + " Heads already cached are unaffected.",
                    new Object[]{why, millis / 1000});
        }
    }

    private static String undashed(UUID id) {
        return id.toString().replace("-", "");
    }

    private static UUID parseUndashed(String id) {
        if (id.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                    + id.substring(12, 16) + "-" + id.substring(16, 20) + "-"
                    + id.substring(20));
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
