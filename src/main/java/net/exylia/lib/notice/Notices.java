package net.exylia.lib.notice;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.notice.internal.NoticeRow;
import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.settings.Broadcasts;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Messages left for a player — mail for words, not items.
 *
 * <p>A sale, an expired auction, a clan invite: whatever a plugin wants a
 * player to read, whether or not they are here to read it.
 *
 * <pre>{@code
 * // onEnable
 * Notices notices = Notices.of(this)
 *         .summary("sold", (player, sold) -> List.of(Text.from(this,
 *                 "%prefix% {letters}While you were away {highlight}%count% {letters}items sold.")
 *                 .with("%count%", sold.size())))
 *         .click("sold", "/market claim");
 *
 * // any thread
 * notices.send(seller, "sold", "%prefix% {letters}Sold {highlight}%item% {letters}for {success}%price%",
 *         Map.of("item", "Diamond x16", "price", Formats.money(4500)));
 * }</pre>
 *
 * <h2>Where a notice goes</h2>
 * <ul>
 *   <li>Online on this server: shown at once, as its own message.</li>
 *   <li>Online on another server sharing the plugin's database: written, then
 *       announced on the plugin's {@code notices} channel; the server they are
 *       on takes it and shows it as its own message. Needs Redis, see
 *       {@code docs/redis.md}; without it the notice waits for their next join.</li>
 *   <li>Offline: written to the plugin's database ({@code exylia_notices}) and
 *       shown on their next join, about three seconds in so it is not buried
 *       under the join messages.</li>
 * </ul>
 *
 * <h2>The join summary</h2>
 * Waiting notices are grouped by category. A category with a
 * {@link #summary summary} collapses into whatever lines it returns; one
 * without lists its notices compactly under a "While you were away" header,
 * at most five, then "and N more". A lone notice is shown as its own message.
 * {@link #footer} adds lines after the summary, such as how many items wait in
 * a claim screen.
 *
 * <h2>Exactly once</h2>
 * A notice is taken by deleting its row, and only the server whose delete
 * removed it shows it. Two servers racing for the same player split the rows;
 * they never both show one. A player who leaves between the take and the
 * message gets their notices written back.
 *
 * <h2>Limits</h2>
 * Up to 500 waiting notices per player and plugin; past that a new one is
 * refused and the console says so once per refusal, because a queue that
 * grows without end is a loop somewhere. A notice nobody read is deleted after
 * {@code notices.keep-days} in ExyliaLib's {@code config.yml} (30 by default).
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread. The database is only touched off the
 * main thread, and nothing is read before the server's first tick, so calling
 * {@link #of} from {@code onEnable} with players online (a reload) is safe.
 *
 * @since 1.278.0
 */
public final class Notices {

    /** How many notices one player may be waiting on from one plugin. */
    static final int MOST_PER_PLAYER = 500;

    /** How many notices the default summary lists before "and N more". */
    static final int LISTED = 5;

    /** How long after a join the waiting notices are shown: after the join messages. */
    private static final long JOIN_DELAY_TICKS = 60L;

    /** How often old notices are swept, in ticks: six hours. */
    private static final long SWEEP_TICKS = 20L * 60 * 60 * 6;

    /** How many old rows one sweep reads at a time. */
    private static final int SWEEP_BATCH = 200;

    private static final Gson GSON = new Gson();
    private static final Type VALUES = new TypeToken<Map<String, String>>() { }.getType();

    /** By plugin name; the load that owns each is compared by identity, see {@link #release}. */
    private static final Map<String, Notices> OPEN = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final String name;
    private final Repository<NoticeRow> rows;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final Map<String, NoticeSummary> summaries = new ConcurrentHashMap<>();
    private final Map<String, String> clicks = new ConcurrentHashMap<>();
    private final Map<String, Broadcasts.Channel> mutes = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<Player, Notice>> lives = new ConcurrentHashMap<>();
    private volatile @Nullable Function<UUID, List<String>> footer;
    private volatile @Nullable Channel wire;
    private volatile LongSupplier clock = System::currentTimeMillis;

    private Notices(Plugin plugin) {
        this.plugin = plugin;
        this.name = plugin.getName();
        this.rows = Databases.of(plugin).repository(NoticeRow.class);
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /**
     * A plugin's notices, kept in its database.
     *
     * <p>Call it once, in {@code onEnable}. From then on every join on this
     * server is checked, and players already online are checked a few seconds
     * later. Released with the plugin.
     *
     * @param plugin the plugin whose {@code database.yml} holds the table
     * @return the same instance for the same plugin
     */
    public static @NotNull Notices of(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? open : null);
        Notices[] created = {null};
        Notices notices = OPEN.computeIfAbsent(plugin.getName(), name -> created[0] = new Notices(plugin));
        if (created[0] != null) {
            notices.start();
        }
        return notices;
    }

    private void start() {
        try {
            Channel channel = Channels.of(plugin).channel("notices");
            channel.subscribe(message -> arrived(message.payload()));
            wire = channel;
        } catch (RuntimeException noChannel) {
            debug.warn("Notices: no channel to other servers (" + noChannel.getMessage()
                    + "); a player online elsewhere reads theirs on their next join.");
        }
        try {
            tasks.runAsyncTimer(20L * 60, SWEEP_TICKS, this::sweep);
            for (Player online : Bukkit.getOnlinePlayers()) {
                greet(online);
            }
        } catch (RuntimeException noServer) {
            // No server behind this call — a test — and nobody to greet.
        }
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    /**
     * Collapses a category into a few lines on join.
     *
     * @param category the category
     * @param summary  what to show for its waiting notices
     * @return this
     */
    public @NotNull Notices summary(@NotNull String category, @NotNull NoticeSummary summary) {
        summaries.put(category, summary);
        return this;
    }

    /**
     * Makes a category's join lines run a command when clicked, such as the
     * command that opens a claim screen.
     *
     * @param category the category
     * @param command  run as the player, with or without its slash
     * @return this
     */
    public @NotNull Notices click(@NotNull String category, @NotNull String command) {
        clicks.put(category, command.startsWith("/") ? command : "/" + command);
        return this;
    }

    /**
     * Ties a category to an announcement channel: a player who muted it in
     * {@code /settings} is not shown its notices, live or on join. They are
     * still taken, so muting does not pile them up.
     *
     * @param category the category
     * @param channel  the channel whose mute applies
     * @return this
     */
    public @NotNull Notices channel(@NotNull String category, @NotNull Broadcasts.Channel channel) {
        mutes.put(category, channel);
        return this;
    }

    /**
     * Runs alongside every notice of a category shown live — the moment it
     * reaches a player who is online, on this server or another — for what a
     * line of chat cannot carry: a sound, a toast, a title. Not run for the
     * join summary, where ten toasts at once would be noise.
     *
     * <p>Runs on the player's thread, after the message; skipped for a muted
     * category. A hook that throws is reported and the notice still counts as
     * shown.
     *
     * @param category the category
     * @param then     what to do, given the player and the notice
     * @return this
     * @since 1.279.0
     */
    public @NotNull Notices live(@NotNull String category, @NotNull BiConsumer<Player, Notice> then) {
        lives.put(category, then);
        return this;
    }

    /**
     * Lines shown after the join summary, or alone when nothing was waiting —
     * the place for "you have 3 items to claim".
     *
     * <p>Called off the main thread on every join, so it may read a database;
     * {@code PendingRewards.count} is made for it. Returning an empty list
     * shows nothing.
     *
     * @param lines raw lines for a player, Exylia notation, {@code %prefix%} included
     * @return this
     */
    public @NotNull Notices footer(@NotNull Function<UUID, List<String>> lines) {
        this.footer = lines;
        return this;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * Leaves a message for a player.
     *
     * @param player   who it is for
     * @param category what kind of notice, the key its join summary is grouped by
     * @param message  raw message, Exylia notation, {@code %prefix%} included
     */
    public void send(@NotNull UUID player, @NotNull String category, @NotNull String message) {
        send(player, category, message, Map.of());
    }

    /**
     * Leaves a message for a player, with values substituted when it is shown.
     *
     * <p>Values are kept apart from the message, so a summary can add them up
     * ({@link Notice#number}) and the message is rendered with the palette of
     * the server that shows it.
     *
     * @param player   who it is for
     * @param category what kind of notice, at most 64 characters
     * @param message  raw message with {@code %key%} placeholders
     * @param values   what each {@code %key%} stands for, by key without percent signs
     */
    public void send(@NotNull UUID player, @NotNull String category, @NotNull String message,
                     @NotNull Map<String, ?> values) {
        if (category.isBlank() || category.length() > 64) {
            throw new IllegalArgumentException("A notice category is 1 to 64 characters: \"" + category + "\"");
        }
        Map<String, String> strings = new LinkedHashMap<>();
        values.forEach((key, value) -> strings.put(key, String.valueOf(value)));
        Notice notice = new Notice(category, message, strings, clock.getAsLong());
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            NoticeRow row = row(player, notice);
            tasks.runAtEntity(online, () -> showLive(online, List.of(notice)),
                    () -> tasks.runAsync(() -> keep(row)));
            return;
        }
        tasks.runAsync(() -> keep(row(player, notice)));
    }

    /** Writes a notice, then tells the network; never the other way round. */
    private void keep(NoticeRow row) {
        if (store(row)) {
            Channel channel = wire;
            if (channel != null) {
                channel.publish(row.player().toString());
            }
        }
    }

    /** Writes one row, refusing past the ceiling. Off the main thread. */
    boolean store(NoticeRow row) {
        try {
            long held = rows.all().where("plugin", name).where("player", row.player()).count().join();
            if (held >= MOST_PER_PLAYER) {
                debug.error(row.player() + " already has " + held + " notices waiting, which is the ceiling."
                        + " The newest (" + row.category() + ") is refused: something is sending them in a loop.");
                return false;
            }
            rows.save(row).join();
            return true;
        } catch (RuntimeException unwritable) {
            debug.error("Could not keep a notice for " + row.player() + ".", unwritable);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Taking
    // ------------------------------------------------------------------

    /** A notice announced on the channel: taken here when its player is here. */
    private void arrived(String payload) {
        UUID id;
        try {
            id = UUID.fromString(payload.trim());
        } catch (IllegalArgumentException unreadable) {
            return;
        }
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return;
        }
        tasks.runAsync(() -> {
            List<Taken> taken = claim(id);
            if (!taken.isEmpty()) {
                deliver(player, taken, () -> showLive(player, notices(taken)));
            }
        });
    }

    /** A player who just joined: their waiting notices, after the join messages. */
    private void greet(Player player) {
        tasks.runAsyncLater(JOIN_DELAY_TICKS, () -> {
            if (!player.isOnline()) {
                return;
            }
            List<Taken> taken = claim(player.getUniqueId());
            List<String> extra = footerFor(player.getUniqueId());
            if (taken.isEmpty() && extra.isEmpty()) {
                return;
            }
            deliver(player, taken, () -> showSummary(player, notices(taken), extra));
        });
    }

    /** Shows on the player's thread; a player who left by then gets the rows written back. */
    private void deliver(Player player, List<Taken> taken, Runnable show) {
        tasks.runAtEntity(player, show, () -> tasks.runAsync(() -> {
            for (Taken back : taken) {
                try {
                    rows.save(back.row()).join();
                } catch (RuntimeException lost) {
                    debug.error("Could not put back a notice for " + back.row().player() + ".", lost);
                }
            }
        }));
    }

    private List<String> footerFor(UUID player) {
        Function<UUID, List<String>> lines = footer;
        if (lines == null) {
            return List.of();
        }
        try {
            List<String> found = lines.apply(player);
            return found == null ? List.of() : found;
        } catch (RuntimeException failed) {
            debug.error("A notices footer failed for " + player + ".", failed);
            return List.of();
        }
    }

    /** One row this server took, and what it reads as. */
    record Taken(NoticeRow row, Notice notice) {
    }

    /**
     * Takes every waiting notice of a player, oldest first, leaving nothing.
     *
     * <p>Off the main thread. Each row is deleted by id and only the rows this
     * call deleted are returned, so a racing claim never gets the same one.
     * Expired rows are deleted and not returned.
     */
    List<Taken> claim(UUID player) {
        List<NoticeRow> held;
        try {
            held = rows.all().where("plugin", name).where("player", player).orderBy("sentAt").find().join();
        } catch (RuntimeException unreadable) {
            debug.error("Could not read the notices waiting for " + player + ".", unreadable);
            return List.of();
        }
        long oldest = clock.getAsLong() - keepMillis();
        List<Taken> taken = new ArrayList<>(held.size());
        for (NoticeRow row : held) {
            boolean mine;
            try {
                mine = Boolean.TRUE.equals(rows.delete(row.id()).join());
            } catch (RuntimeException failed) {
                continue;
            }
            if (mine && row.sentAt() >= oldest) {
                taken.add(new Taken(row, notice(row)));
            }
        }
        return taken;
    }

    /**
     * Deletes every notice older than {@code notices.keep-days}, oldest first.
     *
     * @return how many were deleted
     */
    int sweep() {
        long oldest = clock.getAsLong() - keepMillis();
        int deleted = 0;
        try {
            while (true) {
                List<NoticeRow> batch = rows.all().where("plugin", name).orderBy("sentAt")
                        .limit(SWEEP_BATCH).find().join();
                int before = deleted;
                for (NoticeRow row : batch) {
                    if (row.sentAt() >= oldest) {
                        return deleted;
                    }
                    if (Boolean.TRUE.equals(rows.delete(row.id()).join())) {
                        deleted++;
                    }
                }
                if (batch.size() < SWEEP_BATCH || deleted == before) {
                    return deleted;
                }
            }
        } catch (RuntimeException failed) {
            debug.warn("Notices: could not delete old notices (" + failed.getMessage() + ").");
            return deleted;
        }
    }

    private static long keepMillis() {
        LibrarySettings.Notices settings = LibrarySettings.get().notices();
        int days = settings == null ? 30 : Math.max(1, settings.keepDays());
        return days * 86_400_000L;
    }

    // ------------------------------------------------------------------
    // Showing
    // ------------------------------------------------------------------

    /** Live: each notice as its own message, skipping muted categories. */
    private void showLive(Player player, List<Notice> notices) {
        for (Notice notice : notices) {
            if (heard(player, notice.category())) {
                say(player, notice.category(), notice.text(plugin));
                BiConsumer<Player, Notice> then = lives.get(notice.category());
                if (then != null) {
                    try {
                        then.accept(player, notice);
                    } catch (RuntimeException failed) {
                        debug.error("The live hook of notices \"" + notice.category() + "\" failed.", failed);
                    }
                }
            }
        }
    }

    /** On join: one block per category, then the footer. */
    private void showSummary(Player player, List<Notice> notices, List<String> extra) {
        for (Map.Entry<String, List<Notice>> group : group(notices).entrySet()) {
            String category = group.getKey();
            if (!heard(player, category)) {
                continue;
            }
            NoticeSummary summary = summaries.get(category);
            List<Text> lines;
            try {
                lines = summary != null ? summary.render(player, group.getValue()) : listed(group.getValue());
            } catch (RuntimeException failed) {
                debug.error("The summary of notices \"" + category + "\" failed; listed instead.", failed);
                lines = listed(group.getValue());
            }
            for (Text line : lines) {
                say(player, category, line);
            }
        }
        for (String line : extra) {
            Text.from(plugin, line).forPlayer(player).send(player);
        }
    }

    /** The default summary: a lone notice as itself, more as a capped list. */
    List<Text> listed(List<Notice> notices) {
        if (notices.size() == 1) {
            return List.of(notices.getFirst().text(plugin));
        }
        List<Text> lines = new ArrayList<>(LISTED + 2);
        lines.add(Text.from(plugin, Phrases.tr(
                "%prefix% {letters}While you were away {letters_black}({info}{0}{letters_black})",
                notices.size())));
        for (Notice notice : notices.subList(0, Math.min(LISTED, notices.size()))) {
            String body = notice.message().replace("%prefix%", "").strip();
            lines.add(notice.fill(Text.from(plugin, " {letters_black}▎ {letters}" + body)));
        }
        if (notices.size() > LISTED) {
            lines.add(Text.from(plugin, Phrases.tr(" {letters_black}▎ {muted}and {0} more",
                    notices.size() - LISTED)));
        }
        return lines;
    }

    private void say(Player player, String category, Text line) {
        Text filled = line.forPlayer(player);
        String command = clicks.get(category);
        if (command == null) {
            filled.send(player);
            return;
        }
        Component clickable = filled.build()
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Text.of(Phrases.tr("{warning}➥ Click to open")).build()));
        player.sendMessage(clickable);
    }

    private boolean heard(Player player, String category) {
        Broadcasts.Channel channel = mutes.get(category);
        return channel == null || Broadcasts.enabled(player, channel);
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /** Groups by category, in the order each first appears, keeping each group's order. */
    static Map<String, List<Notice>> group(List<Notice> notices) {
        Map<String, List<Notice>> groups = new LinkedHashMap<>();
        for (Notice notice : notices) {
            groups.computeIfAbsent(notice.category(), category -> new ArrayList<>()).add(notice);
        }
        return groups;
    }

    private NoticeRow row(UUID player, Notice notice) {
        return NoticeRow.of(name, player, notice.category(), notice.message(),
                notice.values().isEmpty() ? "" : GSON.toJson(notice.values()), notice.sentAt());
    }

    private Notice notice(NoticeRow row) {
        Map<String, String> values = Map.of();
        if (row.data() != null && !row.data().isBlank()) {
            try {
                Map<String, String> read = GSON.fromJson(row.data(), VALUES);
                if (read != null) {
                    read.values().removeIf(java.util.Objects::isNull);
                    values = read;
                }
            } catch (RuntimeException unreadable) {
                debug.warn("Notice " + row.id() + " has values that cannot be read; shown without them.");
            }
        }
        return new Notice(row.category(), row.message() == null ? "" : row.message(), values, row.sentAt());
    }

    private static List<Notice> notices(List<Taken> taken) {
        return taken.stream().map(Taken::notice).toList();
    }

    /** Package-private: how the tests write a row as {@link #send} would for an offline player. */
    NoticeRow rowForTests(UUID player, String category, String message, Map<String, String> values, long sentAt) {
        return row(player, new Notice(category, message, values, sentAt));
    }

    /** Package-private: the tests' clock. */
    void clockForTests(LongSupplier clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Checks a joining player in every plugin. Called by the library on join. */
    public static void joined(@NotNull Player player) {
        for (Notices notices : OPEN.values()) {
            notices.greet(player);
        }
    }

    /**
     * Stops using a plugin's database when it is disabled — this load only: a
     * plugin reloaded in place has a newer load alive by the time this runs.
     * Called by the library.
     */
    public static void release(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? null : open);
    }

    /** Forgets every plugin, on shutdown. Called by the library. */
    public static void releaseAll() {
        OPEN.clear();
    }
}
