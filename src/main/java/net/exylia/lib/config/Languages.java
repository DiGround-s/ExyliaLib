package net.exylia.lib.config;

import net.exylia.lib.config.internal.BundledResources;
import net.exylia.lib.debug.Debug;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The language a plugin's menus and messages are written in.
 *
 * <p>Each language is a folder of complete files under {@code lang/} in the
 * data folder, chosen by the {@code language} key of the plugin's
 * {@code config.yml}:
 *
 * <pre>
 * plugins/MyPlugin/config.yml          language: es
 * plugins/MyPlugin/lang/es/messages.yml
 * plugins/MyPlugin/lang/es/menus/...
 * </pre>
 *
 * <p>The server's language is set once, in ExyliaLib's own {@code config.yml};
 * a plugin whose {@code language} is {@code default} — what a fresh install
 * writes — follows it, and any other value sets that plugin alone.
 *
 * <p>A plugin packages its files the same way, under {@code lang/<code>/} in the
 * jar. English is the base every other language is laid over, so a file or a
 * key not translated yet still arrives, in English. A code nothing is packaged
 * for — {@code custom}, or one an owner invented — is simply English to start
 * from, and the owner's to rewrite.
 *
 * <pre>{@code
 * Configs.define(this, "config", Settings.class)          // declares String language, Languages.DEFAULT
 *         .version(4).migration(3, Languages.ADOPT_EXISTING).load();
 * Configs.define(this, "messages", Messages.class).translated().load();
 * Languages.refresh(this, MyPlugin.class, "menus");
 * File main = Languages.file(this, "menus/main.yml");
 * }</pre>
 *
 * <p><b>Servers that ran the plugin before it was translated</b> keep what they
 * edited: the first time a translated file is asked for, the one at its old
 * place ({@code menus/}, {@code messages.yml}) moves to {@code lang/custom/},
 * reviewed defaults included, and {@link #ADOPT_EXISTING} points the existing
 * {@code config.yml} at it. A fresh install has neither and starts in English.
 *
 * <p>{@code config.yml} is read straight from disk every time, so it has to be
 * loaded before the files that follow it, and a reload picks up a changed
 * language without a restart.
 *
 * @since 1.214.0
 */
public final class Languages {

    /** The key in {@code config.yml} that names the language. */
    public static final String KEY = "language";

    /** The language every other is laid over, and the one a fresh install uses. */
    public static final String ENGLISH = "en";

    /** A plugin's language that follows ExyliaLib's, and what a fresh install writes. */
    public static final String DEFAULT = "default";

    /** Where the files of a server that predates translations are moved. */
    public static final String CUSTOM = "custom";

    /**
     * The config migration that keeps a server on the files it already had.
     *
     * <p>Register it on {@code config.yml} at the version that introduces the
     * {@code language} key: it runs only for a file written before that, which
     * is exactly a server that already ran the plugin.
     */
    public static final Migration ADOPT_EXISTING = data -> {
        if (!data.contains(KEY)) {
            data.set(KEY, CUSTOM);
        }
    };

    private static final String FOLDER = "lang";
    private static final String LIBRARY = "ExyliaLib";
    private static final Pattern CODE = Pattern.compile("[a-z0-9_-]{1,32}");

    /** Languages set from code, by plugin name, ahead of any {@code config.yml}. */
    private static final Map<String, String> CHOSEN = new ConcurrentHashMap<>();

    private Languages() {
    }

    /**
     * Sets a plugin's language from code, for a plugin that keeps its settings
     * somewhere other than {@code config.yml}, such as a database.
     *
     * <p>Wins over the plugin's {@code config.yml}; {@link #DEFAULT} or
     * {@code null} goes back to reading it. Reload the plugin's files after
     * calling this so they are read in the new language.
     *
     * @param plugin the plugin
     * @param code   a language code such as {@code es}, or {@code null}
     * @since 1.237.0
     */
    public static void use(@NotNull Plugin plugin, @Nullable String code) {
        String clean = code == null ? DEFAULT : code.trim().toLowerCase(Locale.ROOT);
        if (clean.equals(DEFAULT) || !CODE.matcher(clean).matches()) {
            CHOSEN.remove(plugin.getName());
        } else {
            CHOSEN.put(plugin.getName(), clean);
        }
    }

    /**
     * The language a plugin speaks.
     *
     * <p>What its {@code config.yml} names; {@code default}, or no value at
     * all, means the one ExyliaLib's {@code config.yml} names, and English when
     * that names none either.
     *
     * @param plugin the plugin
     * @return a lower-case code such as {@code en}, {@code es} or {@code custom}
     */
    public static @NotNull String code(@NotNull Plugin plugin) {
        String chosen = CHOSEN.get(plugin.getName());
        if (chosen != null) {
            return chosen;
        }
        String own = read(plugin, plugin.getDataFolder());
        if (!own.equals(DEFAULT)) {
            return own;
        }
        // The library's folder sits next to every plugin's.
        File library = new File(plugin.getDataFolder().getParentFile(), LIBRARY);
        String shared = plugin.getName().equals(LIBRARY) ? DEFAULT : read(plugin, library);
        return shared.equals(DEFAULT) ? ENGLISH : shared;
    }

    /** The language one data folder's {@code config.yml} names, or {@link #DEFAULT}. */
    private static String read(Plugin plugin, File dataFolder) {
        File config = new File(dataFolder, "config.yml");
        if (!config.isFile()) {
            return DEFAULT;
        }
        String raw = YamlConfiguration.loadConfiguration(config).getString(KEY, DEFAULT);
        String code = raw.trim().toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            Debug.of(plugin).warn("language \"" + raw + "\" in " + dataFolder.getName()
                    + "/config.yml is not a language code (letters, digits, - and _), so it is ignored.");
            return DEFAULT;
        }
        return code;
    }

    /**
     * Where a translated file lives, relative to the data folder.
     *
     * <p>Moves the file from its place before translations into
     * {@code lang/custom/} first, if it is still there.
     *
     * @param plugin   the plugin
     * @param resource the path inside a language folder, such as {@code menus/main.yml}
     * @return {@code lang/<code>/<resource>}
     */
    public static @NotNull String path(@NotNull Plugin plugin, @NotNull String resource) {
        Path relative = BundledResources.relative(resource);
        adopt(plugin, relative);
        return FOLDER + "/" + code(plugin) + "/" + slashed(relative);
    }

    /**
     * The file {@link #path(Plugin, String)} names.
     *
     * @param plugin   the plugin
     * @param resource the path inside a language folder
     * @return the file in the data folder, which may not exist
     */
    public static @NotNull File file(@NotNull Plugin plugin, @NotNull String resource) {
        return new File(plugin.getDataFolder(), path(plugin, resource));
    }

    /**
     * Installs and updates a translated file or directory the owner may edit.
     *
     * <p>{@link BundledFiles#refresh(Plugin, Class, String)} for the current
     * language: missing files are written, new keys added, and a changed default
     * waits in {@code /exylialib updates}.
     *
     * @param plugin   the plugin
     * @param anchor   a class packaged with the resources
     * @param resource the path inside a language folder, the same in the jar and on disk
     * @return {@code false} when something could not be read or written, each of which is logged
     */
    public static boolean refresh(@NotNull Plugin plugin, @NotNull Class<?> anchor, @NotNull String resource) {
        Path target = Path.of(path(plugin, resource));
        return BundledFiles.refresh(plugin, anchor, layers(plugin, resource), target, resource);
    }

    /**
     * Replaces a translated directory with its packaged files, dropping edits.
     *
     * <p>For screens the plugin owns rather than the server owner, such as
     * admin menus, which are rewritten from the jar on every start.
     *
     * @param plugin   the plugin
     * @param anchor   a class packaged with the resources
     * @param resource the directory inside a language folder
     * @return whether the directory was replaced; a failure is logged and leaves the old one
     */
    public static boolean replace(@NotNull Plugin plugin, @NotNull Class<?> anchor, @NotNull String resource) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = BundledResources.inside(dataFolder, Path.of(path(plugin, resource)));
        try {
            BundledResources.replaceDirectory(anchor, layers(plugin, resource), dataFolder, target);
            return true;
        } catch (IOException | URISyntaxException | SecurityException failure) {
            Debug.of(plugin).warn("Could not refresh bundled directory \"" + target + "\": "
                    + failure.getMessage());
            return false;
        }
    }

    /**
     * The packaged paths a translated resource is assembled from, lowest first.
     *
     * <p>The untranslated path comes first so a plugin whose English files still
     * sit at the root of its jar keeps working.
     */
    static List<Path> layers(Plugin plugin, String resource) {
        Path relative = BundledResources.relative(resource);
        String code = code(plugin);
        List<Path> layers = new ArrayList<>(List.of(relative, Path.of(FOLDER, ENGLISH).resolve(relative)));
        if (!code.equals(ENGLISH)) {
            layers.add(Path.of(FOLDER, code).resolve(relative));
        }
        return layers;
    }

    /**
     * Moves a file from before translations into {@code lang/custom/}.
     *
     * <p>Never over a file already there, so it can never overwrite a custom
     * translation. Its reviewed defaults move with it, or every edit would look
     * like a pending update the first time the file is compared.
     */
    private static void adopt(Plugin plugin, Path relative) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path legacy = BundledResources.inside(dataFolder, relative);
        if (!Files.exists(legacy)) {
            return;
        }
        try {
            boolean moved = merge(legacy, dataFolder.resolve(FOLDER).resolve(CUSTOM).resolve(relative));
            for (String kind : List.of("files", "configs")) {
                Path reviewed = dataFolder.resolve(".defaults").resolve(kind);
                if (Files.exists(reviewed.resolve(relative))) {
                    merge(reviewed.resolve(relative), reviewed.resolve(FOLDER).resolve(CUSTOM).resolve(relative));
                }
            }
            removeEmptyParents(dataFolder, legacy.getParent());
            if (moved) {
                Debug.of(plugin).log("Moved " + slashed(relative) + " to " + FOLDER + "/" + CUSTOM + "/"
                        + slashed(relative) + " so the edits it holds are kept.");
            }
        } catch (IOException | SecurityException failure) {
            Debug.of(plugin).warn("Could not move " + slashed(relative) + " into " + FOLDER + "/"
                    + CUSTOM + "/: " + failure.getMessage());
        }
    }

    /**
     * Moves a file or directory to where it is adopted, file by file into a
     * directory that already exists.
     *
     * <p>A directory adopted piece by piece — {@code menus/admin} before the
     * rest of {@code menus} — still arrives whole. A file already at its new
     * place is never overwritten, and its old copy stays where it was.
     *
     * @return whether anything moved
     */
    private static boolean merge(Path from, Path to) throws IOException {
        if (Files.notExists(to)) {
            BundledResources.move(from, to);
            return true;
        }
        if (!Files.isDirectory(from) || !Files.isDirectory(to)) {
            return false;
        }
        boolean moved = false;
        try (var children = Files.list(from)) {
            for (Path child : children.toList()) {
                moved |= merge(child, to.resolve(child.getFileName().toString()));
            }
        }
        try (var left = Files.list(from)) {
            if (left.findAny().isEmpty()) {
                Files.delete(from);
            }
        }
        return moved;
    }

    private static void removeEmptyParents(Path dataFolder, Path directory) throws IOException {
        while (directory != null && !directory.equals(dataFolder) && directory.startsWith(dataFolder)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Files.delete(directory);
            directory = directory.getParent();
        }
    }

    private static String slashed(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
