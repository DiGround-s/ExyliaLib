package net.exylia.lib.ragdoll.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What is read out of MineSkin's answers, and what is sent to it. */
class MineSkinQueueTest {

    private static final String DONE = """
            {"success":true,"job":{"id":"6763","status":"completed"},
             "skin":{"uuid":"a","texture":{"data":{"value":"VALUE","signature":"SIG"}}},
             "rateLimit":{"next":{"absolute":1700000000000,"relative":3000.0},"delay":{"millis":3000}}}
            """;

    private static final String WAITING = """
            {"success":true,"job":{"id":"6764","status":"waiting"},
             "rateLimit":{"next":{"absolute":1700000000000,"relative":0}}}
            """;

    @Test
    @DisplayName("a finished job hands over its texture and how long to wait")
    void finished() {
        assertEquals("VALUE", MineSkinQueue.texture(DONE));
        assertEquals(3000L, MineSkinQueue.nextMillis(DONE));
    }

    @Test
    @DisplayName("a queued job has no texture yet, only an id to poll")
    void queued() {
        assertNull(MineSkinQueue.texture(WAITING));
        assertEquals("6764", MineSkinQueue.string(WAITING, "job", "id"));
        assertEquals("waiting", MineSkinQueue.string(WAITING, "job", "status"));
        assertEquals(0L, MineSkinQueue.nextMillis(WAITING));
    }

    @Test
    @DisplayName("an answer that is not JSON is nothing, never an exception")
    void malformed() {
        assertNull(MineSkinQueue.texture("<html>502</html>"));
        assertEquals(0L, MineSkinQueue.nextMillis(""));
    }

    @Test
    @DisplayName("the form carries the picture and asks for an unlisted classic skin")
    void form() {
        String body = new String(MineSkinQueue.multipart("B", new byte[]{1, 2, 3}), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"visibility\"\r\n\r\nunlisted\r\n"));
        assertTrue(body.contains("name=\"variant\"\r\n\r\nclassic\r\n"));
        assertTrue(body.contains("name=\"file\"; filename=\"cube.png\"\r\nContent-Type: image/png"));
        assertTrue(body.endsWith("\r\n--B--\r\n"));
    }
}
