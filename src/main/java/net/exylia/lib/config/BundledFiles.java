package net.exylia.lib.config;

import net.exylia.lib.config.internal.BundledResources;
import net.exylia.lib.config.internal.DefaultUpdates;
import net.exylia.lib.config.internal.DefaultsMerge;
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
 *   <li><b>missing file</b> — written, unless it was installed before and the
 *       owner deleted it;</li>
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
 * still forces itself over edits when the packaged file declares a
 * {@code menu-version} (or {@code defaults-version}) higher than the one on
 * disk; the replaced file is kept as {@code <name>.v<old version>}. A file on
 * disk that does not parse is left untouched and reported, and a file that is
 * not YAML is only ever written when missing.
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
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        BundledResources.inside(dataFolder, relative);
        Debug debug = Debug.of(plugin);

        Path staging = null;
        try {
            Files.createDirectories(dataFolder);
            staging = Files.createTempDirectory(dataFolder, ".bundled-");
            boolean single = BundledResources.extract(anchor, relative, staging);
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
            debug.warn("Could not refresh bundled files \"" + resource + "\": " + failure.getMessage());
            return false;
        } finally {
            BundledResources.deleteTree(staging);
        }
    }

    private static boolean update(Plugin plugin, Debug debug, Path dataFolder, Path packaged, Path file,
                                  Properties legacy) {
        String name = file.toString().replace('\\', '/');
        Path onDisk = dataFolder.resolve(file);
        Path reviewedPath = dataFolder.resolve(REVIEWED).resolve(file);
        try {
            if (Files.notExists(onDisk)) {
                if (Files.exists(reviewedPath)) {
                    // Installed before and gone now: the owner deleted it.
                    DefaultUpdates.forget(plugin, name);
                    return true;
                }
                install(packaged, onDisk, reviewedPath);
                DefaultUpdates.forget(plugin, name);
                return true;
            }
            if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
                return true;
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

            Integer shippedVersion = version(shipped);
            Integer declared = version(disk);
            int diskVersion = declared == null ? 0 : declared;
            if (shippedVersion != null && diskVersion < shippedVersion) {
                Path previous = onDisk.resolveSibling(onDisk.getFileName() + ".v" + diskVersion);
                Files.copy(onDisk, previous, StandardCopyOption.REPLACE_EXISTING);
                install(packaged, onDisk, reviewedPath);
                DefaultUpdates.forget(plugin, name);
                debug.log("Updated " + name + " from version " + diskVersion + " to " + shippedVersion
                        + "; the previous file was kept as " + previous.getFileName() + ".");
                return true;
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

    /** Writes a packaged file and records it as the reviewed defaults. */
    private static void install(Path packaged, Path onDisk, Path reviewedPath) throws IOException {
        Files.createDirectories(reviewedPath.getParent());
        Files.copy(packaged, reviewedPath, StandardCopyOption.REPLACE_EXISTING);
        BundledResources.move(packaged, onDisk);
    }

    private static @Nullable Integer version(YamlConfiguration yaml) {
        for (String key : VERSION_KEYS) {
            if (yaml.isInt(key)) {
                return yaml.getInt(key);
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
