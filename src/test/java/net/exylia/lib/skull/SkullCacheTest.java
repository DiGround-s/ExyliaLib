package net.exylia.lib.skull;

import net.exylia.lib.FakeServer;
import net.exylia.lib.skull.internal.Lookup;
import net.exylia.lib.skull.internal.SkullRuntime;
import net.exylia.lib.skull.internal.SkullStore;
import net.exylia.lib.skull.internal.Textures;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the skull module promises: fetch once, remember, and never twice at the
 * same time.
 *
 * <p>Item building is not exercised here — {@code ItemStack} cannot be
 * constructed without a real server — so these tests drive the layer that
 * decides <em>whether</em> to fetch, which is where every performance claim
 * and every bug about hammering Mojang lives.
 */
class SkullCacheTest {

    /** A real, valid texture: the standard Steve head. */
    private static final String STEVE = Textures.fromUrl(
            "https://textures.minecraft.net/texture/"
                    + "1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b");

    @TempDir Path folder;

    private Plugin plugin;
    private CountingLookup lookup;

    /** A lookup that answers instantly and counts how often it was asked. */
    private static final class CountingLookup implements Lookup {

        final AtomicInteger idCalls = new AtomicInteger();
        final AtomicInteger textureCalls = new AtomicInteger();

        /** Held so a test can make two callers race on one in-flight lookup. */
        volatile CountDownLatch gate;

        volatile boolean backedOff;
        volatile String answer = STEVE;

        @Override
        public UUID idOf(String name) {
            idCalls.incrementAndGet();
            waitForGate();
            return UUID.nameUUIDFromBytes(name.getBytes());
        }

        @Override
        public String textureOf(UUID id) {
            textureCalls.incrementAndGet();
            waitForGate();
            return answer;
        }

        private void waitForGate() {
            CountDownLatch current = gate;
            if (current != null) {
                try {
                    current.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override public boolean isBackedOff() { return backedOff; }
        @Override public long backoffRemaining() { return backedOff ? 60_000L : 0L; }
        @Override public void clearBackoff() { backedOff = false; }
    }

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        // Lookups happen on another thread, and that is the point of them.
        FakeServer.runAsyncForReal();
        plugin = FakeServer.newPlugin("SkullTest", folder.toFile());
        lookup = new CountingLookup();
        SkullRuntime.installForTests(plugin, lookup,
                new SkullStore(folder.resolve("skull-cache.txt"), plugin.getLogger()));
    }

    @AfterEach
    void tearDown() {
        SkullRuntime.invalidateAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a base64 texture needs nobody: no lookup, ever")
    void textureSourceNeverFetches() {
        SkullSource source = SkullSource.texture(STEVE);

        assertTrue(Skulls.isCached(source));
        assertEquals(STEVE, SkullRuntime.cached(source));
        assertEquals(0, lookup.idCalls.get());
        assertEquals(0, lookup.textureCalls.get());
    }

    @Test
    @DisplayName("a URL is turned into a texture locally, not fetched")
    void urlSourceNeverFetches() {
        SkullSource source = SkullSource.url(
                "https://textures.minecraft.net/texture/abc123");

        String texture = SkullRuntime.cached(source);

        assertNotNull(texture);
        assertEquals("https://textures.minecraft.net/texture/abc123", Textures.urlOf(texture));
        assertEquals(0, lookup.textureCalls.get());
    }

    @Test
    @DisplayName("a bare hash is accepted, because that is what configs carry")
    void bareHashIsAccepted() {
        String texture = SkullRuntime.cached(SkullSource.url("abc123"));

        assertEquals("https://textures.minecraft.net/texture/abc123", Textures.urlOf(texture));
    }

    @Test
    @DisplayName("a player is fetched once and then remembered")
    void playerIsFetchedOnceThenCached() throws Exception {
        SkullSource source = SkullSource.player("Notch");

        assertEquals(STEVE, Skulls.texture(source).get(5, TimeUnit.SECONDS));
        assertEquals(1, lookup.textureCalls.get());

        // Second time: answered from memory, nobody asked.
        assertEquals(STEVE, Skulls.texture(source).get(5, TimeUnit.SECONDS));
        assertEquals(1, lookup.textureCalls.get());
        assertTrue(Skulls.isCached(source));
    }

    @Test
    @DisplayName("forty slots asking for one head make one request")
    void concurrentRequestsCollapseIntoOne() throws Exception {
        SkullSource source = SkullSource.player("Notch");
        // Hold the lookup open so every caller arrives while it is in flight,
        // which is exactly what a menu of forty identical heads does.
        lookup.gate = new CountDownLatch(1);

        List<CompletableFuture<String>> all = java.util.stream.IntStream.range(0, 40)
                .mapToObj(ignored -> Skulls.texture(source))
                .toList();
        lookup.gate.countDown();

        for (CompletableFuture<String> future : all) {
            assertEquals(STEVE, future.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, lookup.textureCalls.get(),
                "one texture request for forty callers");
    }

    @Test
    @DisplayName("a name Mojang does not know is not asked for again")
    void unknownPlayersAreRemembered() throws Exception {
        lookup.answer = null;
        SkullSource source = SkullSource.player("NotARealPlayer");

        assertNull(Skulls.texture(source).get(5, TimeUnit.SECONDS));
        assertNull(Skulls.texture(source).get(5, TimeUnit.SECONDS));

        assertEquals(1, lookup.textureCalls.get(),
                "a typo in a config must not be asked about on every menu open");
    }

    @Test
    @DisplayName("a rate limit is not evidence that a player is fake")
    void backoffDoesNotPoisonTheCache() throws Exception {
        lookup.answer = null;
        lookup.backedOff = true;
        SkullSource source = SkullSource.player("Notch");

        assertNull(Skulls.texture(source).get(5, TimeUnit.SECONDS));

        // The player is real; we were simply not allowed to ask. Once the
        // back-off clears, the next request must try again.
        lookup.backedOff = false;
        lookup.answer = STEVE;
        assertEquals(STEVE, Skulls.texture(source).get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("invalidating a head makes the next request fetch it again")
    void invalidateForcesARefetch() throws Exception {
        SkullSource source = SkullSource.player("Notch");
        Skulls.texture(source).get(5, TimeUnit.SECONDS);
        assertEquals(1, lookup.textureCalls.get());

        Skulls.invalidate(source);

        assertFalse(Skulls.isCached(source));
        Skulls.texture(source).get(5, TimeUnit.SECONDS);
        assertEquals(2, lookup.textureCalls.get());
    }

    @Test
    @DisplayName("warming a menu ahead of time leaves every head ready")
    void warmingResolvesEverythingUpFront() throws Exception {
        List<SkullSource> sources = List.of(
                SkullSource.player("One"),
                SkullSource.player("Two"),
                SkullSource.player("Three"));

        Skulls.warm(sources).get(5, TimeUnit.SECONDS);

        for (SkullSource source : sources) {
            assertTrue(Skulls.isCached(source), source + " should be ready");
        }
        assertEquals(3, lookup.textureCalls.get());
    }

    @Test
    @DisplayName("an id skips the name lookup entirely")
    void idSourceSkipsTheNameRequest() throws Exception {
        SkullSource source = SkullSource.player(UUID.randomUUID());

        Skulls.texture(source).get(5, TimeUnit.SECONDS);

        assertEquals(0, lookup.idCalls.get(), "no name to resolve");
        assertEquals(1, lookup.textureCalls.get());
    }

    @Test
    @DisplayName("stats report what the module is holding")
    void statsReflectReality() throws Exception {
        Skulls.texture(SkullSource.player("Notch")).get(5, TimeUnit.SECONDS);

        Skulls.Stats stats = Skulls.stats();

        assertEquals(1, stats.cached());
        assertEquals(0, stats.pending());
        assertFalse(stats.backedOff());
    }

    @Test
    @DisplayName("the fallback head starts as the library default")
    void fallbackDefaultsToTheLibraryTexture() {
        assertEquals(net.exylia.lib.internal.LibrarySettings.DEFAULT_FALLBACK_HEAD,
                SkullRuntime.fallback());
    }

    @Test
    @DisplayName("a valid configured fallback replaces the default")
    void validFallbackIsAccepted() {
        SkullRuntime.fallback(STEVE);

        assertEquals(STEVE, SkullRuntime.fallback());
    }

    @Test
    @DisplayName("an unreadable fallback keeps the previous one, not a blank head")
    void invalidFallbackKeepsThePreviousOne() {
        SkullRuntime.fallback(STEVE);

        SkullRuntime.fallback("not-a-real-texture");

        assertEquals(net.exylia.lib.internal.LibrarySettings.DEFAULT_FALLBACK_HEAD,
                SkullRuntime.fallback(),
                "an invalid value must fall back to the library default, not stay corrupt");
    }
}
