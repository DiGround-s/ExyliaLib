package net.exylia.lib.config.internal;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads what a plugin packaged and writes files into its data folder safely.
 *
 * <p>Shared by bundled menus and bundled files. A plugin's resources can live
 * in a jar, in a build directory, or only inside a classloader that decrypted
 * them in memory, and all three are read the same way here.
 */
public final class BundledResources {

    private BundledResources() {
    }

    /**
     * Checks a resource path given by a plugin.
     *
     * @param resource the path inside the plugin and its data folder
     * @return the normalised relative path
     * @throws IllegalArgumentException if it is blank, absolute or escapes the plugin
     */
    public static @NotNull Path relative(@NotNull String resource) {
        if (resource.isBlank()) {
            throw new IllegalArgumentException("Resource directory cannot be blank.");
        }
        Path path = Path.of(resource).normalize();
        if (path.toString().isEmpty() || path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("Resource directory must be relative and cannot escape its plugin.");
        }
        return path;
    }

    /**
     * Resolves a relative path inside a data folder.
     *
     * @throws IllegalArgumentException if the result leaves the data folder
     */
    public static @NotNull Path inside(@NotNull Path dataFolder, @NotNull Path relative) {
        Path target = dataFolder.resolve(relative).normalize();
        if (!target.startsWith(dataFolder)) {
            throw new IllegalArgumentException("Resource directory must stay inside the plugin data folder.");
        }
        return target;
    }

    /**
     * Copies a packaged file, or every file under a packaged directory, into {@code staging}.
     *
     * @param anchor   a class packaged with the resources
     * @param resource the relative resource path
     * @param staging  an empty directory to copy into
     * @return {@code true} when the resource was one file, copied as {@code staging/<its name>}
     * @throws IOException when nothing is packaged at that path, or copying fails
     */
    public static boolean extract(@NotNull Class<?> anchor, @NotNull Path resource, @NotNull Path staging)
            throws IOException, URISyntaxException {
        URL location = artifactOf(anchor);
        if (location == null) {
            // No artifact on disk to walk. A plugin whose classes were defined
            // from bytes — a bootstrap loader that decrypts its payload in
            // memory — has neither a jar to open nor a directory to list, and
            // its resources live only inside its classloader.
            return extractFromClassLoader(anchor, resource, staging);
        }
        URI artifact = location.toURI();
        if ("file".equals(artifact.getScheme()) && Files.isDirectory(Path.of(artifact))) {
            Path root = Path.of(artifact);
            Path source = root.resolve(resource).normalize();
            if (!source.startsWith(root)) {
                throw new IOException("Packaged entry escapes the requested directory.");
            }
            if (Files.isRegularFile(source)) {
                Files.copy(source, staging.resolve(source.getFileName().toString()));
                return true;
            }
            if (!Files.isDirectory(source)) {
                throw new IOException("Packaged directory does not exist.");
            }
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Path destination = staging.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination);
                }
            }
            return false;
        }

        try (JarFile jar = new JarFile(Path.of(artifact).toFile())) {
            JarEntry single = jar.getJarEntry(nameOf(resource));
            if (single != null && !single.isDirectory()) {
                try (var input = jar.getInputStream(single)) {
                    Files.copy(input, staging.resolve(resource.getFileName().toString()));
                }
                return true;
            }
            String prefix = prefixOf(resource);
            boolean found = false;
            for (var entries = jar.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                found = true;
                Path destination = staging.resolve(entry.getName().substring(prefix.length())).normalize();
                if (!destination.startsWith(staging)) {
                    throw new IOException("Packaged entry escapes the requested directory.");
                }
                Files.createDirectories(destination.getParent());
                try (var input = jar.getInputStream(entry)) {
                    Files.copy(input, destination);
                }
            }
            if (!found) {
                throw new IOException("Packaged directory does not exist.");
            }
            return false;
        }
    }

    /**
     * Copies several packaged versions of one resource into {@code staging}, each
     * laid over the ones before it.
     *
     * <p>How a translation is read: the English files first, then the chosen
     * language's on top, so a file or key nobody has translated yet still
     * arrives in English rather than not at all. A layer that is not packaged is
     * skipped.
     *
     * @param anchor  a class packaged with the resources
     * @param layers  the relative resource paths, lowest first
     * @param staging an empty directory to copy into
     * @return {@code true} when the resource is one file, copied as {@code staging/<its name>}
     * @throws IOException when no layer is packaged, or copying fails
     */
    public static boolean extractLayers(@NotNull Class<?> anchor, @NotNull List<Path> layers,
                                        @NotNull Path staging) throws IOException, URISyntaxException {
        Boolean single = null;
        for (Path layer : layers) {
            Path scratch = Files.createTempDirectory(staging.getParent(), ".layer-");
            try {
                boolean one;
                try {
                    one = extract(anchor, layer, scratch);
                } catch (IOException missing) {
                    if (Files.exists(scratch) && isEmpty(scratch)) {
                        continue;
                    }
                    throw missing;
                }
                single = single == null ? one : single;
                try (var files = Files.walk(scratch)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        // A single file is staged under its own name, which
                        // differs between layers only if the paths do.
                        Path destination = one
                                ? staging.resolve(layers.get(0).getFileName().toString())
                                : staging.resolve(scratch.relativize(file));
                        Files.createDirectories(destination.getParent());
                        if (Files.exists(destination) && isYaml(destination)) {
                            layer(file, destination);
                        } else {
                            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } finally {
                deleteTree(scratch);
            }
        }
        if (single == null) {
            throw new IOException("Packaged directory does not exist.");
        }
        return single;
    }

    /**
     * Replaces a directory in a data folder with its packaged files.
     *
     * <p>The packaged files are staged before the existing target is moved
     * aside, so a failure at any point leaves the previous directory in place.
     *
     * @param anchor     a class packaged with the resources
     * @param layers     the packaged paths, lowest first, each laid over the ones before
     * @param dataFolder the plugin's data folder, where staging happens
     * @param target     the directory to replace
     */
    public static void replaceDirectory(@NotNull Class<?> anchor, @NotNull List<Path> layers,
                                        @NotNull Path dataFolder, @NotNull Path target)
            throws IOException, URISyntaxException {
        Path staging = null;
        Path backup = null;
        try {
            Files.createDirectories(dataFolder);
            staging = Files.createTempDirectory(dataFolder, ".bundled-");
            extractLayers(anchor, layers, staging);

            backup = Files.createTempDirectory(dataFolder, ".previous-");
            Files.delete(backup);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                move(target, backup);
            }
            try {
                move(staging, target);
            } catch (IOException replacementFailure) {
                try {
                    if (Files.exists(backup)) {
                        move(backup, target);
                        backup = null;
                    }
                } catch (IOException restorationFailure) {
                    replacementFailure.addSuppressed(restorationFailure);
                }
                throw replacementFailure;
            }
            staging = null;
            deleteTree(backup);
        } finally {
            deleteTree(staging);
        }
    }

    /**
     * Lays one YAML file over another: the upper wins, and a key only the lower
     * has is added, so a button new in English reaches a translation that does
     * not have it yet. An upper file with nothing to add is copied byte for byte.
     */
    private static void layer(Path upper, Path lower) throws IOException {
        YamlConfiguration top = new YamlConfiguration();
        YamlConfiguration base = new YamlConfiguration();
        try {
            top.loadFromString(Files.readString(upper, StandardCharsets.UTF_8));
            base.loadFromString(Files.readString(lower, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException broken) {
            Files.copy(upper, lower, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        boolean added = false;
        for (String key : base.getKeys(true)) {
            if (!base.isConfigurationSection(key) && !top.contains(key)) {
                top.set(key, base.get(key));
                added = true;
            }
        }
        if (!added) {
            Files.copy(upper, lower, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        top.options().width(Integer.MAX_VALUE);
        Files.writeString(lower, top.saveToString(), StandardCharsets.UTF_8);
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Moves a file into place, atomically where the file system allows.
     *
     * <p>Staging lives in the data folder, so this is a rename: a crash halfway
     * never leaves a truncated file behind.
     */
    public static void move(@NotNull Path source, @NotNull Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes text through a temporary file, and only when it differs from what is there.
     *
     * <p>Rewriting an identical file on every start would bump its timestamp and
     * make an owner wonder what changed.
     */
    public static void write(@NotNull Path target, @NotNull String content) throws IOException {
        if (Files.exists(target) && Files.readString(target, StandardCharsets.UTF_8).equals(content)) {
            return;
        }
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Deletes a directory and everything in it, ignoring whatever cannot be deleted. */
    public static void deleteTree(@Nullable Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A failed cleanup must not change the refresh result.
                }
            });
        } catch (IOException ignored) {
            // A failed cleanup must not change the refresh result.
        }
    }

    /**
     * The artifact the class was loaded from, or {@code null} when there is
     * none to read.
     *
     * <p>A class defined from a byte array carries the classloader's default
     * protection domain, whose code source is present but locationless. Both
     * that and a missing code source mean the same thing here: there is no jar
     * or directory to walk.
     */
    private static URL artifactOf(Class<?> anchor) {
        CodeSource source = anchor.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }

    private static String nameOf(Path resource) {
        return resource.toString().replace('\\', '/');
    }

    /** The resource path of a directory, always ending in a slash. */
    private static String prefixOf(Path resource) {
        String prefix = nameOf(resource);
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /**
     * Copies a packaged file or directory out of the classloader itself.
     *
     * <p>The names come from the loader's own resource table and the bytes come
     * back through {@link ClassLoader#getResourceAsStream}, so nothing here
     * depends on the payload existing as a file.
     */
    private static boolean extractFromClassLoader(Class<?> anchor, Path resource, Path staging)
            throws IOException {
        String prefix = prefixOf(resource);
        ClassLoader loader = anchor.getClassLoader();
        Collection<String> names = bundledResourceNames(loader, prefix);
        if (names.isEmpty()) {
            try (var input = loader == null ? null : loader.getResourceAsStream(nameOf(resource))) {
                if (input == null) {
                    throw new IOException("Packaged directory does not exist.");
                }
                Files.copy(input, staging.resolve(resource.getFileName().toString()));
                return true;
            }
        }
        for (String name : names) {
            Path destination = staging.resolve(name.substring(prefix.length())).normalize();
            if (!destination.startsWith(staging)) {
                throw new IOException("Packaged entry escapes the requested directory.");
            }
            try (var input = loader.getResourceAsStream(name)) {
                if (input == null) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return false;
    }

    /**
     * The resources a classloader holds under a directory.
     *
     * <p>{@code ClassLoader} can hand back a resource by name but cannot list
     * one, so a loader that keeps its payload in memory is asked for its table
     * directly: every map it declares is read, and the keys that sit under the
     * directory are the entries. Reflection is the only door there is, and a
     * loader that does not open it simply reports nothing, which the caller
     * reads as "no packaged directory".
     */
    private static Collection<String> bundledResourceNames(ClassLoader loader, String prefix) {
        if (loader == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Class<?> type = loader.getClass(); type != null && type != ClassLoader.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(Modifier.isStatic(field.getModifiers()) ? null : loader);
                    if (!(value instanceof Map<?, ?> table)) {
                        continue;
                    }
                    for (Object key : table.keySet()) {
                        if (key instanceof String name && name.startsWith(prefix) && !name.endsWith("/")) {
                            names.add(name);
                        }
                    }
                } catch (RuntimeException | ReflectiveOperationException ignored) {
                    // A table this loader will not open is a table with nothing
                    // in it, as far as looking for packaged files goes.
                }
            }
        }
        return names;
    }
}
