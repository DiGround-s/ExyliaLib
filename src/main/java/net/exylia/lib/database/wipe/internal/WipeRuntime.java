package net.exylia.lib.database.wipe.internal;

import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.database.Table;
import net.exylia.lib.database.transfer.TransferOutcome;
import net.exylia.lib.database.transfer.TransferReport;
import net.exylia.lib.database.transfer.Transfers;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.log.Logs;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.Message;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Wipes and player resets: the catalog of every table, the backup, the delete,
 * and what every server of the network does about it.
 *
 * <p>Started by the library. Every entry point returns at once and works on a
 * background thread; nothing here blocks the game.
 */
public final class WipeRuntime {

    /** Who may wipe, and who may stay on a locked server. */
    public static final String PERMISSION = "exylialib.admin.wipe";

    /** The descriptor {@code @Table} leaves in the constant pool of a class it annotates. */
    private static final byte[] TABLE_DESCRIPTOR =
            ("L" + Table.class.getName().replace('.', '/') + ";").getBytes(StandardCharsets.ISO_8859_1);

    /** The file that tells the next start to delete the vanilla player files. */
    static final String VANILLA_MARKER = "wipe-vanilla-files";

    /**
     * How long a player reset waits after locking the player out, so the saves
     * their quit started land before their rows are deleted.
     */
    // ponytail: a fixed wait; a quit save slower than this lands after the delete.
    // Upgrade path: plugins acknowledge their quit save over the channel.
    static final Duration QUIT_GRACE = Duration.ofSeconds(4);

    /** How long a player stays locked out at most, should the unlock message be lost. */
    static final Duration LOCK_CEILING = Duration.ofMinutes(5);

    private static volatile @Nullable Plugin library;
    private static volatile @Nullable Channel channel;
    private static volatile @Nullable Channel.Subscription subscription;
    private static volatile @Nullable Listener listener;

    private static final Map<String, List<Consumer<UUID>>> HOOKS = new ConcurrentHashMap<>();
    private static final Map<String, Plugin> HOOK_OWNERS = new ConcurrentHashMap<>();
    private static final Set<Plugin> SCANNED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<UUID, Long> LOCKED_PLAYERS = new ConcurrentHashMap<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile boolean serverLocked;

    private WipeRuntime() {
    }

    /**
     * What a finished wipe did.
     *
     * @param failure  why nothing was deleted, or {@code null} when it ran
     * @param removed  rows removed, plugin by plugin and kind by kind
     * @param backups  the dumps taken first
     * @param problems tables that could not be emptied, one line each
     * @param skipped  tables a player reset left alone because they name no owner
     * @param restart  whether the servers have to restart to apply it
     */
    public record Outcome(@Nullable String failure, @NotNull Map<String, Map<DataKind, Long>> removed,
                          @NotNull List<Path> backups, @NotNull List<String> problems, int skipped,
                          boolean restart) {

        static Outcome failed(String why) {
            return new Outcome(why, Map.of(), List.of(), List.of(), 0, false);
        }

        /** Rows removed in all. */
        public long rows() {
            return removed.values().stream().flatMap(kinds -> kinds.values().stream()).mapToLong(Long::longValue).sum();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Starts listening: the network channel, the login gate, and a vanilla
     * wipe asked for before the last restart.
     *
     * @param plugin the library
     */
    public static void init(@NotNull Plugin plugin) {
        library = plugin;
        deleteMarkedVanillaFiles(plugin);
        Channel opened = Channels.of(plugin).channel("wipes");
        channel = opened;
        subscription = opened.subscribe(WipeRuntime::receive);
        Listener gate = new LoginGate();
        listener = gate;
        Bukkit.getPluginManager().registerEvents(gate, plugin);
    }

    /** Stops listening, when the library disables. */
    public static void release() {
        Channel.Subscription open = subscription;
        if (open != null) open.close();
        Listener gate = listener;
        if (gate != null) HandlerList.unregisterAll(gate);
        subscription = null;
        channel = null;
        listener = null;
        library = null;
        HOOKS.clear();
        HOOK_OWNERS.clear();
        SCANNED.clear();
        LOCKED_PLAYERS.clear();
        serverLocked = false;
        RUNNING.set(false);
    }

    // ---------------------------------------------------------------- hooks

    /**
     * Adds what a plugin does about a reset player's rows it keeps in memory.
     *
     * @param plugin who listens
     * @param hook   what to run, on a background thread, on every server
     */
    public static void hook(@NotNull Plugin plugin, @NotNull Consumer<UUID> hook) {
        Plugin known = HOOK_OWNERS.get(plugin.getName());
        if (known != plugin) {
            // A new load of the plugin: the old load's hooks hold a dead classloader.
            HOOK_OWNERS.put(plugin.getName(), plugin);
            HOOKS.put(plugin.getName(), new CopyOnWriteArrayList<>());
        }
        HOOKS.get(plugin.getName()).add(hook);
    }

    /** Forgets a plugin's hooks, when it disables. */
    public static void release(@NotNull Plugin plugin) {
        if (HOOK_OWNERS.remove(plugin.getName(), plugin)) HOOKS.remove(plugin.getName());
        SCANNED.remove(plugin);
    }

    static void runHooks(UUID player) {
        HOOKS.forEach((plugin, hooks) -> {
            for (Consumer<UUID> hook : hooks) {
                try {
                    hook.accept(player);
                } catch (Throwable failure) {
                    Plugin owner = HOOK_OWNERS.get(plugin);
                    if (owner != null) Debug.of(owner).error("A player reset hook failed for " + player, failure);
                }
            }
        });
    }

    // ---------------------------------------------------------------- catalog

    /**
     * Every table of every plugin, each physical table once. Blocking: a
     * background thread only.
     *
     * <p>Scans each Exylia plugin's jar first, so a table the plugin only
     * registers on first use is in the list too.
     *
     * @return the tables, sorted by plugin and then table
     */
    public static @NotNull List<WipeTable> catalog() {
        Plugin plugin = library;
        if (plugin != null) discover(plugin);
        List<WipeTable> raw = new ArrayList<>();
        for (String name : Databases.registeredPlugins()) {
            PluginDatabase database = Databases.find(name);
            if (database == null) continue;
            String location = database.location();
            for (Repository<?> repository : database.tables().values()) {
                raw.add(WipeTable.of(database.plugin().getName(), location, repository));
            }
        }
        return merge(raw, warning -> {
            Plugin owner = library;
            if (owner != null) Debug.of(owner).warn(warning);
        });
    }

    /**
     * One entry per physical table: two views of one database name the same
     * table once. Two records that disagree on its kind leave it unclassified.
     *
     * @param raw  every registered repository, as read
     * @param warn told about each disagreement
     * @return the merged tables, sorted by plugin and then table
     */
    static @NotNull List<WipeTable> merge(@NotNull List<WipeTable> raw, @NotNull Consumer<String> warn) {
        List<WipeTable> sorted = new ArrayList<>(raw);
        // The plugin's own entry first, so a shared table is listed under a
        // plugin that is still there rather than whichever view came first.
        sorted.sort(java.util.Comparator.comparing(WipeTable::source, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(WipeTable::table, String.CASE_INSENSITIVE_ORDER));
        Map<String, WipeTable> merged = new LinkedHashMap<>();
        for (WipeTable table : sorted) {
            WipeTable known = merged.get(table.key());
            if (known == null) {
                merged.put(table.key(), table);
            } else if (known.kind() != table.kind() && known.kind() != DataKind.UNSPECIFIED) {
                warn.accept("The table " + table.table() + " is declared " + known.kind() + " by " + known.source()
                        + " and " + table.kind() + " by " + table.source() + "; wipes treat it as UNSPECIFIED"
                        + " until both agree.");
                merged.put(table.key(), known.withKind(DataKind.UNSPECIFIED));
            }
        }
        List<WipeTable> result = new ArrayList<>(merged.values());
        result.sort(java.util.Comparator.comparing(WipeTable::plugin, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(WipeTable::table, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Registers every {@code @Table} record in every Exylia plugin's jar, once per load. */
    private static void discover(Plugin library) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin == library || !plugin.isEnabled() || !dependsOnLibrary(plugin, library.getName())
                    || !SCANNED.add(plugin)) {
                continue;
            }
            for (Class<?> type : tableRecords(plugin)) {
                try {
                    Databases.of(plugin).repository(type);
                } catch (Throwable unusable) {
                    Debug.of(library).log("Skipped " + type.getName() + " of " + plugin.getName()
                            + " while listing tables: " + unusable);
                }
            }
        }
    }

    private static boolean dependsOnLibrary(Plugin plugin, String library) {
        return Stream.concat(plugin.getDescription().getDepend().stream(),
                plugin.getDescription().getSoftDepend().stream()).anyMatch(library::equalsIgnoreCase);
    }

    /**
     * The {@code @Table} records in a plugin's jar.
     *
     * <p>The bytes are searched before a class is loaded, so a jar of a
     * thousand classes loads only its handful of records; and loaded without
     * being initialised, so no static block of the plugin runs.
     */
    static @NotNull List<Class<?>> tableRecords(@NotNull Plugin plugin) {
        Path jar;
        try {
            jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException noJar) {
            return List.of();
        }
        return tableRecords(jar, plugin.getClass().getClassLoader());
    }

    static @NotNull List<Class<?>> tableRecords(@NotNull Path jar, @NotNull ClassLoader loader) {
        if (!Files.isRegularFile(jar)) return List.of();
        List<Class<?>> found = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("-info")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (!contains(bytes, TABLE_DESCRIPTOR)) continue;
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                try {
                    Class<?> type = Class.forName(className, false, loader);
                    if (type.isRecord() && type.isAnnotationPresent(Table.class)) found.add(type);
                } catch (Throwable unloadable) {
                    // A record of an optional integration whose dependency is
                    // missing: it cannot be stored here either.
                }
            }
        } catch (IOException unreadable) {
            return found;
        }
        return found;
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- counting

    /**
     * Reads the catalog into a request and counts every candidate table, off
     * the main thread. The request fills in as answers arrive, so a screen
     * drawing it shows "counting" until each one is known.
     *
     * @param request what to plan
     * @return completes when every count is in
     */
    public static @NotNull CompletableFuture<WipeRequest> plan(@NotNull WipeRequest request) {
        return async(() -> {
            request.tables(catalog());
            for (List<WipeTable> tables : request.byPlugin().values()) {
                for (WipeTable table : tables) {
                    try {
                        request.count(table, count(table, request.player()));
                    } catch (RuntimeException unreadable) {
                        request.count(table, -1L);
                    }
                }
            }
            return request;
        });
    }

    static long count(WipeTable table, @Nullable UUID player) {
        Repository<?> repository = table.repository();
        if (repository == null) return 0L;
        var query = player == null ? repository.all() : repository.where(table.owner(), table.ownerValue(player));
        if (table.tenant() != null) query.where(WipeTable.TENANT_COLUMN, table.tenant());
        return query.count().join();
    }

    /**
     * Deletes a table's rows in scope: all of them, or one player's.
     *
     * <p>Through the storage rather than the repository, so a repository the
     * wipe has already frozen still has its rows deleted. The shared cache is
     * dropped for the table here and on every peer.
     */
    static long delete(WipeTable table, @Nullable UUID player) {
        Repository<?> repository = table.repository();
        if (repository == null) return 0L;
        List<String> columns = new ArrayList<>(2);
        List<Object> values = new ArrayList<>(2);
        if (player != null) {
            columns.add(table.owner());
            values.add(table.ownerValue(player));
        }
        if (table.tenant() != null) {
            columns.add(WipeTable.TENANT_COLUMN);
            values.add(table.tenant());
        }
        if (columns.isEmpty()) return repository.storage().deleteAll(repository.model()).join();
        return repository.storage().deleteWhere(repository.model(), columns, values, 0).join();
    }

    // ---------------------------------------------------------------- running

    /**
     * Runs a planned request: the backup, the delete, and what every server
     * does about it.
     *
     * <p>{@code report} is told first; only then are players kicked and the
     * servers locked, so whoever ran it reads the result before their own
     * screen goes.
     *
     * @param request what to delete, already planned or not
     * @param actor   who asked, for the staff log
     * @param dumps   where the backups go
     * @param report  told what happened, on a background thread
     */
    public static void run(@NotNull WipeRequest request, @NotNull CommandSender actor, @NotNull Path dumps,
                           @NotNull Consumer<Outcome> report) {
        if (!RUNNING.compareAndSet(false, true)) {
            report.accept(Outcome.failed(Phrases.tr("Another wipe is still running.")));
            return;
        }
        async(() -> {
            try {
                Outcome outcome = request.whole() ? runWhole(request, actor, dumps) : runPlayer(request, actor, dumps);
                report.accept(outcome);
                if (outcome.failure() == null && request.whole()) {
                    if (request.vanilla()) publish("vanilla");
                    publish("lock");
                }
                return outcome;
            } catch (Throwable failure) {
                Plugin plugin = library;
                if (plugin != null) Debug.of(plugin).error("A wipe failed in a way it should have reported.", failure);
                report.accept(Outcome.failed(String.valueOf(failure.getMessage())));
                return null;
            } finally {
                RUNNING.set(false);
            }
        });
    }

    private static Outcome runWhole(WipeRequest request, CommandSender actor, Path dumps) {
        request.tables(catalog());
        List<WipeTable> targets = request.targets();
        if (targets.isEmpty()) return Outcome.failed(Phrases.tr("Nothing matches: no table of those kinds."));
        List<Path> backups = new ArrayList<>();
        String refused = backup(targets, dumps, backups);
        if (refused != null) return Outcome.failed(refused);

        // Frozen everywhere before the rows go, so nothing still in memory
        // writes them back between the delete and the restart.
        Map<String, List<String>> bySource = new TreeMap<>();
        for (WipeTable table : targets) {
            bySource.computeIfAbsent(table.source(), key -> new ArrayList<>()).add(table.table());
        }
        bySource.forEach((source, tables) -> publish("freeze|" + source + "|" + String.join(",", tables)));
        bySource.forEach(WipeRuntime::freeze);

        Map<String, Map<DataKind, Long>> removed = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> problems = new ArrayList<>();
        for (WipeTable table : targets) {
            try {
                long rows = delete(table, null);
                removed.computeIfAbsent(table.plugin(), key -> new TreeMap<>()).merge(table.kind(), rows, Long::sum);
            } catch (RuntimeException failure) {
                problems.add(table.plugin() + " " + table.table() + ": " + rootMessage(failure));
            }
        }
        Plugin plugin = library;
        if (plugin != null) {
            Logs.of(plugin).record("data-wipe", actor, describe(request, removed));
        }
        return new Outcome(null, removed, backups, problems, 0, true);
    }

    private static Outcome runPlayer(WipeRequest request, CommandSender actor, Path dumps) {
        UUID player = java.util.Objects.requireNonNull(request.player());
        Optional<ExyliaPlayer> found = ExyliaPlayers.resolve(player).join();
        if (found.isPresent() && !found.get().isHere() && found.get().isConnected()) {
            return Outcome.failed(Phrases.tr("{0} is online on {1}. A player is reset while offline or on this server.",
                    request.playerName(), found.get().serverName().orElse("?")));
        }
        request.tables(catalog());
        List<WipeTable> targets = request.targets();
        int skipped = WipePanels.skipped(request);
        if (targets.isEmpty() && !request.vanilla()) {
            return Outcome.failed(Phrases.tr("Nothing matches: no table of those kinds names a player."));
        }
        List<Path> backups = new ArrayList<>();
        String refused = backup(targets, dumps, backups);
        if (refused != null) return Outcome.failed(refused);

        publish("plock|" + player + '|' + (System.currentTimeMillis() + LOCK_CEILING.toMillis()));
        sleep(QUIT_GRACE);
        Map<String, Map<DataKind, Long>> removed = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> problems = new ArrayList<>();
        try {
            for (WipeTable table : targets) {
                try {
                    long rows = delete(table, player);
                    removed.computeIfAbsent(table.plugin(), key -> new TreeMap<>()).merge(table.kind(), rows, Long::sum);
                } catch (RuntimeException failure) {
                    problems.add(table.plugin() + " " + table.table() + ": " + rootMessage(failure));
                }
            }
            publish("reset|" + player + '|' + (request.vanilla() ? '1' : '0'));
            // A moment for every server to run its hooks before the player can come back.
            sleep(Duration.ofSeconds(2));
        } finally {
            publish("punlock|" + player);
        }
        Plugin plugin = library;
        if (plugin != null) {
            Logs.of(plugin).record("player-reset", actor, player, request.playerName(), describe(request, removed));
        }
        return new Outcome(null, removed, backups, problems, skipped, false);
    }

    /**
     * Takes one dump per plugin whose tables are about to change.
     *
     * @return why the wipe is cancelled, or {@code null} when every dump was written
     */
    private static @Nullable String backup(List<WipeTable> targets, Path dumps, List<Path> written) {
        Set<String> sources = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        targets.forEach(table -> sources.add(table.source()));
        for (String source : sources) {
            PluginDatabase database = Databases.find(source);
            if (database == null) return Phrases.tr("{0} is no longer loaded, so it could not be backed up.", source);
            TransferReport report = Transfers.of(database.plugin()).export(dumps).join();
            if (report.outcome() == TransferOutcome.FAILED) {
                return Phrases.tr("The backup of {0} failed: {1}", source, String.join("; ", report.problems()));
            }
            if (report.file() != null) written.add(report.file());
        }
        return null;
    }

    private static String describe(WipeRequest request, Map<String, Map<DataKind, Long>> removed) {
        StringBuilder detail = new StringBuilder(request.preset().name().toLowerCase(Locale.ROOT))
                .append(' ').append(request.kinds());
        if (request.only() != null) detail.append(" plugin=").append(request.only());
        if (request.table() != null) detail.append(" table=").append(request.table());
        if (request.vanilla()) detail.append(" +vanilla");
        removed.forEach((plugin, kinds) -> detail.append(" | ").append(plugin).append(' ').append(kinds));
        return detail.toString();
    }

    // ---------------------------------------------------------------- the network

    private static void publish(String payload) {
        Channel open = channel;
        if (open != null) {
            open.publish(payload);
        } else {
            receive(new Message("", payload, true));
        }
    }

    /** What every server does when a wipe announces itself; this one included. */
    static void receive(Message message) {
        String[] parts = message.payload().split("\\|", -1);
        try {
            switch (parts[0]) {
                case "freeze" -> freeze(parts[1], List.of(parts[2].split(",")));
                case "lock" -> lockServer();
                case "vanilla" -> markVanillaFiles();
                case "plock" -> {
                    UUID player = UUID.fromString(parts[1]);
                    LOCKED_PLAYERS.put(player, Long.parseLong(parts[2]));
                    kick(Bukkit.getPlayer(player));
                }
                case "reset" -> {
                    UUID player = UUID.fromString(parts[1]);
                    runHooks(player);
                    if ("1".equals(parts[2])) deleteVanillaFiles(player);
                }
                case "punlock" -> LOCKED_PLAYERS.remove(UUID.fromString(parts[1]));
                default -> { }
            }
        } catch (RuntimeException malformed) {
            Plugin plugin = library;
            if (plugin != null) Debug.of(plugin).warn("Ignored a malformed wipe message: " + message.payload());
        }
    }

    /** Drops every write to the named tables of one plugin until the restart. */
    static void freeze(String source, List<String> tables) {
        PluginDatabase database = Databases.find(source);
        if (database == null) return;
        database.tables().forEach((name, repository) -> {
            if (tables.stream().anyMatch(name::equalsIgnoreCase)) repository.freeze();
        });
    }

    private static void lockServer() {
        serverLocked = true;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission(PERMISSION)) kick(online);
        }
    }

    /** Whether the server refuses players until its restart. */
    public static boolean serverLocked() {
        return serverLocked;
    }

    /** Whether a player is locked out by a reset that is running. */
    static boolean playerLocked(UUID player) {
        Long until = LOCKED_PLAYERS.get(player);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            LOCKED_PLAYERS.remove(player, until);
            return false;
        }
        return true;
    }

    private static void kick(@Nullable Player player) {
        Plugin plugin = library;
        if (player == null || plugin == null) return;
        Tasks.of(plugin).runAtEntity(player, () -> player.kick(Text.of(kickMessage()).build()));
    }

    static String kickMessage() {
        return serverLocked
                ? Phrases.tr("{primary}&lDATA WIPE") + "\n\n" + Phrases.tr("{letters}This server's data was wiped.")
                        + "\n" + Phrases.tr("{letters}It restarts soon; join again in a moment.")
                : Phrases.tr("{primary}&lDATA RESET") + "\n\n" + Phrases.tr("{letters}Your data is being reset.")
                        + "\n" + Phrases.tr("{letters}Join again in a few seconds.");
    }

    /** Refuses a login while the server or the player is locked. */
    static final class LoginGate implements Listener {

        @EventHandler(priority = EventPriority.HIGH)
        void onLogin(PlayerLoginEvent event) {
            Player player = event.getPlayer();
            boolean refused = (serverLocked && !player.hasPermission(PERMISSION))
                    || playerLocked(player.getUniqueId());
            if (refused) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, Text.of(kickMessage()).build());
            }
        }
    }

    // ---------------------------------------------------------------- vanilla files

    private static void markVanillaFiles() {
        Plugin plugin = library;
        if (plugin == null) return;
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            Files.writeString(plugin.getDataFolder().toPath().resolve(VANILLA_MARKER),
                    "Delete the main world's playerdata, stats and advancements on the next start.\n");
        } catch (IOException failure) {
            Debug.of(plugin).error("Could not mark the vanilla player files for deletion.", failure);
        }
    }

    /**
     * Deletes the main world's player files when a full wipe asked for it.
     *
     * <p>The library loads at startup, before any world and before any player
     * can join, so nothing holds the files open and nothing writes them back.
     */
    private static void deleteMarkedVanillaFiles(Plugin plugin) {
        Path marker = plugin.getDataFolder().toPath().resolve(VANILLA_MARKER);
        if (!Files.exists(marker)) return;
        Path world = Bukkit.getWorldContainer().toPath().resolve(levelName());
        int deleted = 0;
        for (String folder : List.of("playerdata", "stats", "advancements")) {
            try (Stream<Path> files = Files.list(world.resolve(folder))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Files.deleteIfExists(file);
                    deleted++;
                }
            } catch (IOException missing) {
                // A folder that is not there has nothing to delete.
            }
        }
        try {
            Files.deleteIfExists(marker);
        } catch (IOException ignored) {
            // Deleted again next start, which is harmless: the folders are empty.
        }
        plugin.getLogger().info("Data wipe: deleted " + deleted + " vanilla player files of " + world.getFileName() + ".");
    }

    /** The main world's folder name, read from {@code server.properties}. */
    private static String levelName() {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("server.properties"))) {
            properties.load(in);
        } catch (IOException missing) {
            return "world";
        }
        return properties.getProperty("level-name", "world");
    }

    private static void deleteVanillaFiles(UUID player) {
        if (Bukkit.getWorlds().isEmpty()) return;
        Path world = Bukkit.getWorlds().get(0).getWorldFolder().toPath();
        for (String file : List.of("playerdata/" + player + ".dat", "playerdata/" + player + ".dat_old",
                "stats/" + player + ".json", "advancements/" + player + ".json")) {
            try {
                Files.deleteIfExists(world.resolve(file));
            } catch (IOException failure) {
                Plugin plugin = library;
                if (plugin != null) Debug.of(plugin).warn("Could not delete " + file + ": " + failure.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- machinery

    private static <T> CompletableFuture<T> async(java.util.function.Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin plugin = library;
        Runnable task = () -> {
            try {
                future.complete(work.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        };
        if (plugin == null) {
            task.run();
        } else {
            Tasks.of(plugin).runAsync(task);
        }
        return future;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return String.valueOf(root.getMessage());
    }
}
