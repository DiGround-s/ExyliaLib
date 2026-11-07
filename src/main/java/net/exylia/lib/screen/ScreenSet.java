package net.exylia.lib.screen;

import net.exylia.lib.config.BundledFiles;
import net.exylia.lib.config.Languages;
import net.exylia.lib.config.internal.BundledResources;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A plugin's screens as files on the server: one HTML per screen for every language, its words in the language
 * files, and the server owner's edits kept across updates.
 *
 * <pre>{@code
 * ScreenSet screens = Screens.of(plugin).files(MyPlugin.class, "screens");   // on enable
 * ScreenMarkup hub = screens.markup("hub")                                   // screens/hub.html
 *         .rows("kits", session -> kitRows(session))
 *         .action("queue", (event, args) -> ...);
 * hub.problems().forEach(plugin.getLogger()::warning);
 * Screens.of(plugin).open(player, hub.screen());
 * screens.reload();                                                          // on the plugin's reload
 * }</pre>
 *
 * <p>What a screen reads, for {@code markup("hub")} in the folder {@code screens}:
 * <ul>
 *   <li>{@code plugins/<Plugin>/screens/hub.html}, and the files it includes ({@code <include src="parts/x.html"/>})
 *       and every {@code components/*.html} next to it, whose {@code <template>}s it may use;</li>
 *   <li>its sheets, in cascade order: the library's {@code base.css}, the network's
 *       {@code plugins/ExyliaLib/screens/theme.css} when there is one, the plugin's {@code screens/theme.css} and
 *       {@code screens/hub.css} when there are, then {@code screens/custom.css}, which the plugin never ships, so
 *       an owner who only adds rules there keeps every update of the rest;</li>
 *   <li>its words from {@code lang/<code>/screens/hub.yml} ({@code t="key"} in the markup), so one HTML speaks every
 *       language; a key the file lacks keeps the English written in the HTML;</li>
 *   <li>pictures for {@code <img src="img/x.png">} from the same folder.</li>
 * </ul>
 *
 * <p>Every file comes from the plugin's jar under the same path through {@link BundledFiles}: missing ones are
 * written, ones the owner never edited follow the jar on every update, and an edited one stays as the owner left
 * it, with the jar's new version beside it as {@code <name>.new} and listed in {@code /exylialib updates}. Words
 * files gain new keys at once, as every other language file does.
 *
 * <p>Edits show without a restart: the files are checked every two seconds and a screen whose files changed is
 * read again, open sessions included, with their state kept ({@link #hotReload(boolean)}). A file that no longer
 * reads keeps the last good version on screen and says why in {@link ScreenMarkup#problems()} and in
 * {@code /exylialib screens check}.
 *
 * @since 1.494.0
 */
public final class ScreenSet {

    private static final Map<String, ScreenSet> ALL = new ConcurrentHashMap<>();
    private static final long POLL_TICKS = 40;

    private final Plugin plugin;
    private final Class<?> anchor;
    private final String folder;
    private final Map<String, ScreenMarkup> markups = new ConcurrentHashMap<>();
    private volatile boolean hotReload = true;
    private volatile TaskHandle watcher;
    private volatile long stamp;

    private ScreenSet(Plugin plugin, Class<?> anchor, String folder) {
        this.plugin = plugin;
        this.anchor = anchor;
        this.folder = folder;
    }

    static ScreenSet of(Plugin plugin, Class<?> anchor, String folder) {
        String clean = BundledResources.relative(folder).toString().replace('\\', '/');
        ScreenSet set = ALL.compute(plugin.getName() + "/" + clean, (key, old) ->
                old != null && old.plugin == plugin ? old : new ScreenSet(plugin, anchor, clean));
        set.installFiles();
        set.watch();
        return set;
    }

    /** Every set of every plugin, for {@code /exylialib screens}. */
    public static @NotNull List<ScreenSet> all() {
        return List.copyOf(ALL.values());
    }

    /** The plugin these screens belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** The folder they live in, relative to the plugin's data folder. */
    public @NotNull String folder() {
        return folder;
    }

    /** The screens read so far, by id. */
    public @NotNull Map<String, ScreenMarkup> markups() {
        return Map.copyOf(markups);
    }

    /**
     * The screen {@code <folder>/<id>.html}, read on the first call and the same object afterwards: register its
     * sources, values and actions once, and they survive every reload.
     *
     * @throws IllegalArgumentException when the file is missing or not well-formed, with the file and line
     */
    public @NotNull ScreenMarkup markup(@NotNull String id) {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("A screen id is letters, digits, - and _: " + id);
        ScreenMarkup known = markups.get(id);
        if (known != null) return known;
        refreshWords(id);
        try {
            ScreenMarkup read = ScreenMarkup.read(id + ".html", () -> compile(id));
            ScreenMarkup raced = markups.putIfAbsent(id, read);
            stamp = fingerprint();
            ScreenMarkup kept = raced != null ? raced : read;
            route(kept);
            return kept;
        } catch (IOException missing) {
            throw new IllegalArgumentException(folder + "/" + id + ".html could not be read: " + missing.getMessage(), missing);
        }
    }

    /**
     * Brings the files up to date with the jar (new files, untouched files updated, new words) and reads every
     * screen again, keeping what was registered on them. Call it from the plugin's own reload.
     *
     * @return whether every screen read; the ones that did not keep their last good version
     */
    public boolean reload() {
        installFiles();
        boolean ok = true;
        for (String id : markups.keySet()) {
            refreshWords(id);
            ok &= markups.get(id).reload();
            route(markups.get(id));
        }
        stamp = fingerprint();
        return ok;
    }

    /** Every problem of every screen read so far, each starting with its file. */
    public @NotNull List<String> problems() {
        List<String> out = new ArrayList<>();
        for (ScreenMarkup markup : markups.values()) out.addAll(markup.problems());
        return out;
    }

    /**
     * Whether edits to the files show without a reload (on by default). Checking costs a look at the files'
     * dates every two seconds, off the main thread.
     */
    public @NotNull ScreenSet hotReload(boolean on) {
        this.hotReload = on;
        if (!on && watcher != null) {
            watcher.cancel();
            watcher = null;
        } else if (on) {
            watch();
        }
        return this;
    }

    /**
     * Makes the menu ids {@code <screen routes="...">} names open this screen in SCREEN style
     * ({@link net.exylia.lib.ui.MenuStyles}).
     */
    private void route(ScreenMarkup markup) {
        net.exylia.lib.screen.internal.ScreenRoutes.declare(plugin, markup, markup.routes());
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private Path dir() {
        return plugin.getDataFolder().toPath().resolve(folder);
    }

    private Path networkTheme() {
        return plugin.getDataFolder().toPath().getParent().resolve("ExyliaLib").resolve("screens").resolve("theme.css");
    }

    private Path wordsFile(String id) {
        return Languages.file(plugin, folder + "/" + id + ".yml").toPath();
    }

    private void installFiles() {
        if (jarHas(folder)) BundledFiles.refresh(plugin, anchor, folder);
    }

    private boolean jarHas(String path) {
        return anchor.getClassLoader().getResource(path) != null;
    }

    private void refreshWords(String id) {
        String words = folder + "/" + id + ".yml";
        if (jarHas("lang/" + Languages.ENGLISH + "/" + words) || jarHas("lang/" + Languages.code(plugin) + "/" + words)) {
            Languages.refresh(plugin, anchor, words);
        }
    }

    private ScreenMarkup.Compiled compile(String id) throws IOException {
        Path dir = dir();
        Path html = dir.resolve(id + ".html");
        if (!Files.isRegularFile(html)) throw new IOException("there is no " + folder + "/" + id + ".html");
        List<String[]> sheets = new ArrayList<>();
        for (Path sheet : new Path[]{networkTheme(), dir.resolve("theme.css"), dir.resolve(id + ".css"), dir.resolve("custom.css")}) {
            if (Files.isRegularFile(sheet)) {
                sheets.add(new String[]{Files.readString(sheet, StandardCharsets.UTF_8), label(sheet)});
            }
        }
        Map<String, String> words = words(id);
        return ScreenMarkup.compile(id + ".html", Files.readString(html, StandardCharsets.UTF_8), sheets,
                ScreenMarkup.components(dir), relative -> ScreenMarkup.readInside(dir, relative), words::get, dir);
    }

    private String label(Path sheet) {
        return sheet.equals(networkTheme()) ? "ExyliaLib/screens/theme.css" : sheet.getFileName().toString();
    }

    /** The words of a screen, by dotted key. */
    private Map<String, String> words(String id) {
        Path file = wordsFile(id);
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) out.put(key, String.valueOf(yaml.get(key)));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Hot reload
    // ------------------------------------------------------------------

    private void watch() {
        if (!hotReload || watcher != null) return;
        try {
            watcher = Tasks.of(plugin).runAsyncTimer(POLL_TICKS, POLL_TICKS, () -> {
                if (markups.isEmpty()) return;
                long now = fingerprint();
                if (now == stamp) return;
                stamp = now;
                for (Map.Entry<String, ScreenMarkup> entry : new LinkedHashMap<>(markups).entrySet()) {
                    boolean read = entry.getValue().reload();
                    route(entry.getValue());
                    Debug.of(plugin).log((read ? "Read again: " : "Kept the last good version of ") + folder + "/"
                            + entry.getKey() + ".html" + (read ? "" : ", which no longer reads: "
                            + String.join("; ", entry.getValue().problems())));
                }
            });
        } catch (RuntimeException | LinkageError noServer) {
            // Without a running server (a unit test) there is nothing to watch with.
        }
    }

    /** The dates and sizes of every file the screens read, as one number. */
    private long fingerprint() {
        long hash = 17;
        List<Path> roots = new ArrayList<>(List.of(dir(), networkTheme()));
        for (String id : markups.keySet()) roots.add(wordsFile(id));
        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            try (var files = Files.walk(root, 4)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    hash = hash * 31 + file.toString().hashCode();
                    hash = hash * 31 + Files.getLastModifiedTime(file).toMillis();
                    hash = hash * 31 + Files.size(file);
                }
            } catch (IOException | RuntimeException unreadable) {
                hash = hash * 31 + 1;
            }
        }
        return hash;
    }
}
