package net.exylia.lib.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the decisions the updater makes before it touches the network: which
 * release the {@code latest} link names, which version counts as newer, and
 * whether a staged jar can be left alone.
 *
 * <p>All three are private — implementation detail, not API — so they are
 * reached reflectively rather than widened just to be tested.
 */
class ExyliaLibUpdaterTest {

    private static String versionFromLocation(String location) throws Exception {
        Method m = ExyliaLibUpdater.class
            .getDeclaredMethod("versionFromLocation", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, location);
    }

    private static boolean isNewer(String candidate, String current) throws Exception {
        Method m = ExyliaLibUpdater.class
            .getDeclaredMethod("isNewer", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, candidate, current);
    }

    private static boolean isAlready(Path file, String version) throws Exception {
        Method m = ExyliaLibUpdater.class
            .getDeclaredMethod("isAlready", Path.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, file, version);
    }

    /** Writes a jar that declares {@code version} the way the build does. */
    private static Path jar(Path dir, String name, String version) throws Exception {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("plugin.yml"));
            zip.write(("name: ExyliaLib\nversion: '" + version + "'\nmain: net.exylia.lib.ExyliaLib\n")
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    @DisplayName("the release behind the latest link is read from the redirect")
    void readsVersionFromRedirect() throws Exception {
        assertEquals("1.64.3", versionFromLocation(
            "https://github.com/DiGround-s/ExyliaLib/releases/download/v1.64.3/ExyliaLib.jar"));
    }

    @Test
    @DisplayName("a redirect that is not a release download names no version")
    void rejectsUnrelatedRedirect() throws Exception {
        assertNull(versionFromLocation("https://github.com/login?return_to=%2FDiGround-s"));
        assertNull(versionFromLocation(null));
        // A tag that is not a plain X.Y.Z is not a stable release.
        assertNull(versionFromLocation(
            "https://github.com/DiGround-s/ExyliaLib/releases/download/dev-v1.64.3/ExyliaLib.jar"));
    }

    @Test
    @DisplayName("an older install is offered the newer release")
    void offersNewerRelease() throws Exception {
        assertTrue(isNewer("1.17.1", "1.17.0"));
        assertTrue(isNewer("1.18.0", "1.17.9"));
        // The major is deliberately not a gate: a server runs whatever is newest.
        assertTrue(isNewer("2.0.0", "1.64.3"));
    }

    @Test
    @DisplayName("the current release is not offered to itself")
    void currentIsUpToDate() throws Exception {
        assertFalse(isNewer("1.17.1", "1.17.1"));
    }

    @Test
    @DisplayName("a newer install is never downgraded")
    void neverDowngrades() throws Exception {
        // What a developer running a local build ahead of the newest release has.
        assertFalse(isNewer("1.17.1", "1.18.0"));
    }

    @Test
    @DisplayName("an unreadable release version is never installed")
    void rejectsUnparsableRelease() throws Exception {
        assertFalse(isNewer(null, "1.17.0"));
        assertFalse(isNewer("", "1.17.0"));
        assertFalse(isNewer("nightly", "1.17.0"));
    }

    @Test
    @DisplayName("a jar already staged for that release is not downloaded again")
    void skipsWhenAlreadyStaged(@TempDir Path dir) throws Exception {
        assertTrue(isAlready(jar(dir, "ExyliaLib.jar", "1.17.1"), "1.17.1"));
    }

    @Test
    @DisplayName("a second release before the restart replaces the staged one")
    void newerReleaseSupersedesWhatIsStaged(@TempDir Path dir) throws Exception {
        // A server can stay up across several releases. The check compares
        // against the version running, not against the jar already staged, so
        // 1.17.2 must be offered to a 1.17.0 install that already has 1.17.1
        // waiting — and must overwrite it.
        assertTrue(isNewer("1.17.2", "1.17.0"));
        assertFalse(isAlready(jar(dir, "ExyliaLib.jar", "1.17.1"), "1.17.2"));
    }

    @Test
    @DisplayName("a half-written jar is replaced rather than served")
    void restagesWhenTruncated(@TempDir Path dir) throws Exception {
        // An interrupted download from a shutdown pass: right name, not a
        // readable jar. Skipping on the name alone would serve it forever.
        Path staged = dir.resolve("ExyliaLib.jar");
        Files.write(staged, "truncated".getBytes(StandardCharsets.UTF_8));

        assertFalse(isAlready(staged, "1.17.1"));
    }

    @Test
    @DisplayName("a jar without a plugin.yml is not the release")
    void restagesWhenNotAPlugin(@TempDir Path dir) throws Exception {
        Path staged = dir.resolve("ExyliaLib.jar");
        try (OutputStream out = Files.newOutputStream(staged);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertFalse(isAlready(staged, "1.17.1"));
    }

    @Test
    @DisplayName("nothing staged means nothing to skip")
    void handlesMissingFile(@TempDir Path dir) throws Exception {
        assertFalse(isAlready(dir.resolve("absent.jar"), "1.17.1"));
    }

    @Test
    @DisplayName("no expected version never skips a download")
    void blankVersionDoesNotSkip(@TempDir Path dir) throws Exception {
        assertFalse(isAlready(jar(dir, "ExyliaLib.jar", "1.17.1"), null));
    }
}
