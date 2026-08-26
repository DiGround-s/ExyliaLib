package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Checks for a newer version of ExyliaLib after the server has started,
 * downloads it and writes it to {@code plugins/update/} so the server applies
 * it on the next restart.
 *
 * <p>This runs asynchronously — it never blocks the main thread. Everything
 * it reads comes from one URL: {@code releases/latest/download/ExyliaLib.jar},
 * which every release publishes under that fixed name. Asking for it without
 * following the redirect answers with the release it currently points at, so
 * the newest version is read from the {@code Location} header without
 * downloading anything.
 *
 * <p>The update is gated by {@code auto-update: true} in
 * {@code plugins/ExyliaLib/config.yml}. If that file is absent, the
 * default is {@code true} and the file is generated on first run.
 *
 * @since 1.6.0
 */
public final class ExyliaLibUpdater {

    /**
     * The download that always names the newest release.
     *
     * <p>Resolved by GitHub per request, so a release is visible the moment it
     * exists — unlike a file on the default branch, which is served by a CDN
     * that holds it for five minutes.
     */
    private static final String LATEST_JAR_URL =
        "https://github.com/DiGround-s/ExyliaLib/releases/latest/download/ExyliaLib.jar";

    /** Pulls {@code 1.64.3} out of {@code .../releases/download/v1.64.3/ExyliaLib.jar}. */
    private static final Pattern RELEASE_IN_LOCATION =
        Pattern.compile("/releases/download/v(\\d+\\.\\d+\\.\\d+)/");

    private static final int TIMEOUT_MS = 15_000;

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
    public static UpdateOutcome checkForUpdate(ExyliaLib plugin) {
        LibrarySettings settings = LibrarySettings.load(plugin);

        if (!settings.autoUpdate()) {
            plugin.getLogger().fine("Auto-update is disabled — skipping update check.");
            return new UpdateOutcome(UpdateStatus.DISABLED, version(plugin), null);
        }

        return stageNow(plugin);
    }

    /**
     * Checks and stages regardless of {@code auto-update}.
     *
     * <p>What {@code /exylialib update} runs. The setting governs the passes
     * nobody asked for — startup, shutdown, the poll — and an admin typing the
     * command has asked. Nothing else differs: the same release, the same
     * checks, the same staged jar applied on the next restart.
     *
     * @param plugin the ExyliaLib plugin instance
     * @return what happened, for a caller that has somebody to tell
     * @since 1.65.0
     */
    public static UpdateOutcome stageNow(ExyliaLib plugin) {
        String currentVersion = version(plugin);

        Release latest;
        try {
            latest = resolveLatest();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                "Could not check for ExyliaLib updates: " + e.getMessage());
            return new UpdateOutcome(UpdateStatus.FAILED, currentVersion, e.getMessage());
        }

        if (!isNewer(latest.version, currentVersion)) {
            plugin.getLogger().fine("ExyliaLib " + currentVersion + " is up to date.");
            return new UpdateOutcome(UpdateStatus.UP_TO_DATE, currentVersion, null);
        }

        try {
            Path updateDir = updateDir();
            Files.createDirectories(updateDir);
            Path dest = updateDir.resolve("ExyliaLib.jar");

            // Startup already staged this one, or a previous shutdown did.
            // Downloading it again would cost the admin a slower stop for a
            // file that is already the release we would fetch.
            //
            // Checked before announcing anything: on a poll this is the usual
            // outcome, and a line every half hour about a jar that has not
            // moved is noise the console does not need.
            if (isAlready(dest, latest.version)) {
                plugin.getLogger().fine(String.format(
                    "ExyliaLib %s is already staged — will be applied on next restart.",
                    latest.version));
                return new UpdateOutcome(UpdateStatus.ALREADY_STAGED, latest.version, null);
            }

            plugin.getLogger().info(String.format(
                "ExyliaLib %s is available (current: %s). Downloading...",
                latest.version, currentVersion));

            downloadWithVerification(latest, dest);
            plugin.getLogger().info(String.format(
                "ExyliaLib %s ready — will be applied on next restart.", latest.version));
            return new UpdateOutcome(UpdateStatus.STAGED, latest.version, null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                "Failed to download ExyliaLib " + latest.version + ": " + e.getMessage(), e);
            return new UpdateOutcome(UpdateStatus.FAILED, currentVersion, e.getMessage());
        }
    }

    /**
     * What an update check found.
     *
     * @param status  what happened
     * @param version the version the status is about: the one running for
     *                everything except a staged jar, which names the new one
     * @param detail  why it failed, or {@code null} when it did not
     * @since 1.65.0
     */
    public record UpdateOutcome(UpdateStatus status, String version, String detail) {}

    /** The outcomes an update check has. */
    public enum UpdateStatus {
        /** {@code auto-update} is off, so the automatic passes did nothing. */
        DISABLED,
        /** Nothing newer has been released. */
        UP_TO_DATE,
        /** The newer jar was already sitting in the update folder. */
        ALREADY_STAGED,
        /** The newer jar was downloaded, verified and staged. */
        STAGED,
        /** The release or the download could not be read. */
        FAILED
    }

    /**
     * Returns whether {@code file} already holds exactly the expected release.
     *
     * <p>Reads the version out of the jar rather than trusting the file name: a
     * staged jar from an interrupted download carries the right name and is not
     * a readable jar at all, and skipping on name alone would keep serving it
     * forever.
     */
    private static boolean isAlready(Path file, String expectedVersion) {
        return expectedVersion != null && expectedVersion.equals(jarVersion(file));
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

    /**
     * Asks GitHub which release {@code latest} points at, without downloading
     * it.
     *
     * <p>A {@code HEAD} that does not follow the redirect costs a round trip
     * and no body, and neither touches the API nor its 60-requests-per-hour
     * limit. The versioned URL it answers with is what the download then uses:
     * a release published between these two calls cannot swap the bytes
     * underneath a download that is already running.
     */
    private static Release resolveLatest() throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(LATEST_JAR_URL).toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "ExyliaLib-Updater/1.0");

            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if (code / 100 != 3 || location == null) {
                throw new IOException("Latest release lookup returned HTTP " + code);
            }

            String version = versionFromLocation(location);
            if (version == null) {
                throw new IOException("Could not read a version out of " + location);
            }
            return new Release(version, location);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Failed to reach " + LATEST_JAR_URL + ": " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Reads the release version out of the URL {@code latest} redirects to, or
     * {@code null} when it is not a release download at all.
     */
    private static String versionFromLocation(String location) {
        if (location == null) return null;
        Matcher matcher = RELEASE_IN_LOCATION.matcher(location);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Reads the version a jar declares in its {@code plugin.yml}, or
     * {@code null} when the file is absent, unreadable, or not an ExyliaLib
     * jar.
     */
    private static String jarVersion(Path jar) {
        if (!Files.isRegularFile(jar)) return null;
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry("plugin.yml");
            if (entry == null) return null;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("version:")) continue;
                    String value = line.substring("version:".length()).trim();
                    if (value.length() >= 2
                        && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                        && value.charAt(value.length() - 1) == value.charAt(0)) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value.isBlank() ? null : value;
                }
            }
            return null;
        } catch (IOException e) {
            // Truncated, half-written, or not a zip: not the release we want.
            return null;
        }
    }

    private static void downloadWithVerification(Release release, Path dest) throws IOException {
        Path tmp = Files.createTempFile(dest.getParent(), "ExyliaLib", ".tmp");
        try {
            downloadFile(release.url, tmp);

            // What a hash used to cover: a download cut short by a dropped
            // connection is not a readable jar, and one that somehow answered
            // with a different release does not declare this version. Both
            // leave the staged jar untouched instead of handing the server a
            // library it cannot load.
            String downloaded = jarVersion(tmp);
            if (!release.version.equals(downloaded)) {
                throw new IOException(downloaded == null
                    ? "The download is not a readable ExyliaLib jar"
                    : "The download declares " + downloaded + ", expected " + release.version);
            }

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

    // --- semver helpers ---

    /**
     * Returns whether {@code candidate} is a release worth installing over
     * {@code current}.
     *
     * <p>Only the direction is checked, not the major: a server runs whatever
     * is newest. What it does refuse is going backwards — a build ahead of the
     * newest release, which is what a developer running a local jar has, is
     * left alone rather than pulled back to it.
     */
    private static boolean isNewer(String candidate, String current) {
        int[] a = parseTriple(candidate);
        int[] b = parseTriple(current);
        if (a == null) return false;
        if (b == null) return true;
        return compareSemver(a, b) > 0;
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

    // --- the release `latest` currently points at ---

    private record Release(String version, String url) {}
}
