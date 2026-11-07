package net.exylia.lib.config;

import net.exylia.lib.config.internal.BundledResources;
import net.exylia.lib.config.internal.DefaultUpdates;
import net.exylia.lib.config.internal.DefaultsMerge;
import net.exylia.lib.config.internal.InstalledFiles;
import net.exylia.lib.debug.Debug;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Files a plugin ships for the server owner to edit — menus, catalogues of
 * effects, cosmetics or items — installed once and kept up to date without
 * undoing the owner's work.
 *
 * <pre>{@code
 * BundledFiles.refresh(this, MyPlugin.class, "menus");        // a directory
 * BundledFiles.refresh(this, MyPlugin.class, "effects.yml");  // one file
 * }</pre>
 *
 * <p>Call it before reading the files, on enable and on reload. For a YAML file
 * every key is compared with the defaults the owner last reviewed, kept in
 * {@code .defaults/files/} in the data folder:
 * <ul>
 *   <li><b>missing file</b> — written, every time it is missing, whether it was
 *       never installed or deleted afterwards;</li>
 *   <li><b>new key</b> — added to the owner's file at once, comments included:
 *       nobody can have chosen something that did not exist;</li>
 *   <li><b>changed or removed default</b> on a key still at its reviewed value
 *       — left as it is and listed in {@code /exylialib updates}, where the
 *       owner applies or keeps it: a value equal to its default may well be the
 *       one they want;</li>
 *   <li><b>anything the owner changed or deleted</b> — never touched.</li>
 * </ul>
 *
 * <p>On a server with nothing reviewed yet every value already there counts as
 * the owner's, and a key missing from a file is listed as new rather than added,
 * since it may be one the owner deleted. A change the old file cannot survive
 * is declared by a packaged {@code menu-version} (or {@code defaults-version})
 * higher than the one on disk: an untouched file is replaced at once, an edited
 * one waits in {@code /exylialib updates} as a single change, and applying it
 * replaces the file and keeps the old one as {@code <name>.v<old version>}.
 * A file on disk that does not parse is left untouched and reported. A file
 * that is not YAML (a screen's HTML, CSS or pictures) is replaced while the
 * owner has not edited it; an edited one stays, with the new version written
 * beside it as {@code <name>.new} and listed in {@code /exylialib updates}
 * (since 1.494.0).
 *
 * @since 1.158.0
 */
public final class BundledFiles {

    /** Where the reviewed defaults of bundled files are kept, inside the data folder. */
    private static final String REVIEWED = ".defaults/files";

    /** What 1.157.0 recorded: the hash of each file it installed. */
    private static final String LEGACY_LEDGER = ".bundled-files";

    private static final List<String> VERSION_KEYS = List.of("menu-version", "defaults-version");

    private BundledFiles() {
    }

    /**
     * Marks values the library wrote into a packaged file with its own defaults, before the plugin's jar
     * shipped those keys, as defaults nobody has reviewed yet rather than as the owner's: a key the jar
     * ships now, which the reviewed defaults never had, whose value on disk is still {@code defaults}'.
     * The next {@link #refresh} then offers the jar's value in {@code /exylialib updates} instead of
     * keeping the library's for good. Call it before {@code refresh}.
     *
     * <p>That happens when the library is updated before a plugin: a record file (a spatial layout) gains
     * its new keys from the record, then the plugin's jar ships its own value for them.
     *
     * @param defaults dotted keys and the value the library writes for each
     * @since 1.442.0
     */
    public static void libraryWrote(@NotNull Plugin plugin, @NotNull Class<?> anchor, @NotNull String resource,
                                    @NotNull java.util.Map<String, Object> defaults) {
        Path relative = BundledResources.relative(resource);
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path onDisk = dataFolder.resolve(relative);
        Path reviewedPath = dataFolder.resolve(REVIEWED).resolve(relative);
        if (Files.notExists(onDisk) || Files.notExists(reviewedPath)) return;
        try (java.io.InputStream in = BundledResources.open(anchor.getClassLoader(), resource)) {
            if (in == null) return;
            YamlConfiguration shipped = DefaultsMerge.yaml();
            shipped.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            YamlConfiguration disk = DefaultsMerge.yaml();
            disk.load(onDisk.toFile());
            YamlConfiguration reviewed = DefaultsMerge.yaml();
            reviewed.load(reviewedPath.toFile());
            boolean changed = false;
            for (java.util.Map.Entry<String, Object> key : defaults.entrySet()) {
                // These trees keep dotted keys whole: their separator is not the dot.
                String path = key.getKey().replace('.', DefaultsMerge.SEPARATOR);
                if (!shipped.contains(path) || reviewed.contains(path) || !disk.contains(path)) continue;
                if (!sameValue(disk.get(path), key.getValue())) continue;
                reviewed.set(path, disk.get(path));
                changed = true;
            }
            if (changed) BundledResources.write(reviewedPath, reviewed.saveToString());
        } catch (IOException | InvalidConfigurationException | SecurityException failure) {
            Debug.of(plugin).warn("Could not read " + resource + " to review its new defaults: " + failure.getMessage());
        }
    }

    /**
     * Lets a raised {@code menu-version} replace a file nobody edited when the file is also read as a record
     * ({@code schema}, a spatial layout): reading it fills keys the jar never shipped with the record's
     * defaults, drops keys the record does not own and stamps a {@code config-version}, so it never equals
     * the reviewed defaults again and {@link #refresh} took it for one the owner edited, offering the new
     * layout instead of installing it. When the file on disk is exactly the reviewed defaults as the record
     * writes them, those become the reviewed defaults, and the next {@code refresh} sees an untouched file.
     * Does nothing unless the jar's version is higher than the file's. Call it before {@code refresh}.
     *
     * @param earlier values the record wrote for a key in an older library, by dotted key: a file written
     *                then still holds them, and they are the library's, not the owner's
     * @since 1.452.0
     */
    public static void recordWrote(@NotNull Plugin plugin, @NotNull Class<?> anchor, @NotNull String resource,
                                   @NotNull Class<? extends Record> schema,
                                   @NotNull java.util.Map<String, java.util.List<Object>> earlier) {
        Path relative = BundledResources.relative(resource);
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path onDisk = dataFolder.resolve(relative);
        Path reviewedPath = dataFolder.resolve(REVIEWED).resolve(relative);
        if (Files.notExists(onDisk) || Files.notExists(reviewedPath)) return;
        try (java.io.InputStream in = BundledResources.open(anchor.getClassLoader(), resource)) {
            if (in == null) return;
            YamlConfiguration shipped = DefaultsMerge.yaml();
            shipped.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String versionKey = versionKey(shipped);
            if (versionKey == null) return;
            YamlConfiguration disk = DefaultsMerge.yaml();
            disk.load(onDisk.toFile());
            int diskVersion = disk.isInt(versionKey) ? disk.getInt(versionKey) : 0;
            if (diskVersion >= shipped.getInt(versionKey)) return;
            YamlConfiguration reviewed = DefaultsMerge.yaml();
            reviewed.load(reviewedPath.toFile());
            if (DefaultsMerge.same(disk, reviewed)) return;
            YamlConfiguration written = net.exylia.lib.config.internal.RecordYaml.normalised(schema, plugin.getName(), reviewed);
            // The record's own bookkeeping, whatever it reads.
            Object stamp = disk.get("config-version");
            if (stamp != null) written.set("config-version", stamp);
            for (java.util.Map.Entry<String, java.util.List<Object>> key : earlier.entrySet()) {
                String path = key.getKey().replace('.', DefaultsMerge.SEPARATOR);
                if (reviewed.contains(path) || !disk.contains(path)) continue;
                for (Object old : key.getValue()) {
                    if (DefaultsMerge.same(disk.get(path), old)) written.set(path, disk.get(path));
                }
            }
            if (!DefaultsMerge.same(disk, written)) return;
            BundledResources.write(reviewedPath, written.saveToString());
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            Debug.of(plugin).warn("Could not compare " + resource + " with its reviewed defaults: " + failure.getMessage());
        }
    }

    private static boolean sameValue(@Nullable Object disk, @Nullable Object library) {
        if (disk instanceof Number a && library instanceof Number b) return a.doubleValue() == b.doubleValue();
        return java.util.Objects.equals(disk, library);
    }

    /**
     * Installs and updates a packaged file, or every file under a packaged directory.
     *
     * @param plugin   the plugin whose data folder receives the files
     * @param anchor   a class packaged with the resources
     * @param resource the relative path, the same inside the plugin and its data folder
     * @return {@code false} when something could not be read or written, each of which is logged
     * @throws IllegalArgumentException if the path is blank, absolute, or escapes the data folder
     */
    public static boolean refresh(@NotNull Plugin plugin, @NotNull Class<?> anchor, @NotNull String resource) {
        Path relative = BundledResources.relative(resource);
        return refresh(plugin, anchor, List.of(relative), relative, resource);
    }

    /**
     * Installs and updates a file assembled from several packaged layers.
     *
     * @param layers   the packaged paths, lowest first, each laid over the ones before
     * @param relative where the result lives, relative to the data folder
     * @param label    how the resource is named in a warning
     */
    static boolean refresh(Plugin plugin, Class<?> anchor, List<Path> layers, Path relative, String label) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        BundledResources.inside(dataFolder, relative);
        InstalledFiles.editable(plugin, relative);
        Debug debug = Debug.of(plugin);

        Path staging = null;
        try {
            Files.createDirectories(dataFolder);
            staging = Files.createTempDirectory(dataFolder, ".bundled-");
            boolean single = BundledResources.extractLayers(anchor, layers, staging);
            Properties legacy = legacyLedger(dataFolder);

            boolean written = true;
            try (var files = Files.walk(staging)) {
                for (Path packaged : files.filter(Files::isRegularFile).toList()) {
                    Path file = single ? relative : relative.resolve(staging.relativize(packaged));
                    written &= update(plugin, debug, dataFolder, packaged, file, legacy);
                }
            }
            return written;
        } catch (IOException | URISyntaxException | SecurityException failure) {
            debug.warn("Could not refresh bundled files \"" + label + "\": " + failure.getMessage());
            return false;
        } finally {
            BundledResources.deleteTree(staging);
        }
    }

    /**
     * Removes a file the plugin no longer ships, unless the owner edited it.
     *
     * <p>The file is deleted when it still matches what the library last wrote there (or is already gone);
     * an edited one, or one the library has no record of, stays where it is. Use it when a plugin moves or
     * drops a bundled file, so servers do not keep a stale copy nobody chose to keep.
     *
     * <pre>{@code
     * if (!BundledFiles.retire(plugin, "lang/en/screens/practice_hub.html")) {
     *     getLogger().warning("lang/en/screens/practice_hub.html was edited and is no longer read");
     * }
     * }</pre>
     *
     * @param plugin   the owning plugin
     * @param resource the path relative to the data folder
     * @return {@code true} when the file is gone, {@code false} when it was kept
     * @throws IllegalArgumentException if the path is blank, absolute, or escapes the data folder
     * @since 1.494.0
     */
    public static boolean retire(@NotNull Plugin plugin, @NotNull String resource) {
        Path relative = BundledResources.relative(resource);
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        BundledResources.inside(dataFolder, relative);
        Path onDisk = dataFolder.resolve(relative);
        Path reviewedPath = dataFolder.resolve(REVIEWED).resolve(relative);
        try {
            if (Files.exists(onDisk)) {
                if (Files.notExists(reviewedPath)
                        || !java.util.Arrays.equals(Files.readAllBytes(onDisk), Files.readAllBytes(reviewedPath))) {
                    return false;
                }
                Files.delete(onDisk);
            }
            Files.deleteIfExists(reviewedPath);
            Files.deleteIfExists(DefaultUpdates.offered(onDisk));
            DefaultUpdates.forget(plugin, relative.toString().replace('\\', '/'));
            return true;
        } catch (IOException failure) {
            Debug.of(plugin).warn("Could not remove " + resource + ": " + failure.getMessage());
            return false;
        }
    }

    private static boolean update(Plugin plugin, Debug debug, Path dataFolder, Path packaged, Path file,
                                  Properties legacy) {
        String name = file.toString().replace('\\', '/');
        Path onDisk = dataFolder.resolve(file);
        Path reviewedPath = dataFolder.resolve(REVIEWED).resolve(file);
        try {
            if (Files.notExists(onDisk)) {
                // Nothing on disk is nothing to keep: the file comes back, as
                // a config does. Removing one for good means removing it from
                // the plugin, not from the data folder.
                install(packaged, onDisk, reviewedPath);
                DefaultUpdates.forget(plugin, name);
                return true;
            }
            if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
                return updateWhole(plugin, debug, packaged, onDisk, reviewedPath, name);
            }

            String shippedText = Files.readString(packaged);
            YamlConfiguration shipped = DefaultsMerge.yaml();
            shipped.loadFromString(shippedText);
            YamlConfiguration disk = DefaultsMerge.yaml();
            try {
                disk.load(onDisk.toFile());
            } catch (InvalidConfigurationException broken) {
                debug.warn(name + " could not be read (" + broken.getMessage()
                        + "), so its new defaults were not added. The file was left untouched.");
                return false;
            }

            YamlConfiguration reviewed = null;
            if (Files.exists(reviewedPath)) {
                reviewed = DefaultsMerge.yaml();
                reviewed.load(reviewedPath.toFile());
            } else if (hash(onDisk).equals(legacy.getProperty(name))) {
                // Installed by 1.157.0 and untouched since, so it is exactly
                // the defaults this server last had.
                reviewed = DefaultsMerge.yaml();
                reviewed.load(onDisk.toFile());
            }

            String versionKey = versionKey(shipped);
            int shippedVersion = versionKey == null ? 0 : shipped.getInt(versionKey);
            int diskVersion = versionKey != null && disk.isInt(versionKey) ? disk.getInt(versionKey) : 0;
            if (diskVersion < shippedVersion) {
                if (reviewed == null || !DefaultsMerge.same(disk, reviewed)) {
                    // The owner's edits are theirs to give up: the new layout
                    // waits in /exylialib updates and the file stays as it is.
                    DefaultUpdates.track(plugin, name, onDisk, reviewedPath, shippedText, null,
                            List.of(DefaultsMerge.layout(versionKey, diskVersion, shippedVersion)));
                    return true;
                }
                Path previous = onDisk.resolveSibling(onDisk.getFileName() + ".v" + diskVersion);
                Files.copy(onDisk, previous, StandardCopyOption.REPLACE_EXISTING);
                install(packaged, onDisk, reviewedPath);
                DefaultUpdates.forget(plugin, name);
                debug.log("Updated " + name + " from version " + diskVersion + " to " + shippedVersion
                        + "; the previous file was kept as " + previous.getFileName() + ".");
                return true;
            }

            DefaultsMerge.Result result = DefaultsMerge.merge(disk, reviewed, shipped);
            if (!result.added().isEmpty()) {
                BundledResources.write(onDisk, disk.saveToString());
                debug.log("Added to " + name + ": " + result.added().stream()
                        .map(DefaultsMerge.Change::dotted).collect(Collectors.joining(", ")) + ".");
            }
            BundledResources.write(reviewedPath, result.reviewed().saveToString());
            DefaultUpdates.track(plugin, name, onDisk, reviewedPath, shippedText, null, result.pending());
            return true;
        } catch (IOException | InvalidConfigurationException | SecurityException failure) {
            debug.warn("Could not update " + name + ": " + failure.getMessage());
            return false;
        }
    }

    /**
     * A file that is not YAML has no keys to merge, so it moves as a whole: replaced when the owner never
     * touched it, and otherwise left alone with the new version written beside it as {@code <name>.new} and
     * listed in {@code /exylialib updates} (since 1.494.0; before, such a file never updated once installed).
     */
    private static boolean updateWhole(Plugin plugin, Debug debug, Path packaged, Path onDisk, Path reviewedPath,
                                       String name) throws IOException {
        byte[] shipped = Files.readAllBytes(packaged);
        byte[] reviewed = Files.exists(reviewedPath) ? Files.readAllBytes(reviewedPath) : null;
        Path offered = DefaultUpdates.offered(onDisk);
        if (reviewed != null && java.util.Arrays.equals(shipped, reviewed)) {
            // Nothing new in the jar; a .new the owner deleted is their answer.
            if (Files.notExists(offered)) DefaultUpdates.forget(plugin, name);
            else DefaultUpdates.track(plugin, name, onDisk, reviewedPath, "", null, List.of(DefaultsMerge.wholeFile()));
            return true;
        }
        byte[] disk = Files.readAllBytes(onDisk);
        if (java.util.Arrays.equals(disk, shipped) || (reviewed != null && java.util.Arrays.equals(disk, reviewed))) {
            install(packaged, onDisk, reviewedPath);
            Files.deleteIfExists(offered);
            DefaultUpdates.forget(plugin, name);
            if (!java.util.Arrays.equals(disk, shipped)) debug.log("Updated " + name + " to the version this jar ships.");
            return true;
        }
        // Edited by the owner: theirs stays, the new one waits beside it.
        Files.createDirectories(offered.getParent());
        Files.copy(packaged, offered, StandardCopyOption.REPLACE_EXISTING);
        DefaultUpdates.track(plugin, name, onDisk, reviewedPath, "", null, List.of(DefaultsMerge.wholeFile()));
        return true;
    }

    /** Writes a packaged file and records it as the reviewed defaults. */
    private static void install(Path packaged, Path onDisk, Path reviewedPath) throws IOException {
        Files.createDirectories(reviewedPath.getParent());
        Files.copy(packaged, reviewedPath, StandardCopyOption.REPLACE_EXISTING);
        BundledResources.move(packaged, onDisk);
    }

    private static @Nullable String versionKey(YamlConfiguration yaml) {
        for (String key : VERSION_KEYS) {
            if (yaml.isInt(key)) {
                return key;
            }
        }
        return null;
    }

    private static Properties legacyLedger(Path dataFolder) throws IOException {
        Properties ledger = new Properties();
        Path file = dataFolder.resolve(LEGACY_LEDGER);
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                ledger.load(reader);
            }
        }
        return ledger;
    }

    private static String hash(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every JVM provides SHA-256.", impossible);
        }
    }
}
