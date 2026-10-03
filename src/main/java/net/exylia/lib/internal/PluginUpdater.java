package net.exylia.lib.internal;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Keeps the other Exylia plugins up to date, the way {@link ExyliaLibUpdater} keeps the library.
 *
 * <h2>What a plugin declares</h2>
 * Two keys in its own {@code plugin.yml}, and no code:
 * <pre>{@code
 * exylia-updates: Exylia-Plugins/ExyliaMines   # the GitHub repository its releases are in
 * exylia-lib: '1.230.0'                        # the oldest ExyliaLib the build runs on
 * }</pre>
 * Every release of that repository attaches {@code <Name>.jar}, the plugin under a name that
 * never changes, so {@code releases/latest/download/<Name>.jar} always names the newest one and
 * its redirect says which version that is without downloading anything.
 *
 * <h2>What is checked before a jar is staged</h2>
 * <ul>
 *   <li>The repository's owner is one the settings trust, so a plugin.yml cannot point the
 *       server at somebody else's code.</li>
 *   <li>The release is newer, and of the same major version unless the owner allows majors: a
 *       major release may change configs or commands, and that is a decision, not a download.</li>
 *   <li>The download is a readable jar declaring the same plugin name and the release's version.</li>
 *   <li>It runs on the ExyliaLib that will be there after the restart: the running one, or a
 *       newer one already staged. One that needs more is held back until the library catches up.</li>
 * </ul>
 * A staged jar takes the file name of the jar the server loaded, which is how the server's update
 * folder matches it, and replaces it on the next start.
 *
 * <p>Runs off the main thread at startup and on a timer, and inline while the server stops, the
 * same passes as the library's own update, so updating everything takes a single restart.
 *
 * @since 1.233.0
 */
public final class PluginUpdater {

    /** The plugin.yml key naming the repository. */
    public static final String REPOSITORY_KEY = "exylia-updates";
    /** The plugin.yml key naming the oldest ExyliaLib a build runs on. */
    public static final String LIBRARY_KEY = "exylia-lib";

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");
    private static final Pattern RELEASE_IN_LOCATION = Pattern.compile("/releases/download/v(\\d+\\.\\d+\\.\\d+)/");
    private static final int MOST_REDIRECTS = 3;
    private static final int TIMEOUT_MS = 15_000;

    /** The last answer for each plugin, by name: what the notice and the command report. */
    private static final Map<String, Outcome> LAST = new ConcurrentHashMap<>();

    /** What a check of one plugin found. */
    public enum Status {
        UP_TO_DATE,
        STAGED,
        ALREADY_STAGED,
        /** A newer major release, left for the owner to install by hand. */
        MAJOR_HELD,
        /** A release that needs a newer ExyliaLib than the one that will be running. */
        NEEDS_LIBRARY,
        SKIPPED,
        FAILED
    }

    /**
     * What a check of one plugin found.
     *
     * @param plugin  the plugin's name
     * @param current the version running
     * @param latest  the newest release, or {@code null} when it could not be read
     * @param detail  why it failed or was held back, or {@code null}
     */
    public record Outcome(@NotNull String plugin, @NotNull String current, @Nullable String latest,
                          @NotNull Status status, @Nullable String detail) {
    }

    private PluginUpdater() {
        throw new AssertionError("No instances.");
    }

    /** Checks every plugin that declares a repository, if the settings allow it. */
    public static @NotNull List<Outcome> checkAll(@NotNull Plugin library) {
        LibrarySettings settings = LibrarySettings.get();
        LibrarySettings.PluginUpdates updates = settings == null ? new LibrarySettings.PluginUpdates() : settings.pluginUpdates();
        if (!updates.enabled()) return List.of();
        return stageAll(library, updates);
    }

    /** Checks every plugin that declares a repository, whatever the settings say about running on their own. */
    public static @NotNull List<Outcome> stageAll(@NotNull Plugin library, @NotNull LibrarySettings.PluginUpdates updates) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            String repository = repository(plugin);
            if (repository == null) continue;
            Outcome outcome = check(library, plugin, repository, updates);
            LAST.put(plugin.getName(), outcome);
            outcomes.add(outcome);
        }
        return outcomes;
    }

    /** The last answer for every plugin checked, sorted by name. */
    public static @NotNull List<Outcome> last() {
        return LAST.values().stream().sorted((a, b) -> a.plugin().compareToIgnoreCase(b.plugin())).toList();
    }

    /** How many plugins have an update waiting for the next restart. */
    public static int staged() {
        return (int) LAST.values().stream()
                .filter(outcome -> outcome.status() == Status.STAGED || outcome.status() == Status.ALREADY_STAGED)
                .count();
    }

    private static Outcome check(Plugin library, Plugin plugin, String repository,
                                 LibrarySettings.PluginUpdates updates) {
        String name = plugin.getName();
        @SuppressWarnings("deprecation")
        String current = plugin.getDescription().getVersion();
        if (updates.skip().stream().anyMatch(name::equalsIgnoreCase)) {
            return new Outcome(name, current, null, Status.SKIPPED, "turned off in plugin-updates.skip");
        }
        String owner = repository.substring(0, repository.indexOf('/'));
        if (updates.owners().stream().noneMatch(owner::equalsIgnoreCase)) {
            return new Outcome(name, current, null, Status.SKIPPED, owner + " is not in plugin-updates.owners");
        }
        Path installed = jarOf(plugin);
        if (installed == null) {
            return new Outcome(name, current, null, Status.SKIPPED, "not loaded from a jar in plugins/");
        }

        String latest;
        String url;
        try {
            String[] release = latest(repository, name);
            latest = release[0];
            url = release[1];
        } catch (IOException failure) {
            library.getLogger().warning("Could not check for " + name + " updates: " + failure.getMessage());
            return new Outcome(name, current, null, Status.FAILED, failure.getMessage());
        }
        if (!isNewer(latest, current)) return new Outcome(name, current, latest, Status.UP_TO_DATE, null);
        if (!updates.majors() && major(latest) > major(current)) {
            library.getLogger().info(name + " " + latest + " is a new major version; install it by hand when ready"
                    + " (plugin-updates.majors in plugins/ExyliaLib/config.yml installs majors too).");
            return new Outcome(name, current, latest, Status.MAJOR_HELD, null);
        }

        try {
            Path updateDir = Bukkit.getUpdateFolderFile().toPath();
            Files.createDirectories(updateDir);
            Path dest = updateDir.resolve(installed.getFileName().toString());
            if (latest.equals(describe(dest).version())) {
                return new Outcome(name, current, latest, Status.ALREADY_STAGED, null);
            }
            Path tmp = Files.createTempFile(updateDir, name, ".tmp");
            try {
                download(url, tmp);
                Descriptor downloaded = describe(tmp);
                if (!name.equals(downloaded.name()) || !latest.equals(downloaded.version())) {
                    throw new IOException("the download is " + downloaded.name() + " " + downloaded.version()
                            + ", expected " + name + " " + latest);
                }
                String afterRestart = libraryAfterRestart(library);
                if (downloaded.library() != null && isNewer(downloaded.library(), afterRestart)) {
                    library.getLogger().info(name + " " + latest + " needs ExyliaLib " + downloaded.library()
                            + "; it will be installed once ExyliaLib is updated.");
                    return new Outcome(name, current, latest, Status.NEEDS_LIBRARY, "needs ExyliaLib " + downloaded.library());
                }
                move(tmp, dest);
            } finally {
                Files.deleteIfExists(tmp);
            }
            library.getLogger().info(name + " " + latest + " ready (current: " + current + ") — applied on the next restart.");
            return new Outcome(name, current, latest, Status.STAGED, null);
        } catch (IOException | RuntimeException failure) {
            library.getLogger().log(Level.WARNING, "Failed to update " + name + " to " + latest + ": " + failure.getMessage());
            return new Outcome(name, current, latest, Status.FAILED, failure.getMessage());
        }
    }

    /** The repository a plugin names in its plugin.yml, or {@code null}. */
    static @Nullable String repository(Plugin plugin) {
        try (InputStream in = plugin.getResource("plugin.yml")) {
            if (in == null) return null;
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            String repository = yaml.getString(REPOSITORY_KEY, "").trim();
            return REPOSITORY.matcher(repository).matches() ? repository : null;
        } catch (IOException | InvalidConfigurationException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The jar a plugin was loaded from, when it is one in the plugins folder. */
    private static @Nullable Path jarOf(Plugin plugin) {
        try {
            Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            Path plugins = Bukkit.getUpdateFolderFile().toPath().getParent();
            return Files.isRegularFile(jar) && jar.getParent() != null && plugins != null
                    && Files.isSameFile(jar.getParent(), plugins) ? jar : null;
        } catch (URISyntaxException | IOException | RuntimeException unknown) {
            return null;
        }
    }

    /** The ExyliaLib that will run after a restart: a staged one when there is one, the running one otherwise. */
    @SuppressWarnings("deprecation")
    private static String libraryAfterRestart(Plugin library) {
        String running = library.getDescription().getVersion();
        String staged = describe(Bukkit.getUpdateFolderFile().toPath().resolve("ExyliaLib.jar")).version();
        return staged != null && isNewer(staged, running) ? staged : running;
    }

    /** The newest release's version and download URL, read from the redirect of the fixed-name asset. */
    private static String[] latest(String repository, String name) throws IOException {
        String target = "https://github.com/" + repository + "/releases/latest/download/" + name + ".jar";
        for (int hop = 0; hop < MOST_REDIRECTS; hop++) {
            HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
            try {
                connection.setRequestMethod("HEAD");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", "ExyliaLib-PluginUpdater/1.0");
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if (code / 100 != 3 || location == null) {
                    throw new IOException("no release with " + name + ".jar (HTTP " + code + ")");
                }
                Matcher matcher = RELEASE_IN_LOCATION.matcher(location);
                if (matcher.find()) return new String[]{matcher.group(1), location};
                // A renamed or moved repository: ask the name GitHub gave.
                target = location;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("could not read a version within " + MOST_REDIRECTS + " redirects");
    }

    private static void download(String url, Path into) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/java-archive");
            connection.setRequestProperty("User-Agent", "ExyliaLib-PluginUpdater/1.0");
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("download returned HTTP " + code);
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(into)) {
                in.transferTo(out);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** What a jar's plugin.yml says it is; every field {@code null} for a missing or unreadable jar. */
    record Descriptor(@Nullable String name, @Nullable String version, @Nullable String library) {
    }

    static Descriptor describe(Path jar) {
        if (!Files.isRegularFile(jar)) return new Descriptor(null, null, null);
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry("plugin.yml");
            if (entry == null) return new Descriptor(null, null, null);
            String name = null, version = null, library = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("name:")) name = value(line, "name:");
                    else if (line.startsWith("version:")) version = value(line, "version:");
                    else if (line.startsWith(LIBRARY_KEY + ":")) library = value(line, LIBRARY_KEY + ":");
                }
            }
            return new Descriptor(name, version, library);
        } catch (IOException truncated) {
            // Cut short, half written or not a zip: not the release asked for.
            return new Descriptor(null, null, null);
        }
    }

    private static String value(String line, String key) {
        String value = line.substring(key.length());
        int comment = value.indexOf(" #");
        if (comment >= 0) value = value.substring(0, comment);
        value = value.trim();
        if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isBlank() ? null : value;
    }

    // --- versions: major.minor.patch, anything after a dash ignored ---

    static boolean isNewer(String candidate, String current) {
        int[] a = triple(candidate);
        int[] b = triple(current);
        if (a == null) return false;
        if (b == null) return true;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int major(String version) {
        int[] parts = triple(version);
        return parts == null ? 0 : parts[0];
    }

    private static int @Nullable [] triple(String version) {
        if (version == null) return null;
        String[] parts = version.toLowerCase(Locale.ROOT).split("-", 2)[0].split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0};
        } catch (NumberFormatException notAVersion) {
            return null;
        }
    }
}
