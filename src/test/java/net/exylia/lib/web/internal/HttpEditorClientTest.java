package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What actually goes over the wire: gzip bodies, the v2 paths, the headers. */
class HttpEditorClientTest {

    private record Received(String method, String path, String encoding, String authorization, String agent, String key, String body) {
    }

    private final List<Received> received = new ArrayList<>();
    private HttpServer server;
    private HttpEditorClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
            byte[] raw = exchange.getRequestBody().readAllBytes();
            String body = "";
            if (raw.length > 0) {
                try (InputStream in = "gzip".equals(encoding)
                        ? new GZIPInputStream(new java.io.ByteArrayInputStream(raw))
                        : new java.io.ByteArrayInputStream(raw)) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            synchronized (received) {
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), encoding,
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("User-Agent"),
                        exchange.getRequestHeaders().getFirst("X-Exylia-Server-Key"), body));
            }
            if (exchange.getRequestURI().getPath().equals("/api/v1/editor/sessions")) {
                byte[] answer = "{\"code\":\"abc\",\"url\":\"https://e/abc\",\"expiresAt\":5}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, answer.length);
                exchange.getResponseBody().write(answer);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
        client = new HttpEditorClient("http://127.0.0.1:" + server.getAddress().getPort(), "1.307.0", "k".repeat(43));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void everyBodyIsGzippedAndReadsBackTheSame() throws IOException {
        JsonObject index = JsonParser.parseString("{\"version\":2,\"plugins\":[{\"name\":\"ExyliaMines\"}]}").getAsJsonObject();
        EditorClient.Opened opened = client.open("s3cret", index);
        assertEquals("abc", opened.code());

        JsonObject payload = new JsonObject();
        payload.addProperty("lore", "é ".repeat(5000));
        client.uploadPlugin("abc", "s3cret", "ExyliaMines", payload);

        JsonObject outcome = JsonParser.parseString("{\"applied\":[\"ExyliaMines/mines\"],\"rejected\":{}}").getAsJsonObject();
        client.applied("abc", "s3cret", "change-1", outcome);

        assertEquals(3, received.size());
        Received open = received.get(0);
        assertEquals("POST", open.method());
        assertEquals("gzip", open.encoding());
        assertEquals("Bearer s3cret", open.authorization());
        assertEquals("ExyliaLib/1.307.0", open.agent());
        assertEquals("k".repeat(43), open.key());
        assertEquals(index, JsonParser.parseString(open.body()));

        Received upload = received.get(1);
        assertEquals("PUT", upload.method());
        assertEquals("/api/v1/editor/sessions/abc/plugins/ExyliaMines", upload.path());
        assertEquals("gzip", upload.encoding());
        assertEquals(payload, JsonParser.parseString(upload.body()));
        assertEquals(null, upload.key(), "the key goes with the open only");

        JsonObject applied = JsonParser.parseString(received.get(2).body()).getAsJsonObject();
        assertEquals(2, applied.get("version").getAsInt());
        assertEquals(outcome, applied.get("outcome"));
        assertTrue(!applied.has("snapshot"), "v2 sends no snapshot; the plugins are uploaded again instead");
    }

    @Test
    void gzipShrinksWhatRepeats() throws IOException {
        byte[] plain = "items: STONE\n".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        byte[] packed = HttpEditorClient.gzip(plain);
        assertTrue(packed.length * 20 < plain.length, packed.length + " of " + plain.length);
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(packed))) {
            assertEquals(new String(plain, StandardCharsets.UTF_8), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
