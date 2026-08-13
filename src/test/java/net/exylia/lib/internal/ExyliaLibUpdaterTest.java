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

    private static boolean isAlready(Path file, String sha) throws Exception {
        Method m = ExyliaLibUpdater.class
            .getDeclaredMethod("isAlready", Path.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, file, sha);
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
}
