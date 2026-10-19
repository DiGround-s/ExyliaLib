package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.skull.internal.Textures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What is sent to Exylia Skins and read out of its answers, and how long a refusal waits. */
class SkinTextureQueueTest {

    @Test
    @DisplayName("a piece is hashed exactly as protocol.md says: RGBA bytes, SHA-256")
    void protocolHash() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        // Clear, but with a colour a skin's base layer shows: it is part of the hash.
        image.setRGB(1, 0, 0x00ABCDEF);
        image.setRGB(1, 1, 0x80123456);
        // Worked out apart, in Python, from the bytes the protocol describes.
        assertEquals("40511476df749a41c82f9b8a66eaddc3f65998d6817fc458000960474a76d130", SkinCubes.hash(image));
    }

    @Test
    @DisplayName("every texture of an answer is read, with its id only once it is ready")
    void readsAnswers() {
        String body = """
                {"textures":[
                  {"hash":"a","status":"ready","id":"3b1a","value":"ewog","signature":"Kd9"},
                  {"hash":"b","status":"queued","position":3},
                  {"hash":"c","status":"unknown"},
                  {"hash":"d","status":"failed","error":"mojang said no"},
                  {"status":"ready","id":"no-hash"}
                ],"retryAfter":20}
                """;
        List<SkinTextureQueue.Texture> textures = SkinTextureQueue.textures(body);
        assertEquals(4, textures.size(), "an entry without a hash is skipped");
        assertTrue(textures.get(0).ready());
        assertEquals("3b1a", textures.get(0).id());
        assertFalse(textures.get(1).ready());
        assertFalse(textures.get(1).wanted(), "a queued piece is waited for, never sent again");
        assertTrue(textures.get(2).wanted());
        assertTrue(textures.get(3).wanted(), "a failed piece is sent again, which requeues it");
        assertEquals(20_000L, SkinTextureQueue.retryAfterMillis(body));
    }

    @Test
    @DisplayName("an answer that is not one reads as nothing, and polls at the default pace")
    void readsGarbage() {
        assertTrue(SkinTextureQueue.textures("<html>502</html>").isEmpty());
        assertTrue(SkinTextureQueue.textures("{\"error\":\"too big\"}").isEmpty());
        assertEquals(20_000L, SkinTextureQueue.retryAfterMillis("<html>502</html>"));
        assertEquals(1_000L, SkinTextureQueue.retryAfterMillis("{\"retryAfter\":0}"), "never sooner than a second");
    }

    @Test
    @DisplayName("a 429 waits its Retry-After; an outage or a bare 429 waits a backoff that doubles to a cap")
    void waits() {
        assertEquals(30_000L, SkinTextureQueue.waitMillis(429, "30", SkinTextureQueue.BACKOFF_MS));
        assertEquals(SkinTextureQueue.RETRY_CAP_MS, SkinTextureQueue.waitMillis(429, "999999", 1L));
        assertEquals(7_000L, SkinTextureQueue.waitMillis(429, "Wed, 21 Oct 2026 07:28:00 GMT", 7_000L));
        assertEquals(7_000L, SkinTextureQueue.waitMillis(503, "30", 7_000L), "only a 429's Retry-After is read");
        assertEquals(7_000L, SkinTextureQueue.waitMillis(0, null, 7_000L));

        long backoff = SkinTextureQueue.BACKOFF_MS;
        assertEquals(10_000L, backoff = SkinTextureQueue.nextBackoff(backoff));
        for (int i = 0; i < 20; i++) {
            backoff = SkinTextureQueue.nextBackoff(backoff);
        }
        assertEquals(SkinTextureQueue.BACKOFF_CAP_MS, backoff);
    }

    @Test
    @DisplayName("a skinless player wears vanilla's default for their UUID: wide Steve, slim Alex")
    void defaultSkin() {
        UUID steve = withIndex(15);
        String wide = SkinCache.defaultTextures(steve);
        assertEquals("http://textures.minecraft.net/texture/31f477eb1a7beee631c2ca64d06f8f68fa93a3386d04452ab27f43acdf1b60cb",
                Textures.urlOf(wide));
        assertFalse(Textures.slim(wide));

        String slim = SkinCache.defaultTextures(withIndex(0));
        assertEquals("46acd06e8483b176e8ea39fc12fe105eb3a2a4970f5100057e9d84d4b60bdfa7",
                SkinCache.textureId(Textures.urlOf(slim)));
        assertTrue(Textures.slim(slim));
        assertNull(SkinCache.textureId("https://example.com/skin.png"));
    }

    /** A UUID the client puts in default skin number {@code index}. */
    private static UUID withIndex(int index) {
        for (long low = 0; ; low++) {
            UUID id = new UUID(0L, low);
            if (Math.floorMod(id.hashCode(), 18) == index) {
                return id;
            }
        }
    }
}
