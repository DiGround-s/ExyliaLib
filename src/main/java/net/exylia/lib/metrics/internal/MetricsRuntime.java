package net.exylia.lib.metrics.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.ExyliaLib;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Tells stats.exylia.net which servers run Exylia plugins, on what, and how
 * those plugins fail.
 *
 * <p>Only production Exylia plugins count: ones running through a Lukittu
 * loader whose branch is not {@code dev}. A local jar or a dev loader is not
 * listed and its errors are dropped; a server with no production plugin sends
 * nothing at all. {@code metrics.enabled: false} in the library's config does
 * the same, and so does the stats server answering {@code enabled: false}.
 *
 * <p>Everything runs on the async scheduler, and every entry point swallows
 * its own failures: a report that cannot be built or delivered is a debug
 * line, never an exception in somebody else's code.
 *
 * @since 1.160.0
 */
public final class MetricsRuntime {

    static final String ENDPOINT = "https://stats.exylia.net/api/v1/report";
    static final int MAX_BODY_BYTES = 256 * 1024;
    static final int MAX_PLUGINS = 100;
    static final Pattern NAME = Pattern.compile("^Exylia[A-Za-z0-9_-]{1,48}$");

    /** The platforms a Bukkit-side loader is generated for, as the class suffix. */
    private static final String[] PLATFORMS = {"Paper", "Spigot"};
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long ERROR_FLUSH_TICKS = 60L * 20L;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    /** What {@code LukittuBootstrap} logs when the inner plugin throws while starting. */
    private static final String LOADER_STARTUP_FAILURE = "Failed to start inner loader plugin";

    private static volatile MetricsRuntime instance;

    private final ExyliaLib lib;
    private final ErrorGroups errors = new ErrorGroups();
    /** Whether a plugin is production, per load: a reload brings a new Plugin object. */
    private final Map<Plugin, Boolean> production = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Plugin, String> versions = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Logger, Handler> watched = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private volatile long intervalSeconds = 1800;
    private volatile boolean remoteOff;
    private String server;

    private MetricsRuntime(ExyliaLib lib) {
        this.lib = lib;
    }

    /**
     * Starts reporting, unless the server owner turned it off.
     *
     * @param lib the library plugin, whose scheduler runs the reports
     */
    public static void start(@NotNull ExyliaLib lib) {
        try {
            if (!LibrarySettings.get().metrics().enabled()) {
                return;
            }
            MetricsRuntime runtime = new MetricsRuntime(lib);
            instance = runtime;
            if (Platform.isPaper()) {
                Bukkit.getPluginManager().registerEvents(new ServerErrors(), lib);
            }
            runtime.watchLoaderStartups();
            long firstTicks = ThreadLocalRandom.current().nextLong(60, 301) * 20L;
            Tasks.of(lib).runAsyncLater(firstTicks, runtime::heartbeat);
            Tasks.of(lib).runAsyncTimer(ERROR_FLUSH_TICKS, ERROR_FLUSH_TICKS, runtime::flushErrors);
        } catch (Throwable failure) {
            instance = null;
            Debug.of(lib).debug("Metrics did not start: " + failure);
        }
    }

    /** Stops reporting. The timers themselves go with the library's tasks. */
    public static void stop() {
        MetricsRuntime runtime = instance;
        instance = null;
        if (runtime != null) {
            runtime.watched.forEach((logger, handler) -> logger.removeHandler(handler));
            runtime.watched.clear();
        }
    }

    /**
     * A Lukittu loader catches the inner plugin failing to start and only logs
     * it, so Paper never raises it. Every Exylia plugin is loaded, not yet
     * enabled, when the library starts: its logger is where that line lands.
     */
    private void watchLoaderStartups() {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin == lib || !NAME.matcher(plugin.getName()).matches()) {
                continue;
            }
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getThrown() != null && record.getMessage() != null
                            && record.getMessage().startsWith(LOADER_STARTUP_FAILURE)) {
                        error(plugin, "enable", record.getThrown());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            plugin.getLogger().addHandler(handler);
            watched.put(plugin.getLogger(), handler);
        }
    }

    /**
     * Counts an error thrown by a plugin, if it is one the report lists.
     *
     * @param plugin the plugin responsible, as the hook knows it
     * @param phase  {@code enable}, {@code disable} or {@code runtime}
     * @param error  what was thrown
     */
    public static void error(@Nullable Plugin plugin, @NotNull String phase, @Nullable Throwable error) {
        MetricsRuntime runtime = instance;
        if (runtime == null || plugin == null || error == null) {
            return;
        }
        try {
            if (NAME.matcher(plugin.getName()).matches() && runtime.counts(plugin)) {
                runtime.errors.add(plugin.getName(), runtime.version(plugin), phase, error);
            }
        } catch (Throwable ignored) {
            // Metrics must never become the next exception.
        }
    }

    private void heartbeat() {
        if (instance != this) {
            return;
        }
        send();
        if (instance == this && !remoteOff) {
            Tasks.of(lib).runAsyncLater(intervalSeconds * 20L, this::heartbeat);
        }
    }

    private void flushErrors() {
        if (instance == this && !errors.isEmpty()) {
            send();
        }
    }

    /** One report. Synchronized so a heartbeat and a flush never overlap. */
    private synchronized void send() {
        try {
            if (instance != this || remoteOff || !LibrarySettings.get().metrics().enabled()) {
                return;
            }
            Map<String, String> plugins = plugins();
            JsonArray pending = errors.drain(plugins.keySet());
            if (plugins.isEmpty()) {
                return;
            }
            byte[] body = encode(payload(serverId(), facts(), lib.version(), plugins, pending));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "ExyliaLib/" + lib.version())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                Debug.of(lib).debug("Metrics report answered " + response.statusCode());
                return;
            }
            JsonObject answer = JsonParser.parseString(response.body()).getAsJsonObject();
            if (answer.has("interval")) {
                intervalSeconds = Math.clamp(answer.get("interval").getAsLong(), 300L, 86_400L);
            }
            if (answer.has("enabled") && !answer.get("enabled").getAsBoolean()) {
                remoteOff = true;
            }
        } catch (Throwable failure) {
            Debug.of(lib).debug("Metrics report failed: " + failure);
        }
    }

    /** Production Exylia plugins, then the library itself if there was any. */
    private Map<String, String> plugins() {
        Map<String, String> found = new LinkedHashMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (found.size() >= MAX_PLUGINS - 1) {
                break;
            }
            if (plugin != lib && NAME.matcher(plugin.getName()).matches() && counts(plugin)) {
                found.put(plugin.getName(), version(plugin));
            }
        }
        if (!found.isEmpty()) {
            found.put(lib.getName(), lib.version());
        }
        return found;
    }

    private boolean counts(Plugin plugin) {
        return plugin == lib || production.computeIfAbsent(plugin,
                p -> production(branch(p.getClass().getClassLoader())));
    }

    /** Generated once, kept in its own file so no config rewrite can lose it. */
    private String serverId() throws java.io.IOException {
        if (server == null) {
            Path file = lib.getDataFolder().toPath().resolve("metrics-id.txt");
            String stored = Files.exists(file) ? Files.readString(file).trim() : "";
            try {
                server = UUID.fromString(stored).toString();
            } catch (IllegalArgumentException unreadable) {
                server = UUID.randomUUID().toString();
                Files.createDirectories(file.getParent());
                Files.writeString(file, server);
            }
        }
        return server;
    }

    // ------------------------------------------------------------------
    // Pure parts, tested without a server
    // ------------------------------------------------------------------

    /**
     * The Lukittu branch a plugin's loader jar was built for.
     *
     * @param loader the class loader of the plugin Bukkit registered
     * @return the branch, or {@code null} when the jar is not a loader
     */
    static @Nullable String branch(ClassLoader loader) {
        for (String platform : PLATFORMS) {
            String branch = branch(loader, platform);
            if (branch != null) {
                return branch;
            }
        }
        return null;
    }

    static @Nullable String branch(ClassLoader loader, String platform) {
        String name = "com.lukittu.loader.generated.GeneratedLoaderData" + platform;
        try {
            // Resources first: a missing class would send a plugin class loader
            // looking through every other plugin, and Paper warns about that.
            if (loader.getResource(name.replace('.', '/') + ".class") == null) {
                return null;
            }
            Class<?> data = Class.forName(name, false, loader);
            // Found in another jar is not this plugin's loader data.
            if (data.getClassLoader() != loader) {
                return null;
            }
            return String.valueOf(data.getField("LUKITTU_BRANCH").get(null));
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }

    static boolean production(@Nullable String branch) {
        return branch != null && !branch.equals("dev");
    }

    static JsonObject payload(String server, JsonObject facts, String lib,
                              Map<String, String> plugins, JsonArray errors) {
        JsonObject body = new JsonObject();
        body.addProperty("server", server);
        facts.entrySet().forEach(entry -> body.add(entry.getKey(), entry.getValue()));
        body.addProperty("lib", lib);
        JsonArray list = new JsonArray();
        plugins.forEach((name, version) -> {
            JsonObject plugin = new JsonObject();
            plugin.addProperty("name", name);
            plugin.addProperty("version", version);
            list.add(plugin);
        });
        body.add("plugins", list);
        body.add("errors", errors);
        return body;
    }

    /** The body as sent, dropping the newest error groups until it fits. */
    static byte[] encode(JsonObject body) {
        JsonArray errors = body.getAsJsonArray("errors");
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        while (bytes.length > MAX_BODY_BYTES && !errors.isEmpty()) {
            errors.remove(errors.size() - 1);
            bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }

    // ------------------------------------------------------------------
    // Server facts
    // ------------------------------------------------------------------

    private static JsonObject facts() {
        Runtime runtime = Runtime.getRuntime();
        JsonObject facts = new JsonObject();
        facts.addProperty("software", Bukkit.getName());
        facts.addProperty("softwareVersion", Bukkit.getVersion());
        facts.addProperty("minecraft", minecraft());
        facts.addProperty("java", System.getProperty("java.version"));
        facts.addProperty("os", System.getProperty("os.name"));
        facts.addProperty("arch", System.getProperty("os.arch"));
        facts.addProperty("cores", runtime.availableProcessors());
        facts.addProperty("memoryMb", runtime.maxMemory() / (1024 * 1024));
        facts.addProperty("players", Bukkit.getOnlinePlayers().size());
        facts.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        facts.addProperty("onlineMode", Bukkit.getOnlineMode());
        facts.addProperty("proxy", proxy());
        return facts;
    }

    private static String minecraft() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError spigot) {
            return Bukkit.getBukkitVersion().split("-")[0];
        }
    }

    @SuppressWarnings("removal") // spigot(): the only portable way to read spigot.yml
    private static @Nullable String proxy() {
        try {
            Object global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration")
                    .getMethod("get").invoke(null);
            Object proxies = global.getClass().getField("proxies").get(global);
            Object velocity = proxies.getClass().getField("velocity").get(proxies);
            if (velocity.getClass().getField("enabled").getBoolean(velocity)) {
                return "velocity";
            }
        } catch (Throwable notPaper) {
            // Not Paper, or its configuration moved: fall through.
        }
        try {
            if (Bukkit.spigot().getSpigotConfig().getBoolean("settings.bungeecord")) {
                return "bungeecord";
            }
        } catch (Throwable unknown) {
            // Unknown is null.
        }
        return null;
    }

    /**
     * The version of the code actually running. A loader's own plugin.yml
     * carries a fixed placeholder version; the real one is in the plugin.yml
     * of the payload its class loader serves, once the loader has started.
     */
    @SuppressWarnings("deprecation") // getDescription(): the portable call, see ExyliaLib#version()
    private String version(Plugin plugin) {
        String cached = versions.get(plugin);
        if (cached != null) {
            return cached;
        }
        try {
            if (plugin.getClass().getMethod("getClazzLoader").invoke(plugin) instanceof ClassLoader payload) {
                try (InputStream in = payload.getResourceAsStream("plugin.yml")) {
                    if (in != null) {
                        String version = YamlConfiguration.loadConfiguration(
                                new InputStreamReader(in, StandardCharsets.UTF_8)).getString("version");
                        if (version != null && !version.isBlank()) {
                            versions.put(plugin, version);
                            return version;
                        }
                    }
                }
            }
        } catch (Throwable notALoader) {
            // A plain plugin, or a loader that has not started: its own version.
        }
        return plugin.getDescription().getVersion();
    }
}
