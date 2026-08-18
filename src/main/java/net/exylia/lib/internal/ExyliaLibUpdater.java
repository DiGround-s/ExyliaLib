package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import org.bukkit.Bukkit;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;

/**
 * Checks for a newer version of ExyliaLib after the server has started,
 * downloads it if the major matches, and writes it to
 * {@code plugins/update/} so the server applies it on the next restart.
 *
 * <p>This runs asynchronously — it never blocks the main thread. The
 * manifest is fetched over HTTPS; the downloaded JAR is verified against
 * its SHA-256 from the manifest before being written.
 *
 * <p>The update is gated by {@code auto-update: true} in
 * {@code plugins/ExyliaLib/config.yml}. If that file is absent, the
 * default is {@code true} and the file is generated on first run.
 *
 * @since 1.6.0
 */
public final class ExyliaLibUpdater {

    /**
     * Where the version manifest lives.
     *
     * <p>Served straight from the repository's default branch rather than a
     * site: it updates with the same push that publishes the release, needs no
     * hosting to keep alive, and sits on the same domain as the release assets
     * it points at. The loader reads the same URL.
     */
    private static final String MANIFEST_URL =
        "https://raw.githubusercontent.com/DiGround-s/ExyliaLib/main/lib-manifest.json";
    private static final int TIMEOUT_MS = 15_000;

    /**
     * The manifest's ETag as of the last successful fetch, and the body that
     * came with it.
     *
     * <p>Polling every half hour would otherwise re-download a file that
     * changes a few times a month. The host answers a conditional request with
     * 304 and no body, so an unchanged manifest costs a round trip and zero
     * bytes — measured: 4340 bytes against 0.
     *
     * <p>Written and read only from the updater thread, which runs one check at
     * a time; volatile so the shutdown pass on the main thread sees them.
     */
    private static volatile String cachedEtag;
    private static volatile String cachedManifest;

    private ExyliaLibUpdater() {
        throw new AssertionError("No instances.");
    }

    /**
     * Checks for an update and stages it if one is available.
     *
     * <p>Run on shutdown as well as on startup. The server applies
     * {@code plugins/update/} while it is discovering plugins, before it loads
     * any of them, so a jar staged during shutdown is picked up by the very
     * next start: one restart, not two. The startup pass stays as a safety net
     * for servers that are killed rather than stopped, where {@code onDisable}
     * never runs.
     *
     * @param plugin the ExyliaLib plugin instance
     */
    public static void checkForUpdate(ExyliaLib plugin) {
        LibrarySettings settings = LibrarySettings.load(plugin);

        if (!settings.autoUpdate()) {
            plugin.getLogger().fine("Auto-update is disabled — skipping update check.");
            return;
        }

        String currentVersion = version(plugin);

        // Fetch the manifest over HTTPS
        String manifestJson;
        try {
            manifestJson = fetchString(MANIFEST_URL);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                "Could not check for ExyliaLib updates: " + e.getMessage());
            return;
        }

        ManifestEntry best = findNewerVersion(manifestJson, currentVersion);
        if (best == null) {
            plugin.getLogger().fine("ExyliaLib " + currentVersion + " is up to date.");
            return;
        }

        try {
            Path updateDir = updateDir();
            Files.createDirectories(updateDir);
            Path dest = updateDir.resolve("ExyliaLib.jar");

            // Startup already staged this one, or a previous shutdown did.
            // Downloading it again would cost the admin a slower stop for a
            // file that is byte for byte what is already sitting there.
            //
            // Checked before announcing anything: on a poll this is the usual
            // outcome, and a line every half hour about a jar that has not
            // moved is noise the console does not need.
            if (isAlready(dest, best.sha256)) {
                plugin.getLogger().fine(String.format(
                    "ExyliaLib %s is already staged — will be applied on next restart.",
                    best.version));
                return;
            }

            plugin.getLogger().info(String.format(
                "ExyliaLib %s is available (current: %s). Downloading...",
                best.version, currentVersion));

            downloadWithVerification(best, dest, plugin);
            plugin.getLogger().info(String.format(
                "ExyliaLib %s ready — will be applied on next restart.", best.version));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                "Failed to download ExyliaLib " + best.version + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns whether {@code file} already holds exactly the expected release.
     *
     * <p>Compares the hash rather than trusting the file name: a staged jar
     * from an interrupted download would carry the right name and the wrong
     * bytes, and skipping on name alone would keep serving it forever.
     */
    private static boolean isAlready(Path file, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) return false;
        if (!Files.isRegularFile(file)) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expectedSha256);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 required", e);
        } catch (IOException e) {
            return false;
        }
    }

    // ---- internal ----

    @SuppressWarnings("deprecation")
    private static String version(ExyliaLib plugin) {
        return plugin.getDescription().getVersion();
    }

    /**
     * Returns the {@code plugins/update/} directory, respecting the
     * {@code settings.update-folder} in {@code bukkit.yml}.
     */
    private static Path updateDir() {
        // Bukkit.getUpdateFolderFile() returns the canonical update directory.
        // It respects the server's configured update folder name.
        File updateFolder = Bukkit.getUpdateFolderFile();
        return updateFolder.toPath();
    }

    private static String fetchString(String urlStr) throws IOException {
        try {
            URI uri = new URI(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "ExyliaLib-Updater/1.0");

            String etag = cachedEtag;
            String body = cachedManifest;
            if (etag != null && body != null) {
                conn.setRequestProperty("If-None-Match", etag);
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && body != null) {
                return body;
            }
            if (code != 200) {
                throw new IOException("Manifest fetch returned HTTP " + code);
            }

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                String fetched = out.toString(StandardCharsets.UTF_8);
                // Cached together so a 304 can never pair an old body with a
                // new tag: either both are replaced or neither is.
                cachedManifest = fetched;
                cachedEtag = conn.getHeaderField("ETag");
                return fetched;
            }
        } catch (Exception e) {
            throw new IOException("Failed to fetch " + urlStr + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the manifest and returns the best version newer than
     * {@code current} within the same major.
     */
    private static ManifestEntry findNewerVersion(String json, String current) {
        // Manual parsing to avoid depending on Gson at runtime.
        // The manifest format is simple enough for string-based extraction.
        int currentMajor = majorOf(current);
        int[] currentTriple = parseTriple(current);

        String bestVersion = null;
        String bestUrl = null;
        String bestSha256 = null;
        int[] bestTriple = null;

        // Find the "latest" marker for this major
        String latestKey = "\"major" + currentMajor + "\"";
        int latestIdx = json.indexOf(latestKey);
        if (latestIdx < 0) return null;

        // Extract the latest version string for this major
        int colonIdx = json.indexOf(':', latestIdx + latestKey.length());
        if (colonIdx < 0) return null;
        int openQuote = json.indexOf('"', colonIdx + 1);
        if (openQuote < 0) return null;
        int closeQuote = json.indexOf('"', openQuote + 1);
        if (closeQuote < 0) return null;
        String latestVersion = json.substring(openQuote + 1, closeQuote);

        // Is it actually newer?
        int[] latestTriple = parseTriple(latestVersion);
        if (latestTriple == null) return null;
        if (compareSemver(latestTriple, currentTriple) <= 0) return null;

        // Find the version entry in the "versions" block
        String search = "\"" + latestVersion + "\"";
        int versionIdx = json.indexOf(search);
        if (versionIdx < 0) return null;

        // Extract URL
        String urlKey = "\"url\"";
        int urlKeyIdx = json.indexOf(urlKey, versionIdx);
        if (urlKeyIdx < 0) return null;
        int urlOpenQuote = json.indexOf('"', urlKeyIdx + urlKey.length());
        if (urlOpenQuote < 0) return null;
        int urlCloseQuote = json.indexOf('"', urlOpenQuote + 1);
        if (urlCloseQuote < 0) return null;
        String url = json.substring(urlOpenQuote + 1, urlCloseQuote);

        // Extract SHA-256
        String shaKey = "\"sha256\"";
        int shaKeyIdx = json.indexOf(shaKey, versionIdx);
        if (shaKeyIdx < 0) return null;
        int shaOpenQuote = json.indexOf('"', shaKeyIdx + shaKey.length());
        if (shaOpenQuote < 0) return null;
        int shaCloseQuote = json.indexOf('"', shaOpenQuote + 1);
        if (shaCloseQuote < 0) return null;
        String sha256 = json.substring(shaOpenQuote + 1, shaCloseQuote);

        return new ManifestEntry(latestVersion, url, sha256);
    }

    private static void downloadWithVerification(ManifestEntry entry, Path dest,
                                                  ExyliaLib plugin) throws IOException {
        Path tmp = Files.createTempFile(dest.getParent(), "ExyliaLib", ".tmp");
        try {
            downloadFile(entry.url, tmp);
            verifySha256(tmp, entry.sha256);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING,
                           StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static void downloadFile(String urlStr, Path dest) throws IOException {
        try {
            URI uri = new URI(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Accept", "application/java-archive");
            conn.setRequestProperty("User-Agent", "ExyliaLib-Updater/1.0");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Download returned HTTP " + code + " for " + urlStr);
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Files.deleteIfExists(dest);
            throw new IOException("Failed to download from " + urlStr + ": " + e.getMessage(), e);
        }
    }

    private static void verifySha256(Path file, String expected) throws IOException {
        if (expected == null || expected.isBlank()) return;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            String actual = sb.toString();
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(String.format(
                    "SHA-256 mismatch.%nExpected: %s%nGot:      %s", expected, actual));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 required", e);
        }
    }

    // --- semver helpers ---

    private static int majorOf(String v) {
        int dot = v.indexOf('.');
        if (dot <= 0) return 0;
        try { return Integer.parseInt(v.substring(0, dot)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int[] parseTriple(String v) {
        if (v == null || v.isBlank()) return null;
        String[] parts = v.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[]{
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                parts.length >= 3 ? Integer.parseInt(parts[2]) : 0
            };
        } catch (NumberFormatException e) { return null; }
    }

    private static int compareSemver(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int diff = a[i] - b[i];
            if (diff != 0) return diff;
        }
        return 0;
    }

    // --- manifest entry ---

    private record ManifestEntry(String version, String url, String sha256) {}
}
