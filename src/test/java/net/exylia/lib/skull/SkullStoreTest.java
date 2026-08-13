package net.exylia.lib.skull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.FakeServer;
import net.exylia.lib.skull.internal.SkullStore;
import net.exylia.lib.skull.internal.Textures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Textures on disk, and the encoding they are stored in.
 *
 * <p>Persistence is what makes the first menu after a restart instant instead
 * of a burst of HTTP, so "does it actually come back" is worth a test.
 */
class SkullStoreTest {

    private static final String STEVE = Textures.fromUrl("abc123");

    @TempDir Path folder;

    private Path file;
    private Logger logger;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        file = folder.resolve("skull-cache.txt");
        logger = Logger.getLogger("SkullStoreTest");
    }

    private static Cache<String, String> newCache() {
        return Caffeine.newBuilder().maximumSize(100).build();
    }

    @Test
    @DisplayName("a texture written before a restart is there after it")
    void texturesSurviveARestart() {
        SkullStore before = new SkullStore(file, logger);
        before.remember("p:notch", STEVE);
        before.save(newCache());

        SkullStore after = new SkullStore(file, logger);
        Cache<String, String> restored = newCache();
        after.load(restored);

        assertEquals(STEVE, restored.getIfPresent("p:notch"));
    }

    @Test
    @DisplayName("nothing is written when nothing changed")
    void cleanStoreWritesNothing() {
        SkullStore store = new SkullStore(file, logger);

        store.save(newCache());

        assertFalse(Files.exists(file), "an unchanged cache should not touch the disk");
    }

    @Test
    @DisplayName("a corrupted line costs one head, not the whole file")
    void malformedLinesAreSkipped() throws Exception {
        Files.writeString(file,
                "p:good\t" + System.currentTimeMillis() + "\t" + STEVE + "\n"
                        + "this line is nonsense\n"
                        + "p:alsogood\t" + System.currentTimeMillis() + "\t" + STEVE + "\n",
                StandardCharsets.UTF_8);

        Cache<String, String> cache = newCache();
        new SkullStore(file, logger).load(cache);

        assertEquals(STEVE, cache.getIfPresent("p:good"));
        assertEquals(STEVE, cache.getIfPresent("p:alsogood"));
        assertEquals(2, cache.estimatedSize());
    }

    @Test
    @DisplayName("entries older than the TTL are dropped on load")
    void expiredEntriesAreDropped() throws Exception {
        long longAgo = System.currentTimeMillis() - java.time.Duration.ofDays(30).toMillis();
        Files.writeString(file, "p:stale\t" + longAgo + "\t" + STEVE + "\n",
                StandardCharsets.UTF_8);

        Cache<String, String> cache = newCache();
        new SkullStore(file, logger).load(cache);

        assertNull(cache.getIfPresent("p:stale"), "a month-old skin should be refetched");
    }

    @Test
    @DisplayName("a truncated texture is not restored as a broken head")
    void invalidTexturesAreRejected() throws Exception {
        Files.writeString(file,
                "p:broken\t" + System.currentTimeMillis() + "\tnot-really-base64!!\n",
                StandardCharsets.UTF_8);

        Cache<String, String> cache = newCache();
        new SkullStore(file, logger).load(cache);

        assertEquals(0, cache.estimatedSize());
    }

    @Test
    @DisplayName("forgetting an entry keeps it out of the next write")
    void forgottenEntriesAreNotWritten() {
        SkullStore store = new SkullStore(file, logger);
        store.remember("p:notch", STEVE);
        store.remember("p:jeb", STEVE);
        store.forget("p:notch");
        store.save(newCache());

        Cache<String, String> cache = newCache();
        new SkullStore(file, logger).load(cache);

        assertNull(cache.getIfPresent("p:notch"));
        assertNotNull(cache.getIfPresent("p:jeb"));
    }

    @Test
    @DisplayName("a URL survives the round trip through base64")
    void urlRoundTrips() {
        String texture = Textures.fromUrl(
                "https://textures.minecraft.net/texture/deadbeef");

        assertEquals("https://textures.minecraft.net/texture/deadbeef",
                Textures.urlOf(texture));
        assertTrue(Textures.isValid(texture));
    }

    @Test
    @DisplayName("the three forms a config writes a skin in all work")
    void everyUrlFormIsAccepted() {
        String expected = "https://textures.minecraft.net/texture/deadbeef";

        assertEquals(expected, Textures.urlOf(Textures.fromUrl("deadbeef")));
        assertEquals(expected, Textures.urlOf(
                Textures.fromUrl("textures.minecraft.net/texture/deadbeef")));
        assertEquals(expected, Textures.urlOf(Textures.fromUrl(expected)));
    }

    @Test
    @DisplayName("rubbish is not mistaken for a texture")
    void invalidTexturesAreDetected() {
        assertFalse(Textures.isValid(null));
        assertFalse(Textures.isValid(""));
        assertFalse(Textures.isValid("definitely not base64 %%%"));
        // Valid base64, but not a texture document.
        assertFalse(Textures.isValid(java.util.Base64.getEncoder()
                .encodeToString("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8))));
    }
}
