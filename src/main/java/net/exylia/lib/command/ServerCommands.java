package net.exylia.lib.command;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.exylia.lib.command.internal.ServerCommandRow;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Commands run for a player on a given server of the network, whenever they
 * are there: a kit bought in the lobby given when they reach survival.
 *
 * <pre>{@code
 * ServerCommands.of(this).run(buyer, "survival",
 *         List.of("console: kit give %player_name% starter"), Map.of());
 * }</pre>
 *
 * <ul>
 *   <li>The player is on that server (or the server is blank) and on this one:
 *       run now, on their thread.</li>
 *   <li>Anywhere else: written to the plugin's database
 *       ({@code exylia_server_commands}) and announced on the plugin's
 *       {@code server-commands} channel, so the right server runs them at once
 *       when the player is already there; otherwise on their next join there.</li>
 * </ul>
 *
 * <p>Lines are {@link CommandLine}s ({@code console:}, {@code player:}),
 * run through {@link PluginCommands} with the player and the values given.
 * A batch is taken by deleting its row, so it runs once, on one server. The
 * server is the {@code server-id} of {@code Redis.serverId}; without Redis
 * every batch for another server waits in the table for a join there.
 *
 * @since 1.337.0
 */
public final class ServerCommands {

    private static final Map<String, ServerCommands> OPEN = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();
    private static final Type LINES = new TypeToken<List<String>>() { }.getType();
    private static final Type VALUES = new TypeToken<Map<String, String>>() { }.getType();
    /** How long after a join its batches run: after the join messages and the first inventory sync. */
    private static final long JOIN_DELAY_TICKS = 40L;

    private final Plugin plugin;
    private final String name;
    private final Repository<ServerCommandRow> rows;
    private final TaskScheduler tasks;
    private final Debug debug;
    private volatile Channel wire;

    private ServerCommands(Plugin plugin) {
        this.plugin = plugin;
        this.name = plugin.getName();
        this.rows = Databases.of(plugin).repository(ServerCommandRow.class);
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /**
     * A plugin's queue. Call it once in {@code onEnable} so joins are checked
     * from then on; players already online are checked straight away.
     */
    public static @NotNull ServerCommands of(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (key, open) -> open.plugin == plugin ? open : null);
        ServerCommands[] created = {null};
        ServerCommands commands = OPEN.computeIfAbsent(plugin.getName(), key -> created[0] = new ServerCommands(plugin));
        if (created[0] != null) commands.start();
        return commands;
    }

    private void start() {
        try {
            Channel channel = Channels.of(plugin).channel("server-commands");
            channel.subscribe(message -> arrived(message.payload()));
            wire = channel;
        } catch (RuntimeException noChannel) {
            debug.warn("Server commands: no channel to other servers (" + noChannel.getMessage()
                    + "); a player online elsewhere gets theirs on their next join there.");
        }
        try {
            for (Player online : Bukkit.getOnlinePlayers()) joinedHere(online);
        } catch (RuntimeException noServer) {
            // A test: nobody to check.
        }
    }

    /** This server's {@code server-id}, in lower case. */
    public @NotNull String server() {
        try {
            return Redis.serverId(plugin).toLowerCase(Locale.ROOT);
        } catch (RuntimeException | LinkageError unknown) {
            return "server-1";
        }
    }

    /**
     * Runs commands for a player on a server, now or when they get there.
     * Any thread.
     *
     * @param player who they are run for
     * @param server the {@code server-id} to run them on; blank for whichever the player is on
     * @param lines  the command lines
     * @param values what each {@code %key%} in them stands for, besides the player's own
     */
    public void run(@NotNull UUID player, @NotNull String server, @NotNull List<String> lines,
                    @NotNull Map<String, ?> values) {
        if (lines.isEmpty()) return;
        String target = server.trim().toLowerCase(Locale.ROOT);
        Map<String, String> strings = new LinkedHashMap<>();
        values.forEach((key, value) -> strings.put(key, String.valueOf(value)));
        Player online = Bukkit.getPlayer(player);
        if (online != null && (target.isEmpty() || target.equals(server()))) {
            tasks.runAtEntity(online, () -> execute(online, lines, strings),
                    () -> tasks.runAsync(() -> keep(player, target, lines, strings)));
            return;
        }
        tasks.runAsync(() -> keep(player, target, lines, strings));
    }

    private void keep(UUID player, String server, List<String> lines, Map<String, String> values) {
        try {
            rows.save(new ServerCommandRow(UUID.randomUUID().toString(), name, player, server,
                    GSON.toJson(lines), GSON.toJson(values), System.currentTimeMillis())).join();
        } catch (RuntimeException unwritable) {
            debug.error("Could not queue commands for " + player + " on " + (server.isEmpty() ? "any server" : server)
                    + ": " + lines, unwritable);
            return;
        }
        Channel channel = wire;
        if (channel != null) channel.publish(player.toString());
    }

    /** A batch announced on the channel: taken here when its player is here. */
    private void arrived(String payload) {
        UUID id;
        try {
            id = UUID.fromString(payload.trim());
        } catch (IllegalArgumentException unreadable) {
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player != null) tasks.runAsync(() -> claim(player));
    }

    private void joinedHere(Player player) {
        tasks.runAsyncLater(JOIN_DELAY_TICKS, () -> {
            if (player.isOnline()) claim(player);
        });
    }

    /** Takes every batch for this player on this server and runs it. Off the main thread. */
    private void claim(Player player) {
        List<ServerCommandRow> held;
        try {
            held = rows.all().where("plugin", name).where("player", player.getUniqueId())
                    .orderBy("queuedAt").find().join();
        } catch (RuntimeException unreadable) {
            debug.error("Could not read the commands waiting for " + player.getName() + ".", unreadable);
            return;
        }
        String here = server();
        List<ServerCommandRow> mine = new ArrayList<>();
        for (ServerCommandRow row : held) {
            if (!row.server().isEmpty() && !row.server().equals(here)) continue;
            try {
                if (Boolean.TRUE.equals(rows.delete(row.id()).join())) mine.add(row);
            } catch (RuntimeException failed) {
                debug.error("Could not take queued commands " + row.id() + ".", failed);
            }
        }
        if (mine.isEmpty()) return;
        tasks.runAtEntity(player, () -> {
            for (ServerCommandRow row : mine) {
                execute(player, GSON.fromJson(row.lines(), LINES), GSON.fromJson(row.data(), VALUES));
            }
        }, () -> tasks.runAsync(() -> mine.forEach(row -> {
            try {
                rows.save(row).join();
            } catch (RuntimeException lost) {
                debug.error("Could not put back queued commands for " + row.player() + ": " + row.lines(), lost);
            }
        })));
    }

    private void execute(Player player, List<String> lines, Map<String, String> values) {
        Commands.of(plugin).run(Commands.compileAll(lines), player, Map.copyOf(values));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Checks a joining player in every plugin. Called by the library on join. */
    public static void joined(@NotNull Player player) {
        for (ServerCommands commands : OPEN.values()) commands.joinedHere(player);
    }

    /** Stops using a plugin's database when it is disabled; this load only. Called by the library. */
    public static void release(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (key, open) -> open.plugin == plugin ? null : open);
    }

    /** Forgets every plugin, on shutdown. Called by the library. */
    public static void releaseAll() {
        OPEN.clear();
    }
}
