package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.config.ConfigFile;
import net.exylia.lib.database.DatabaseSettings;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.internal.MongoBackend;
import net.exylia.lib.database.internal.SqlBackend;
import net.exylia.lib.database.internal.SqlSettings;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.redis.RedisSettings;
import net.exylia.lib.redis.internal.RedisRuntime;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The database step of the setup: asks for a shared database, tests it, and points ExyliaLib and every
 * Exylia plugin still on its own H2 file at it.
 *
 * <p>Nothing changes on the running server: each plugin's {@code database.yml} is rewritten and read on the
 * next start. The rows a plugin already has are exported first and imported there after that restart
 * ({@link SetupWizard#afterStartup()}), so a plugin installed with the server loses nothing by moving.
 * {@code share-database} is turned on, so a plugin installed later starts on the same database.
 */
final class SetupNetwork {

    private static final String LIBRARY = "ExyliaLib";
    private static final String FILE = "database.yml";

    /** The values one admin typed, kept so a failed test opens the form with them again. */
    private static final Map<java.util.UUID, Target> DRAFTS = new ConcurrentHashMap<>();

    private static final FormKey<String> ENGINE = FormKey.text("engine");
    private static final FormKey<String> HOST = FormKey.text("host");
    private static final FormKey<Long> PORT = FormKey.integer("port");
    private static final FormKey<String> DATABASE = FormKey.text("database");
    private static final FormKey<String> USER = FormKey.text("user");
    private static final FormKey<String> SERVER = FormKey.text("server");
    private static final FormKey<String> MESSAGES = FormKey.text("messages");
    private static final FormKey<String> REDIS_HOST = FormKey.text("redis_host");
    private static final FormKey<Long> REDIS_PORT = FormKey.integer("redis_port");

    private static final List<String> ENGINES = List.of("mysql", "mariadb", "postgresql", "mongodb");

    /**
     * A shared database as the setup writes it.
     *
     * @param port      {@code 0} for the engine's default
     * @param redis     whether messages between servers go through Redis rather than the database
     * @param serverId  this server's name on the network
     */
    record Target(String engine, String host, int port, String database, String user, String password,
                  String serverId, boolean redis, String redisHost, int redisPort, String redisPassword) {

        Target withPassword(String value) {
            return new Target(engine, host, port, database, user, value, serverId, redis, redisHost, redisPort,
                    redisPassword);
        }

        Target withRedis(String host, int port, String password) {
            return new Target(engine, this.host, this.port, database, user, this.password, serverId, redis, host,
                    port, password);
        }
    }

    private SetupNetwork() {
    }

    // ------------------------------------------------------------------ reading

    /** The shared database ExyliaLib's {@code database.yml} names, or {@code null} when it is H2 or absent. */
    static @Nullable Target current() {
        File file = libraryFile();
        if (file == null || !file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String engine = normal(yaml.getString("database.type", "h2"));
        if (!ENGINES.contains(engine)) {
            return null;
        }
        String block = "database." + engine + ".";
        return new Target(engine, yaml.getString(block + "host", "localhost"), yaml.getInt(block + "port"),
                yaml.getString(block + "database", "minecraft"), yaml.getString(block + "username", ""),
                yaml.getString(block + "password", ""), yaml.getString("database.redis.server-id", "server-1"),
                !"database".equals(normal(yaml.getString("database.cross-server", "redis")))
                        && yaml.getBoolean("database.redis.enabled"),
                yaml.getString("database.redis.host", "localhost"), yaml.getInt("database.redis.port", 6379),
                yaml.getString("database.redis.password", ""));
    }

    static String engineName(String engine) {
        return switch (normal(engine)) {
            case "mariadb" -> "MariaDB";
            case "postgresql" -> "PostgreSQL";
            case "mongodb" -> "MongoDB";
            default -> "MySQL";
        };
    }

    /** Marks the server as needing a restart before what the setup saved is in force. */
    static void needRestart() {
        YamlConfiguration state = SetupWizard.state();
        state.set("restart", true);
        SetupWizard.save(state);
    }

    // ------------------------------------------------------------------ asking

    /** The network card: the connection, its password, Redis when chosen, then the test. */
    static void ask(@NotNull Player player) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        player.closeInventory();
        Target start = DRAFTS.getOrDefault(player.getUniqueId(), java.util.Objects.requireNonNullElseGet(current(),
                () -> new Target("mysql", "localhost", 0, "minecraft", "root", "", "server-1", false,
                        "localhost", 6379, "")));
        PluginInputs inputs = Inputs.of(library);
        inputs.answered(player, inputs.form(player, Phrases.tr("{primary}Shared database"))
                        .field(ENGINE, FormField.choice(ENGINE, Phrases.tr("Engine"), List.of(
                                new FormField.Option("mysql", "MySQL"),
                                new FormField.Option("mariadb", "MariaDB"),
                                new FormField.Option("postgresql", "PostgreSQL"),
                                new FormField.Option("mongodb", "MongoDB"))).defaultValue(start.engine()))
                        .field(HOST, FormField.text(HOST, Phrases.tr("Host")).defaultValue(start.host()))
                        .field(PORT, FormField.integer(PORT, Phrases.tr("Port")).optional()
                                .hint(Phrases.tr("Empty for the engine's default"))
                                .defaultValue(start.port() > 0 ? (long) start.port() : null)
                                .validate(port -> port >= 0 && port <= 65535, Phrases.tr("A port is 1 to 65535")))
                        .field(DATABASE, FormField.text(DATABASE, Phrases.tr("Database name")).defaultValue(start.database()))
                        .field(USER, FormField.text(USER, Phrases.tr("User")).optional().defaultValue(start.user()))
                        .field(SERVER, FormField.text(SERVER, Phrases.tr("This server's name")).defaultValue(start.serverId())
                                .hint(Phrases.tr("Its name on the proxy, such as lobby-1"))
                                .validate(name -> name.matches("[A-Za-z0-9_.-]{1,48}"),
                                        Phrases.tr("Letters, numbers, dots, dashes and underscores")))
                        .field(MESSAGES, FormField.choice(MESSAGES, Phrases.tr("Messages between servers"), List.of(
                                new FormField.Option("database", Phrases.tr("Through the database")),
                                new FormField.Option("redis", "Redis"))).defaultValue(start.redis() ? "redis" : "database"))
                        .submitLabel(Phrases.tr("Next"))
                        .open(),
                values -> askPassword(player, read(values, start)),
                () -> SetupMenu.open(player, SetupMenu.NETWORK));
    }

    private static Target read(FormValues values, Target start) {
        return new Target(values.get(ENGINE), values.get(HOST).trim(), values.getOr(PORT, 0L).intValue(),
                values.get(DATABASE).trim(), values.getOr(USER, "").trim(), start.password(),
                values.get(SERVER).trim(), "redis".equals(values.get(MESSAGES)), start.redisHost(),
                start.redisPort(), start.redisPassword());
    }

    private static void askPassword(Player player, Target target) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        DRAFTS.put(player.getUniqueId(), target);
        PluginInputs inputs = Inputs.of(library);
        // Asked on its own: a secret answer never shows in chat, logs or a Discord bridge.
        inputs.answered(player, inputs.text(player, Phrases.tr("{primary}Database password {muted}(- for none)"))
                        .sensitive().open(),
                password -> {
                    Target typed = target.withPassword(blankDash(password));
                    if (typed.redis()) askRedis(player, typed);
                    else test(player, typed);
                },
                () -> SetupMenu.open(player, SetupMenu.NETWORK));
    }

    private static void askRedis(Player player, Target target) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        DRAFTS.put(player.getUniqueId(), target);
        PluginInputs inputs = Inputs.of(library);
        inputs.answered(player, inputs.form(player, Phrases.tr("{primary}Redis"))
                        .field(REDIS_HOST, FormField.text(REDIS_HOST, Phrases.tr("Host")).defaultValue(target.redisHost()))
                        .field(REDIS_PORT, FormField.integer(REDIS_PORT, Phrases.tr("Port")).defaultValue((long) target.redisPort())
                                .validate(port -> port > 0 && port <= 65535, Phrases.tr("A port is 1 to 65535")))
                        .submitLabel(Phrases.tr("Next"))
                        .open(),
                values -> inputs.answered(player,
                        inputs.text(player, Phrases.tr("{primary}Redis password {muted}(- for none)")).sensitive().open(),
                        password -> test(player, target.withRedis(values.get(REDIS_HOST).trim(),
                                (int) values.getLong(REDIS_PORT), blankDash(password))),
                        () -> SetupMenu.open(player, SetupMenu.NETWORK)),
                () -> SetupMenu.open(player, SetupMenu.NETWORK));
    }

    // ------------------------------------------------------------------ testing

    private static void test(Player player, Target target) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        DRAFTS.put(player.getUniqueId(), target);
        Text.of(SetupWizard.prefix() + Phrases.tr("{letters}Testing the connection to {highlight}{0}{letters}…",
                Text.escape(target.host()))).send(player);
        Tasks.of(library).runAsync(() -> {
            String problem = probe(library, target);
            Tasks.of(library).runAtEntity(player, () -> {
                if (problem == null) {
                    confirmMove(player, target);
                    return;
                }
                Text.of("[sound:ENTITY_VILLAGER_NO|0.7|1.0]" + SetupWizard.prefix()
                        + Phrases.tr("{error}Could not connect: {letters}{0}", Text.escape(problem))).send(player);
                Text.of(SetupWizard.prefix() + Phrases.tr("{letters}Nothing was saved. Click {highlight}Network {letters}to try again with the same values."))
                        .send(player);
                SetupMenu.open(player, SetupMenu.NETWORK);
            });
        });
    }

    /** Connects with these values and lets go: {@code null} when everything answered, or why not. */
    static @Nullable String probe(ExyliaLib library, Target target) {
        try {
            DatabaseSettings settings = settings(target);
            SqlSettings sql = settings.toSql(library.getDataFolder().toPath());
            if (settings.mongo()) {
                try (MongoBackend backend = MongoBackend.open(sql, "setup")) {
                    backend.ping();
                }
            } else {
                try (SqlBackend ignored = SqlBackend.open(sql, "setup")) {
                    // Opening the pool is the test: Hikari connects eagerly.
                }
            }
            if (target.redis()) {
                RedisRuntime.probe(redis(target));
            }
            return null;
        } catch (RuntimeException | LinkageError failure) {
            return reason(failure, target);
        }
    }

    /** The deepest cause, which names what went wrong, without any password in it. */
    static String reason(Throwable failure, Target target) {
        Throwable deepest = failure;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage() == null ? deepest.getClass().getSimpleName() : deepest.getMessage();
        for (String secret : List.of(target.password(), target.redisPassword())) {
            if (secret != null && !secret.isEmpty()) message = message.replace(secret, "***");
        }
        message = message.replace('\n', ' ').trim();
        return message.length() > 160 ? message.substring(0, 159) + "…" : message;
    }

    static DatabaseSettings settings(Target target) {
        DatabaseSettings.Server server = new DatabaseSettings.Server(target.host(), target.port(), target.database(),
                target.user(), target.password());
        DatabaseSettings.Database defaults = new DatabaseSettings.Database();
        return new DatabaseSettings(new DatabaseSettings.Database(target.engine(), defaults.settings(), defaults.h2(),
                target.engine().equals("mysql") ? server : defaults.mysql(),
                target.engine().equals("mariadb") ? server : defaults.mariadb(),
                target.engine().equals("postgresql") ? server : defaults.postgresql(),
                target.engine().equals("mongodb")
                        ? new DatabaseSettings.Mongo(target.host(), target.port(), target.database(), target.user(),
                        target.password(), "")
                        : defaults.mongodb(),
                redis(target),
                target.redis() ? DatabaseSettings.CROSS_SERVER_REDIS : DatabaseSettings.CROSS_SERVER_DATABASE));
    }

    static RedisSettings redis(Target target) {
        RedisSettings d = new RedisSettings();
        return new RedisSettings(target.redis(), target.redisHost(), target.redisPort(), target.redisPassword(),
                d.username(), d.ssl(), d.database(), d.poolSize(), d.ttlSeconds(), d.localSeconds(), d.localEntries(),
                d.keyPrefix(), target.serverId(), d.previousIds());
    }

    // ------------------------------------------------------------------ moving

    private static void confirmMove(Player player, Target target) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        List<Plugin> moving = onTheirOwn();
        PluginInputs inputs = Inputs.of(library);
        inputs.answered(player, inputs.confirm(player, Phrases.tr(
                                "{success}Connected. {letters}Move {highlight}{0} {letters}plugins to {highlight}{1}{letters}? Their rows are copied there on the next restart.",
                                moving.size(), engineName(target.engine())))
                        .confirmLabel(Phrases.tr("Move"))
                        .open(),
                yes -> {
                    if (yes) move(player, target, moving);
                    else SetupMenu.open(player, SetupMenu.NETWORK);
                },
                () -> SetupMenu.open(player, SetupMenu.NETWORK));
    }

    /**
     * Exylia plugins whose data is in their own H2 file: every plugin with a database view, and every plugin
     * that depends on the library and has a {@code database.yml}. A plugin already on a database server is
     * the owner's choice and is left alone.
     */
    @SuppressWarnings("deprecation") // getDescription: the call that exists on every platform the library runs on
    static List<Plugin> onTheirOwn() {
        Set<String> names = new LinkedHashSet<>(Databases.registeredPlugins());
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            boolean dependent = plugin.getDescription().getDepend().contains(LIBRARY)
                    || plugin.getDescription().getSoftDepend().contains(LIBRARY);
            if (dependent && new File(plugin.getDataFolder(), FILE).isFile()) names.add(plugin.getName());
        }
        names.remove(LIBRARY);
        List<Plugin> out = new ArrayList<>();
        for (String name : names) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin == null) continue;
            File file = new File(plugin.getDataFolder(), FILE);
            String engine = file.isFile() ? normal(YamlConfiguration.loadConfiguration(file).getString("database.type", "h2")) : "h2";
            if (engine.equals("h2")) out.add(plugin);
        }
        return out;
    }

    private static void move(Player player, Target target, List<Plugin> moving) {
        ExyliaLib library = SetupWizard.library();
        if (library == null) return;
        // The library's own rows (notices, logs, playtime) move with the rest, when it keeps any.
        List<Plugin> all = new ArrayList<>(moving);
        if (onH2(library)) all.add(library);
        Map<String, String> dumps = new ConcurrentHashMap<>();
        Set<String> failed = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<?>> exports = new ArrayList<>();
        for (Plugin plugin : all) {
            if (Databases.find(plugin.getName()) == null || !h2File(plugin).isFile()) continue;
            exports.add(Transfers.of(plugin).export(plugin.getDataFolder().toPath().resolve("dumps"))
                    .thenAccept(report -> {
                        if (report.successful() && report.file() != null) {
                            dumps.put(plugin.getName(), report.file().toAbsolutePath().toString());
                        } else {
                            failed.add(plugin.getName());
                            library.getLogger().warning("Setup: could not export " + plugin.getName()
                                    + " (" + report.outcome() + "), so it stays on its own file: " + report.problems());
                        }
                    }));
        }
        CompletableFuture.allOf(exports.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                Tasks.of(library).runAsync(() -> {
                    List<String> moved = new ArrayList<>();
                    write(libraryFile(), target);
                    for (Plugin plugin : moving) {
                        if (failed.contains(plugin.getName())) continue;
                        write(new File(plugin.getDataFolder(), FILE), target);
                        moved.add(plugin.getName());
                    }
                    YamlConfiguration state = SetupWizard.state();
                    Set<String> together = new LinkedHashSet<>(state.getStringList("moved"));
                    together.addAll(moved);
                    state.set("moved", new ArrayList<>(together));
                    dumps.keySet().removeAll(failed);
                    dumps.forEach((name, file) -> state.set("imports." + name, file));
                    state.set("restart", true);
                    SetupWizard.save(state);
                    share(true);
                    DRAFTS.remove(player.getUniqueId());
                    Tasks.of(library).runAtEntity(player, () -> {
                        SetupMenu.done(player, Phrases.tr("{letters}Saved. {highlight}{0} {letters}plugins move to {highlight}{1} {warning}on the next restart{letters}.",
                                moved.size(), engineName(target.engine())));
                        if (!failed.isEmpty()) {
                            Text.of(SetupWizard.prefix() + Phrases.tr("{warning}Kept on their own file: {letters}{0}",
                                    String.join(", ", failed))).send(player);
                        }
                        SetupMenu.open(player, SetupMenu.NETWORK);
                    });
                }));
    }

    /** The single-server card while on a network: the moved plugins go back to their own files. */
    static void single(@NotNull Player player) {
        ExyliaLib library = SetupWizard.library();
        Target network = current();
        if (library == null || network == null) return;
        player.closeInventory();
        PluginInputs inputs = Inputs.of(library);
        inputs.answered(player, inputs.confirm(player, Phrases.tr(
                                "{letters}Back to one server? The plugins the setup moved use their own files again after a restart. What they saved in {highlight}{0} {letters}stays there.",
                                engineName(network.engine())))
                        .confirmLabel(Phrases.tr("Go back"))
                        .dangerous()
                        .open(),
                yes -> {
                    if (!yes) {
                        SetupMenu.open(player, SetupMenu.NETWORK);
                        return;
                    }
                    Tasks.of(library).runAsync(() -> {
                        YamlConfiguration state = SetupWizard.state();
                        List<String> moved = state.getStringList("moved");
                        toH2(libraryFile());
                        for (String name : moved) {
                            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
                            if (plugin != null) toH2(new File(plugin.getDataFolder(), FILE));
                        }
                        state.set("moved", null);
                        state.set("imports", null);
                        state.set("restart", true);
                        SetupWizard.save(state);
                        share(false);
                        Tasks.of(library).runAtEntity(player, () -> {
                            SetupMenu.done(player, Phrases.tr("{letters}Saved. Every plugin uses its own file {warning}after a restart{letters}."));
                            SetupMenu.open(player, SetupMenu.NETWORK);
                        });
                    });
                },
                () -> SetupMenu.open(player, SetupMenu.NETWORK));
    }

    // ------------------------------------------------------------------ files

    /** Points one {@code database.yml} at the shared database, keeping its comments and every other key. */
    static void write(@Nullable File file, Target target) {
        if (file == null) return;
        YamlConfiguration yaml = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        if (!yaml.contains("config-version")) yaml.set("config-version", 2);
        yaml.set("database.type", target.engine());
        String block = "database." + target.engine() + ".";
        yaml.set(block + "host", target.host());
        yaml.set(block + "port", target.port());
        yaml.set(block + "database", target.database());
        yaml.set(block + "username", target.user());
        yaml.set(block + "password", target.password());
        yaml.set("database.cross-server", target.redis() ? DatabaseSettings.CROSS_SERVER_REDIS
                : DatabaseSettings.CROSS_SERVER_DATABASE);
        yaml.set("database.redis.enabled", target.redis());
        if (target.redis()) {
            yaml.set("database.redis.host", target.redisHost());
            yaml.set("database.redis.port", target.redisPort());
            yaml.set("database.redis.password", target.redisPassword());
        }
        yaml.set("database.redis.server-id", target.serverId());
        save(yaml, file);
    }

    private static void toH2(@Nullable File file) {
        if (file == null || !file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("database.type", "h2");
        yaml.set("database.cross-server", DatabaseSettings.CROSS_SERVER_REDIS);
        yaml.set("database.redis.enabled", false);
        save(yaml, file);
    }

    private static void save(YamlConfiguration yaml, File file) {
        try {
            java.nio.file.Files.createDirectories(file.toPath().getParent());
            yaml.save(file);
        } catch (IOException failed) {
            ExyliaLib library = SetupWizard.library();
            if (library != null) library.getLogger().warning("Setup: could not write " + file + ": " + failed.getMessage());
        }
    }

    private static void share(boolean on) {
        ConfigFile<LibrarySettings> file = LibrarySettings.file();
        if (file == null) return;
        file.set("share-database", on);
        LibrarySettings.reload();
    }

    private static @Nullable File libraryFile() {
        ExyliaLib library = SetupWizard.library();
        return library == null ? null : new File(library.getDataFolder(), FILE);
    }

    private static boolean onH2(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE);
        return !file.isFile() || normal(YamlConfiguration.loadConfiguration(file).getString("database.type", "h2")).equals("h2");
    }

    /** Where a plugin's embedded database is, as its {@code database.yml} says. */
    private static File h2File(Plugin plugin) {
        File config = new File(plugin.getDataFolder(), FILE);
        String name = config.isFile()
                ? YamlConfiguration.loadConfiguration(config).getString("database.h2.file", "database/h2")
                : "database/h2";
        return plugin.getDataFolder().toPath().resolve((name == null || name.isBlank() ? "database/h2" : name) + ".mv.db").toFile();
    }

    private static String normal(@Nullable String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (clean) {
            case "postgres", "pgsql" -> "postgresql";
            case "mongo" -> "mongodb";
            default -> clean;
        };
    }

    private static String blankDash(String answer) {
        String clean = answer == null ? "" : answer.trim();
        return clean.equals("-") ? "" : clean;
    }
}
