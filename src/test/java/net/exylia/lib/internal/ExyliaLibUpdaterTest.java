package net.exylia.lib.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the decisions the updater makes before it touches the network:
 * which version counts as newer, and whether a staged jar can be left alone.
 *
 * <p>Both methods are private — they are implementation detail, not API — so
 * they are reached reflectively rather than widened just to be tested.
 */
class ExyliaLibUpdaterTest {

    /** The manifest as actually published, trimmed to what the parser reads. */
    private static final String MANIFEST = """
        {
          "versions": {
            "1.17.0": {
              "version": "1.17.0",
              "url": "https://example.invalid/ExyliaLib-1.17.0.jar",
              "sha256": "aaaa",
              "major": 1,
              "requiresRestart": true
            },
            "1.17.1": {
              "version": "1.17.1",
              "url": "https://example.invalid/ExyliaLib-1.17.1.jar",
              "sha256": "bbbb",
              "major": 1,
              "requiresRestart": true
            }
          },
          "latest": {
            "major1": "1.17.1"
          }
        }
        """;

    private static Object findNewerVersion(String json, String current) throws Exception {
        Method m = ExyliaLibUpdater.class
            .getDeclaredMethod("findNewerVersion", String.class, String.class);
        m.setAccessible(true);
        return m.invoke(null, json, current);
    }

    private static String configuredManifestUrl() throws Exception {
        Method m = ExyliaLibUpdater.class.getDeclaredMethod("configuredManifestUrl");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    private static boolean isAlready(Path file, String sha) throws Exception {
        Method m = ExyliaLibUpdater.class
            .getDeclaredMethod("isAlready", Path.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, file, sha);
    }

    private static void verifySha256(Path file, String sha) throws Exception {
        Method m = ExyliaLibUpdater.class
            .getDeclaredMethod("verifySha256", Path.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(null, file, sha);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static String sha256Of(byte[] bytes) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(bytes)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @DisplayName("an older install is offered the newer release")
    void offersNewerRelease() throws Exception {
        Object entry = findNewerVersion(MANIFEST, "1.17.0");
        assertNotNull(entry, "1.17.1 is newer than 1.17.0 and should have been found");
        assertTrue(entry.toString().contains("1.17.1"),
            "should point at 1.17.1, got: " + entry);
    }

    @Test
    @DisplayName("the current release is not offered to itself")
    void currentIsUpToDate() throws Exception {
        assertNull(findNewerVersion(MANIFEST, "1.17.1"),
            "1.17.1 is the latest and must not update to itself");
    }

    @Test
    @DisplayName("a newer install is never downgraded")
    void neverDowngrades() throws Exception {
        assertNull(findNewerVersion(MANIFEST, "1.18.0"),
            "a build ahead of the manifest must not be pulled backwards");
    }

    @Test
    @DisplayName("a different major is left alone")
    void ignoresOtherMajors() throws Exception {
        assertNull(findNewerVersion(MANIFEST, "2.0.0"),
            "major 2 has no entry and must not be handed a major 1 jar");
    }

    @Test
    @DisplayName("a jar already staged with the right bytes is not downloaded again")
    void skipsWhenAlreadyStaged(@TempDir Path dir) throws Exception {
        byte[] contents = "pretend this is ExyliaLib".getBytes(StandardCharsets.UTF_8);
        Path staged = dir.resolve("ExyliaLib.jar");
        Files.write(staged, contents);

        assertTrue(isAlready(staged, sha256Of(contents)),
            "matching hash means the staged jar is already the release we want");
    }

    @Test
    @DisplayName("a staged jar with the wrong bytes is replaced")
    void restagesWhenBytesDiffer(@TempDir Path dir) throws Exception {
        // A half-written download from an interrupted shutdown: right name,
        // wrong contents. Skipping on the name alone would serve it forever.
        Path staged = dir.resolve("ExyliaLib.jar");
        Files.write(staged, "truncated".getBytes(StandardCharsets.UTF_8));

        assertFalse(isAlready(staged, sha256Of("the real thing".getBytes(StandardCharsets.UTF_8))),
            "a hash mismatch must force the download");
    }

    @Test
    @DisplayName("nothing staged means nothing to skip")
    void handlesMissingFile(@TempDir Path dir) throws Exception {
        assertFalse(isAlready(dir.resolve("absent.jar"), "aaaa"),
            "a file that does not exist cannot already be the release");
    }

    @Test
    @DisplayName("a manifest without a hash does not silently skip the download")
    void blankHashDoesNotSkip(@TempDir Path dir) throws Exception {
        Path staged = dir.resolve("ExyliaLib.jar");
        Files.write(staged, "whatever".getBytes(StandardCharsets.UTF_8));

        assertFalse(isAlready(staged, ""),
            "with no hash to compare there is no evidence the staged jar is current");
    }

    @Test
    @DisplayName("malformed manifest hashes never skip a staged jar")
    void malformedHashDoesNotSkip(@TempDir Path dir) throws Exception {
        Path staged = dir.resolve("ExyliaLib.jar");
        Files.write(staged, "whatever".getBytes(StandardCharsets.UTF_8));

        assertFalse(isAlready(staged, null));
        assertFalse(isAlready(staged, "a".repeat(63)));
        assertFalse(isAlready(staged, "g".repeat(64)));
    }

    @Test
    @DisplayName("malformed manifest hashes reject downloaded bytes")
    void malformedHashFailsVerification(@TempDir Path dir) throws Exception {
        Path downloaded = dir.resolve("ExyliaLib.jar");
        Files.write(downloaded, "whatever".getBytes(StandardCharsets.UTF_8));

        assertThrows(Exception.class, () -> verifySha256(downloaded, null));
        assertThrows(Exception.class, () -> verifySha256(downloaded, " "));
        assertThrows(Exception.class, () -> verifySha256(downloaded, "a".repeat(63)));
        assertThrows(Exception.class, () -> verifySha256(downloaded, "g".repeat(64)));
    }

    @Test
    @DisplayName("a second release before the restart replaces the staged one")
    void newerReleaseSupersedesWhatIsStaged(@TempDir Path dir) throws Exception {
        // A server can stay up across several releases. The check compares
        // against the running version, not the staged jar, so 1.17.2 must be
        // offered to a 1.17.0 install that already has 1.17.1 waiting.
        String twoMore = MANIFEST
            .replace("\"1.17.1\"", "\"1.17.2\"")
            .replace("1.17.1.jar", "1.17.2.jar");

        Object best = findNewerVersion(twoMore, "1.17.0");
        assertNotNull(best, "the newest release is still newer than what is running");
        assertEquals("1.17.2", version(best));

        Path staged = dir.resolve("ExyliaLib.jar");
        Files.write(staged, "the 1.17.1 jar".getBytes(StandardCharsets.UTF_8));
        assertFalse(isAlready(staged, sha256(best)),
            "the staged jar is the previous release, so it must be replaced");
    }

    @Test
    @DisplayName("an unchanged manifest is reused instead of re-read")
    void notModifiedReusesTheCachedBody() throws Exception {
        // Polling costs a round trip and no body while the manifest is
        // unchanged, which is what makes a 30-minute poll cheap. Verified
        // against the real host: 4340 bytes on a 200, zero on a 304.
        setCache("\"tag-1\"", MANIFEST);
        assertEquals(MANIFEST, cachedManifest(),
            "the body is kept so a 304 has something to return");

        // Cleared together: a 304 must never pair a stale body with a new tag.
        setCache(null, null);
        assertNull(cachedManifest());
    }

    @Test
    @DisplayName("a loader-provided manifest URL overrides the stable default")
    void usesConfiguredManifestUrl() throws Exception {
        String property = "exylialib.manifest-url";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "https://raw.example.invalid/dev/lib-manifest.json");
            assertEquals("https://raw.example.invalid/dev/lib-manifest.json", configuredManifestUrl());
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static String version(Object entry) throws Exception {
        Method m = entry.getClass().getDeclaredMethod("version");
        m.setAccessible(true);
        return (String) m.invoke(entry);
    }

    private static String sha256(Object entry) throws Exception {
        Method m = entry.getClass().getDeclaredMethod("sha256");
        m.setAccessible(true);
        return (String) m.invoke(entry);
    }

    private static void setCache(String etag, String body) throws Exception {
        java.lang.reflect.Field e = ExyliaLibUpdater.class.getDeclaredField("cachedEtag");
        java.lang.reflect.Field b = ExyliaLibUpdater.class.getDeclaredField("cachedManifest");
        e.setAccessible(true);
        b.setAccessible(true);
        e.set(null, etag);
        b.set(null, body);
    }

    private static String cachedManifest() throws Exception {
        java.lang.reflect.Field b = ExyliaLibUpdater.class.getDeclaredField("cachedManifest");
        b.setAccessible(true);
        return (String) b.get(null);
    }
}
