package net.exylia.lib.web.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

/**
 * {@link EditorClient} over {@code java.net.http}, which the JVM already has.
 *
 * <pre>
 * POST {base}/api/v1/editor/sessions                                  -> 201 {code,url,expiresAt}
 * PUT  {base}/api/v1/editor/sessions/{code}/plugins/{plugin}          -> 204
 * GET  {base}/api/v1/editor/sessions/{code}/changes/pending           -> 200 {id,subjects,baseHash,author} | 204
 * POST {base}/api/v1/editor/sessions/{code}/changes/{id}/applied      -> 204
 * POST {base}/api/v1/editor/sessions/{code}/trust                     -> 204 | 404
 * </pre>
 *
 * <p>The secret travels as {@code Authorization: Bearer}. Every body is gzip-compressed
 * ({@code Content-Encoding: gzip}) and refused before it is sent when it compresses to more than
 * the editor takes. Answers are capped on the way in so a misbehaving host cannot fill the heap.
 */
final class HttpEditorClient implements EditorClient {

    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    /** The most the editor takes per request, compressed. */
    static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Gson GSON = new Gson();

    private final String base;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    HttpEditorClient(String base, String libraryVersion) {
        this.base = base;
        this.userAgent = "ExyliaLib/" + libraryVersion;
    }

    @Override
    public Opened open(String secret, JsonObject index) throws IOException {
        JsonObject answer = json(send(request("/api/v1/editor/sessions", secret)
                .header("Content-Encoding", "gzip").POST(body(index)).build(), 201, 200));
        return new Opened(text(answer, "code"), text(answer, "url"),
                answer.has("expiresAt") && answer.get("expiresAt").isJsonPrimitive()
                        ? answer.get("expiresAt").getAsLong() : 0L);
    }

    @Override
    public @Nullable Pending pending(String code, String secret) throws IOException {
        HttpResponse<byte[]> response = send(request("/api/v1/editor/sessions/" + encode(code)
                + "/changes/pending", secret).GET().build(), 200, 204);
        if (response.statusCode() == 204 || response.body().length == 0) {
            return null;
        }
        JsonObject answer = json(response);
        return new Pending(text(answer, "id"),
                answer.has("subjects") && answer.get("subjects").isJsonObject() ? answer.getAsJsonObject("subjects") : new JsonObject(),
                answer.has("baseHash") && answer.get("baseHash").isJsonObject() ? answer.getAsJsonObject("baseHash") : new JsonObject(),
                answer.has("author") && answer.get("author").isJsonPrimitive() ? answer.get("author").getAsString() : null);
    }

    @Override
    public void uploadPlugin(String code, String secret, String plugin, JsonObject payload) throws IOException {
        send(request("/api/v1/editor/sessions/" + encode(code) + "/plugins/" + encode(plugin), secret)
                .header("Content-Encoding", "gzip").PUT(body(payload)).build(), 204, 200);
    }

    @Override
    public void applied(String code, String secret, String changeId, JsonObject outcome) throws IOException {
        JsonObject body = new JsonObject();
        body.add("outcome", outcome);
        body.addProperty("version", Snapshot.VERSION);
        send(request("/api/v1/editor/sessions/" + encode(code) + "/changes/" + encode(changeId) + "/applied", secret)
                .header("Content-Encoding", "gzip").POST(body(body)).build(), 204, 200);
    }

    @Override
    public Trust trust(String code, String secret, String challenge) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("challenge", challenge);
        HttpResponse<byte[]> response = send(request("/api/v1/editor/sessions/" + encode(code) + "/trust", secret)
                .header("Content-Encoding", "gzip").POST(body(body)).build(), 204, 200, 404);
        return response.statusCode() == 404 ? Trust.UNKNOWN_CHALLENGE : Trust.TRUSTED;
    }

    private HttpRequest.Builder request(String path, String secret) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + secret)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent);
    }

    private static HttpRequest.BodyPublisher body(JsonObject json) throws IOException {
        byte[] compressed = gzip(GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        if (compressed.length > MAX_REQUEST_BYTES) {
            throw new IOException("the body compresses to " + compressed.length / (1024 * 1024)
                    + " MB; the editor takes at most " + MAX_REQUEST_BYTES / (1024 * 1024) + " MB");
        }
        return HttpRequest.BodyPublishers.ofByteArray(compressed);
    }

    static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, plain.length / 4));
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(plain);
        }
        return out.toByteArray();
    }

    private HttpResponse<byte[]> send(HttpRequest request, int... accepted) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while talking to the editor", interrupted);
        }
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new IOException("The editor answered with " + response.body().length + " bytes; refusing to read it");
        }
        for (int status : accepted) {
            if (response.statusCode() == status) {
                return response;
            }
        }
        String text = new String(response.body(), StandardCharsets.UTF_8);
        throw new Refused(response.statusCode(), text.length() > 200 ? text.substring(0, 200) : text);
    }

    private static JsonObject json(HttpResponse<byte[]> response) throws IOException {
        try {
            return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException malformed) {
            throw new IOException("The editor answered something that is not JSON", malformed);
        }
    }

    private static String text(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IOException("The editor's answer has no '" + key + "'");
        }
        return object.get(key).getAsString();
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8);
    }
}
