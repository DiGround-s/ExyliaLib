package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.skull.internal.Textures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What is read out of MineSkin's answers, and what is sent to it. */
class MineSkinQueueTest {

    private static final long NOW = 1_700_000_000_000L;

    private static final String DONE = """
            {"success":true,"job":{"id":"6763","status":"completed"},
             "skin":{"uuid":"a","texture":{"data":{"value":"VALUE","signature":"SIG"}}},
             "rateLimit":{"next":{"absolute":1700000000000,"relative":3000.0},"delay":{"millis":3000}}}
            """;

    private static final String WAITING = """
            {"success":true,"job":{"id":"6764","status":"waiting"},
             "rateLimit":{"next":{"absolute":1700000000000,"relative":0}}}
            """;

    private static final String HOUR_SPENT = """
            {"rateLimit":{"next":{"relative":3000},
              "limit":{"minute":{"limit":20,"remaining":12,"reset":1700000060},
                       "hour":{"limit":100,"remaining":0,"reset":1700003600}}}}
            """;

    private static final String MINUTE_SPENT = """
            {"rateLimit":{"limit":{"minute":{"limit":20,"remaining":0,"reset":1700000030},
                                   "hour":{"limit":100,"remaining":40,"reset":1700003600}}}}
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
        assertEquals(0L, MineSkinQueue.spentMillis("<html>502</html>", NOW));
    }

    @Test
    @DisplayName("a spent hour holds every upload until a second past its reset")
    void spentHour() {
        assertEquals(1_700_003_600_000L, MineSkinQueue.resetMillis(HOUR_SPENT, "hour"));
        assertEquals(0L, MineSkinQueue.resetMillis(HOUR_SPENT, "minute"), "the minute still has room");
        assertEquals(3_601_000L, MineSkinQueue.spentMillis(HOUR_SPENT, NOW));
    }

    @Test
    @DisplayName("a spent minute holds uploads only until the minute resets")
    void spentMinute() {
        assertEquals(0L, MineSkinQueue.resetMillis(MINUTE_SPENT, "hour"));
        assertEquals(31_000L, MineSkinQueue.spentMillis(MINUTE_SPENT, NOW));
    }

    @Test
    @DisplayName("an answer that reports no windows holds nothing")
    void noWindows() {
        assertEquals(0L, MineSkinQueue.spentMillis(DONE, NOW));
        assertEquals(0L, MineSkinQueue.spentMillis(WAITING, NOW));
    }

    @Test
    @DisplayName("only Mojang's texture id is kept out of a property")
    void textureId() {
        assertEquals("abc123", MineSkinQueue.textureId(Textures.fromUrl("abc123")));
        assertNull(MineSkinQueue.textureId("not a property"));
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
