package net.exylia.lib.web.internal;

import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Languages;
import net.exylia.lib.config.internal.ConfigFileImpl;
import net.exylia.lib.ui.Menus;
import net.exylia.lib.ui.internal.MenuRuntime;
import net.exylia.lib.web.Subject;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every file of a plugin an admin could edit by hand, found without the plugin registering it:
 * its record config files first, then any other YAML in its data folder.
 *
 * <p>Reads the disk, so it runs off the main thread. A subject a plugin registered always wins:
 * a file one already covers (compared as canonical files) is not offered twice, whatever
 * permission that registration asks for.
 */
final class Discovery {

    /** What a discovered subject asks of the sender: the editor's own permission. */
    static final String PERMISSION = "exylialib.editor";
    static final String MENU_KIND = "lib/menu";
    static final long MAX_FILE_BYTES = 1024 * 1024;
    /** Folders that hold what a plugin writes for itself, never what an admin edits. */
    static final Set<String> SKIPPED = Set.of("data", "players", "playerdata", "logs", "backups", "cache",
            "storage", "database", "exports", "schematics");
    private static final Pattern NOT_ID = Pattern.compile("[^A-Za-z0-9_-]+");
    private static final int MAX_ID = 48;

    private Discovery() {
    }

    /** What the server knows about a plugin: its language, the folders its jar regenerates, its config files. */
    static List<SubjectImpl> discover(Plugin plugin, Collection<SubjectImpl> registered, List<String> excluded) {
        return discover(plugin, registered, excluded, Languages.code(plugin),
                MenuRuntime.bundledDirectoriesOf(plugin.getName()), Configs.loaded());
    }

    static List<SubjectImpl> discover(Plugin plugin, Collection<SubjectImpl> registered, List<String> excluded,
                                      String language, Set<String> bundled, Collection<? extends ConfigFile<?>> configs) {
        Path root = canonical(plugin.getDataFolder()).toPath();
        List<Pattern> globs = excluded.stream().map(Discovery::glob).toList();
        Set<File> covered = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (SubjectImpl subject : registered) {
            ids.add(subject.id().toLowerCase(Locale.ROOT));
            File file = subject.source().disk();
            if (file != null) {
                covered.add(canonical(file));
            }
        }
        List<SubjectImpl> found = new ArrayList<>();

        for (ConfigFile<?> config : configs) {
            if (!(config instanceof ConfigFileImpl<?> impl) || !impl.ownedBy(plugin) || impl.file() == null) {
                continue;
            }
            File file = canonical(impl.file());
            if (!covered.add(file) || excluded(relative(root, file.toPath()), globs)) {
                continue;
            }
            String id = id(config.name(), ids);
            SubjectImpl subject = new SubjectImpl(plugin, id, new Source.RecordFile(config), Subject.GENERIC)
                    .discovered(impl.translated() ? "messages" : "settings");
            subject.title(title(config.name())).permission(PERMISSION);
            found.add(subject);
        }

        List<Path> files = yamlFiles(root, language, bundled, globs);
        for (Path path : files) {
            File file = canonical(path.toFile());
            if (covered.contains(file)) {
                continue;
            }
            YamlConfiguration yaml = parse(path);
            if (yaml == null) {
                continue;
            }
            String name = relative(root, path).replaceFirst("(?i)\\.yml$", "");
            boolean menu = yaml.isConfigurationSection("items")
                    && (yaml.contains("size") || yaml.contains("rows") || yaml.contains("type") || yaml.contains("title"));
            YamlSubjectImpl subject = YamlSubjectImpl.of(plugin, id(name, ids), path.toFile(), name,
                    menu ? MENU_KIND : Subject.GENERIC);
            subject.discovered(menu ? "menus" : "files");
            subject.title(title(name)).permission(PERMISSION);
            if (menu) {
                subject.sensitive("items.*.actions");
                subject.check(section -> {
                    String namespace = MenuRuntime.namespaceOf(plugin.getName());
                    return (namespace == null ? Menus.of(plugin) : Menus.of(plugin, namespace)).check(section);
                });
            }
            found.add(subject);
        }
        return found;
    }

    /** Every {@code .yml} under the data folder an admin could mean to edit, sorted by path. */
    private static List<Path> yamlFiles(Path root, String language, Set<String> bundled, List<Pattern> globs) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = relative(root, dir);
                    String name = dir.getFileName().toString();
                    boolean otherLanguage = relative.startsWith("lang/") && relative.indexOf('/', 5) < 0
                            && !name.equals(language) && !name.equals(Languages.CUSTOM);
                    boolean skip = name.startsWith(".") || SKIPPED.contains(name.toLowerCase(Locale.ROOT))
                            || bundled.contains(relative) || otherLanguage || excluded(relative, globs);
                    return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String name = file.getFileName().toString();
                    if (attributes.isRegularFile() && name.toLowerCase(Locale.ROOT).endsWith(".yml")
                            && !name.startsWith(".") && attributes.size() <= MAX_FILE_BYTES
                            && !excluded(relative(root, file), globs)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException unreadable) {
            // What could be walked is offered; a folder the server cannot read is not the admin's to edit here.
        }
        out.sort((a, b) -> relative(root, a).compareTo(relative(root, b)));
        return out;
    }

    private static YamlConfiguration parse(Path file) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return yaml;
        } catch (IOException | InvalidConfigurationException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Whether a relative path, or the same path without {@code .yml}, matches one of the plugin's
     * exclusions. A directory is checked before it is walked, so excluding it skips its files.
     */
    static boolean excluded(String relative, List<Pattern> globs) {
        String bare = relative.replaceFirst("(?i)\\.yml$", "");
        for (Pattern glob : globs) {
            if (glob.matcher(relative).matches() || glob.matcher(bare).matches()) {
                return true;
            }
        }
        return false;
    }

    /** {@code **} crosses folders, {@code *} and {@code ?} stay inside one; everything else is literal. */
    static Pattern glob(String glob) {
        String clean = glob.trim().replace('\\', '/').replaceAll("^/+|/+$", "");
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '*' && i + 1 < clean.length() && clean.charAt(i + 1) == '*') {
                boolean slash = i + 2 < clean.length() && clean.charAt(i + 2) == '/';
                regex.append(slash ? "(?:.*/)?" : ".*");
                i += slash ? 2 : 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /** A subject id from a path: {@code menus/user/mines} is {@code menus-user-mines}, unique within the plugin. */
    static String id(String path, Set<String> taken) {
        String base = NOT_ID.matcher(path).replaceAll("-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "file";
        }
        base = cut(base, MAX_ID);
        String id = base;
        for (int n = 2; taken.contains(id.toLowerCase(Locale.ROOT)); n++) {
            String suffix = "-" + n;
            id = cut(base, MAX_ID - suffix.length()) + suffix;
        }
        taken.add(id.toLowerCase(Locale.ROOT));
        return id;
    }

    private static String cut(String text, int max) {
        String out = text.length() <= max ? text : text.substring(0, max);
        return out.replaceAll("-+$", "").isEmpty() ? out : out.replaceAll("-+$", "");
    }

    /** {@code menus/user/mines} reads as {@code Menus › user › mines}. */
    static String title(String path) {
        String spaced = String.join(" › ", path.replace('_', ' ').replace('-', ' ').split("/"));
        return spaced.isEmpty() ? path : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
    }

    static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException unresolvable) {
            return file.getAbsoluteFile().toPath().normalize().toFile();
        }
    }
}
