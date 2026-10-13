package net.exylia.lib.log;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Query;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.item.ItemTexts;
import net.exylia.lib.log.internal.LogRow;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.teleport.internal.CrossServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/**
 * The staff audit log: who did what to whom, kept by every plugin in its own
 * database and read together with {@code /exylialib logs}.
 *
 * <p>One line at the point the action actually completed, never on a failure
 * path:
 *
 * <pre>{@code
 * // onEnable
 * Logs logs = Logs.of(this).action("market-buy", "EMERALD");
 *
 * // any thread
 * logs.record("market-buy", buyer, seller, "16x Diamond for 4500 dollars",
 *         List.of(Logs.Group.of("Bought", stack)));
 * }</pre>
 *
 * <p>Recording only queues the entry; the queue is written in one batch every
 * {@code logs.flush-seconds}, so no action ever waits on the database. Reading
 * writes the queue first, so staff never miss what just happened. A plugin, or
 * one of its actions, listed in {@code logs.disabled} of ExyliaLib's
 * {@code config.yml} is not recorded, so a plugin may record unconditionally.
 *
 * <p>Actions are kebab-case keys such as {@code crate-keys-give}: what a
 * filter matches and what a menu shows, upper-cased, as the entry's name.
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread. The actor's location is only read when
 * this thread owns them; from a database callback the entry has none.
 *
 * @since 1.296.0
 */
public final class Logs {

    /** What an actor that is not a player is stored as. */
    public static final String CONSOLE = "CONSOLE";

    /** Most stacks one entry keeps; the rest are left out of the stored items, never the text. */
    public static final int MAX_STACKS = 256;

    /** Longest detail kept; an item list past this is cut. */
    static final int MAX_DETAIL = 4000;

    private static final long DAY_MILLIS = 86_400_000L;
    /** How far back one purge walks, so a clock that jumped years cannot start an endless loop. */
    private static final int MAX_PURGE_DAYS = 3650;
    /** How often old entries are purged, in ticks: six hours. */
    private static final long PURGE_TICKS = 20L * 60 * 60 * 6;
    private static final String DEFAULT_ICON = "PAPER";

    /** By plugin name; the load that owns each is compared by identity, see {@link #release}. */
    private static final Map<String, Logs> OPEN = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final String name;
    private final Repository<LogRow> rows;
    private final Debug debug;
    private final Queue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final Map<String, String> icons = new ConcurrentHashMap<>();
    private final Set<String> actions = ConcurrentHashMap.newKeySet();
    private volatile @Nullable String server;
    private volatile LongSupplier clock = System::currentTimeMillis;

    /** An entry recorded and not written yet. */
    private record Pending(String action, UUID actorId, String actorName, UUID targetId, String targetName,
                           String detail, String world, int x, int y, int z, long at, List<Group> groups) {
    }

    private Logs(Plugin plugin) {
        this.plugin = plugin;
        this.name = plugin.getName();
        this.rows = Databases.of(plugin).repository(LogRow.class);
        this.debug = Debug.of(plugin);
    }

    /**
     * A plugin's log, kept in its database.
     *
     * <p>Call it in {@code onEnable}, or whenever: the first call starts the
     * timers that write and purge it. Released with the plugin, after a last
     * write.
     *
     * @param plugin the plugin whose {@code database.yml} holds the table
     * @return the same instance for the same plugin load
     */
    public static @NotNull Logs of(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? open : null);
        Logs[] created = {null};
        Logs logs = OPEN.computeIfAbsent(plugin.getName(), name -> created[0] = new Logs(plugin));
        if (created[0] != null) {
            logs.start();
        }
        return logs;
    }

    private void start() {
        try {
            long flush = 20L * Math.max(1, settings().flushSeconds());
            Tasks.of(plugin).runAsyncTimer(flush, flush, () -> flush());
            Tasks.of(plugin).runAsyncTimer(20L * 60, PURGE_TICKS, () -> purge());
        } catch (RuntimeException noServer) {
            // No server behind this call — a test — and nothing to time.
        }
    }

    /**
     * Declares an action: its icon in the menu and a tab completion before it
     * was ever recorded. Optional; an undeclared action is drawn as paper.
     *
     * @param action the action key, kebab-case
     * @param icon   a material name, or anything a menu's {@code material} reads
     * @return this
     */
    public @NotNull Logs action(@NotNull String action, @NotNull String icon) {
        String key = key(action);
        icons.put(key, icon);
        actions.add(key);
        return this;
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    /** Something a sender did with nobody on the other end. */
    public void record(@NotNull String action, @Nullable CommandSender actor, @Nullable String detail) {
        record(action, actor, (UUID) null, null, detail, null);
    }

    /** Something a sender did to a player, who may be offline. */
    public void record(@NotNull String action, @Nullable CommandSender actor, @Nullable ExyliaPlayer target,
                       @Nullable String detail) {
        record(action, actor, target, detail, null);
    }

    /** The same, keeping the stacks that moved. */
    public void record(@NotNull String action, @Nullable CommandSender actor, @Nullable ExyliaPlayer target,
                       @Nullable String detail, @Nullable List<Group> items) {
        record(action, actor, target == null ? null : target.id(), target == null ? null : target.name(),
                detail, items);
    }

    /** Something a sender did to a player known only by id and name; both {@code null} for nobody. */
    public void record(@NotNull String action, @Nullable CommandSender actor, @Nullable UUID targetId,
                       @Nullable String targetName, @Nullable String detail) {
        record(action, actor, targetId, targetName, detail, null);
    }

    /** The same, keeping the stacks that moved. */
    public void record(@NotNull String action, @Nullable CommandSender actor, @Nullable UUID targetId,
                       @Nullable String targetName, @Nullable String detail, @Nullable List<Group> items) {
        if (actor instanceof Player player) {
            // Only where the player's own thread is this one: a database callback must not read them.
            record(action, player.getUniqueId(), player.getName(), targetId, targetName, detail,
                    owned(player) ? player.getLocation() : null, items);
        } else {
            record(action, null, actor == null ? CONSOLE : actor.getName(), targetId, targetName, detail,
                    null, items);
        }
    }

    /** The full form, for a caller that holds only ids. */
    public void record(@NotNull String action, @Nullable UUID actorId, @Nullable String actorName,
                       @Nullable UUID targetId, @Nullable String targetName, @Nullable String detail,
                       @Nullable Location at) {
        record(action, actorId, actorName, targetId, targetName, detail, at, null);
    }

    /**
     * The full form keeping the stacks that moved, which staff can then open
     * as they were. The groups are copied here, so hand the stacks over
     * afterwards as usual.
     *
     * @param action     what happened, kebab-case, at most 48 characters
     * @param actorId    who did it, or {@code null} for the console
     * @param actorName  their name; {@code null} is {@link #CONSOLE}
     * @param targetId   who it was done to, or {@code null} for nobody
     * @param targetName their name, or {@code null}
     * @param detail     one line a reader understands, at most 4000 characters
     * @param at         where, or {@code null}
     * @param items      what moved, labelled, or {@code null} for none; at most {@link #MAX_STACKS} stacks are kept
     */
    public void record(@NotNull String action, @Nullable UUID actorId, @Nullable String actorName,
                       @Nullable UUID targetId, @Nullable String targetName, @Nullable String detail,
                       @Nullable Location at, @Nullable List<Group> items) {
        String key = cut(key(action), 48);
        if (disabled(name, key)) return;
        actions.add(key);
        boolean placed = at != null && at.getWorld() != null;
        pending.add(new Pending(key, actorId, cut(actorName == null ? CONSOLE : actorName, 32),
                targetId, cut(targetName, 32), cut(detail, MAX_DETAIL),
                placed ? cut(at.getWorld().getName(), 64) : null,
                placed ? at.getBlockX() : 0, placed ? at.getBlockY() : 0, placed ? at.getBlockZ() : 0,
                clock.getAsLong(), capped(items)));
    }

    private static boolean owned(Player player) {
        try {
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (RuntimeException | LinkageError noServer) {
            return false;
        }
    }

    /** Whether {@code logs.disabled} names this plugin, or this plugin and action. */
    static boolean disabled(String plugin, String action) {
        for (String entry : settings().disabled()) {
            if (entry == null) continue;
            String rule = entry.trim();
            if (rule.equalsIgnoreCase(plugin) || rule.equalsIgnoreCase(plugin + ":" + action)) return true;
        }
        return false;
    }

    /** Copies of the groups cut to {@link #MAX_STACKS} stacks in all; a group left with none still says it was there. */
    static @Nullable List<Group> capped(@Nullable List<Group> groups) {
        if (groups == null || groups.isEmpty()) return null;
        List<Group> kept = new ArrayList<>(groups.size());
        int left = MAX_STACKS;
        for (Group group : groups) {
            if (group == null) continue;
            List<ItemStack> items = group.items();
            kept.add(new Group(group.label(), items.size() <= left ? items : items.subList(0, left)));
            left = Math.max(0, left - items.size());
        }
        return kept.isEmpty() ? null : kept;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Writes everything queued, in one batch. Off the main thread. */
    CompletableFuture<Void> flush() {
        List<LogRow> batch = new ArrayList<>();
        for (Pending entry; (entry = pending.poll()) != null; ) {
            batch.add(row(entry));
        }
        if (batch.isEmpty()) return CompletableFuture.completedFuture(null);
        return rows.saveAll(batch).exceptionally(failure -> {
            debug.error("Staff logs could not be saved.", failure);
            return null;
        });
    }

    private LogRow row(Pending entry) {
        return new LogRow(UUID.randomUUID().toString(), name, server(), entry.action(),
                entry.actorId() == null ? null : entry.actorId().toString(), entry.actorName(),
                entry.targetId() == null ? null : entry.targetId().toString(), entry.targetName(),
                entry.detail(), entry.world(), entry.x(), entry.y(), entry.z(),
                entry.at() / DAY_MILLIS, entry.at(),
                LogRow.encodeGroups(entry.groups()), LogRow.encodeItems(entry.groups()));
    }

    private String server() {
        String known = server;
        if (known == null) {
            try {
                known = cut(CrossServer.serverId(plugin), 64);
            } catch (RuntimeException | LinkageError unreadable) {
                known = "server";
            }
            server = known;
        }
        return known;
    }

    /**
     * Deletes this plugin's entries older than {@code logs.retention-days},
     * one day at a time from the oldest.
     *
     * @return how many were deleted
     */
    CompletableFuture<Integer> purge() {
        int days = settings().retentionDays();
        if (days <= 0) return CompletableFuture.completedFuture(0);
        long cutoff = clock.getAsLong() / DAY_MILLIS - days;
        return rows.where("plugin", name).orderBy("createdDay").limit(1).findFirst().thenCompose(oldest -> {
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            if (oldest.isEmpty()) return chain;
            for (long day = Math.max(oldest.get().createdDay(), cutoff - MAX_PURGE_DAYS); day < cutoff; day++) {
                long expired = day;
                chain = chain.thenCompose(deleted -> rows.where("plugin", name).where("createdDay", expired)
                        .delete().thenApply(more -> deleted + more));
            }
            return chain;
        }).exceptionally(failure -> {
            debug.error("Old staff logs could not be purged.", failure);
            return 0;
        });
    }

    // ------------------------------------------------------------------
    // Reading — the library's command and menu
    // ------------------------------------------------------------------

    /**
     * Entries newest first, across every plugin that keeps a log, at most
     * {@code logs.max-rows}. Writes every queue first. Called by the library.
     *
     * @param player only what this player did or had done to them, or {@code null} for everybody
     * @param plugin only this plugin, or {@code null} for all
     * @param action only this action, or {@code null} for all
     * @return the entries
     */
    @ApiStatus.Internal
    public static @NotNull CompletableFuture<List<LogRow>> read(@Nullable UUID player, @Nullable String plugin,
                                                                @Nullable String action) {
        int limit = Math.max(1, settings().maxRows());
        List<CompletableFuture<List<LogRow>>> parts = new ArrayList<>();
        for (Logs logs : OPEN.values()) {
            if (plugin == null || logs.name.equalsIgnoreCase(plugin)) {
                parts.add(logs.flush().thenCompose(done -> logs.newest(player, action, limit)));
            }
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(done -> {
            List<LogRow> all = new ArrayList<>();
            parts.forEach(part -> all.addAll(part.join()));
            return merge(all, limit);
        });
    }

    private CompletableFuture<List<LogRow>> newest(@Nullable UUID player, @Nullable String action, int limit) {
        if (player == null) {
            return newest(rows.where("plugin", name), action, limit);
        }
        String id = player.toString();
        CompletableFuture<List<LogRow>> did = newest(rows.where("plugin", name).where("actor", id), action, limit);
        CompletableFuture<List<LogRow>> got = newest(rows.where("plugin", name).where("target", id), action, limit);
        return did.thenCombine(got, (a, b) -> {
            List<LogRow> both = new ArrayList<>(a);
            both.addAll(b);
            return merge(both, limit);
        });
    }

    private static CompletableFuture<List<LogRow>> newest(Query<LogRow> query, @Nullable String action, int limit) {
        if (action != null) query = query.where("action", key(action));
        return query.orderByDescending("createdAt").limit(limit).find();
    }

    /** One list, newest first; an entry seen twice (done to themselves) shows once. */
    static List<LogRow> merge(List<LogRow> rows, int limit) {
        Map<String, LogRow> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.putIfAbsent(row.id(), row));
        List<LogRow> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparingLong(LogRow::createdAt).reversed());
        return merged.size() > limit ? new ArrayList<>(merged.subList(0, limit)) : merged;
    }

    /**
     * One entry by its id, from whichever plugin kept it; empty once purged.
     * Called by the library.
     */
    @ApiStatus.Internal
    public static @NotNull CompletableFuture<Optional<LogRow>> find(@NotNull String id) {
        List<CompletableFuture<Optional<LogRow>>> parts = new ArrayList<>();
        for (Logs logs : OPEN.values()) {
            parts.add(logs.flush().thenCompose(done -> logs.rows.find(id))
                    .thenApply(found -> found.filter(row -> logs.name.equals(row.plugin())))
                    .exceptionally(failure -> Optional.empty()));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(done -> parts.stream()
                .map(CompletableFuture::join).flatMap(Optional::stream).findFirst());
    }

    /** The plugins keeping a log now, by name. Called by the library. */
    @ApiStatus.Internal
    public static @NotNull List<String> plugins() {
        return OPEN.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * A plugin's declared and recorded actions, sorted; every plugin's for
     * {@code null}, none for an unknown plugin. Called by the library.
     */
    @ApiStatus.Internal
    public static @NotNull List<String> actions(@Nullable String plugin) {
        Set<String> found = new TreeSet<>();
        if (plugin == null) {
            OPEN.values().forEach(logs -> found.addAll(logs.actions));
        } else {
            Logs logs = open(plugin);
            if (logs != null) found.addAll(logs.actions);
        }
        return List.copyOf(found);
    }

    /** The icon an action was declared with, or paper. Called by the library. */
    @ApiStatus.Internal
    public static @NotNull String icon(@Nullable String plugin, @Nullable String action) {
        Logs logs = open(plugin);
        String icon = logs == null || action == null ? null : logs.icons.get(key(action));
        return icon == null ? DEFAULT_ICON : icon;
    }

    private static @Nullable Logs open(@Nullable String plugin) {
        if (plugin == null) return null;
        for (Logs logs : OPEN.values()) {
            if (logs.name.equalsIgnoreCase(plugin)) return logs;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Groups and helpers
    // ------------------------------------------------------------------

    /**
     * Stacks a log entry keeps under a short label, such as {@code Steve gave}.
     *
     * <p>Copied on creation, air left out: giving an item can shrink the stack
     * the caller still holds.
     *
     * @param label what the stacks were, such as {@code Bought}
     * @param items the stacks
     */
    public record Group(String label, List<ItemStack> items) {

        public Group {
            List<ItemStack> copy = new ArrayList<>();
            if (items != null) {
                for (ItemStack stack : items) {
                    if (stack != null && !stack.getType().isAir()) copy.add(stack.clone());
                }
            }
            items = List.copyOf(copy);
        }

        /** Some stacks under a label. */
        public static @NotNull Group of(String label, Collection<ItemStack> items) {
            return new Group(label, items == null ? List.of() : new ArrayList<>(items));
        }

        /** One stack under a label. */
        public static @NotNull Group of(String label, ItemStack stack) {
            return new Group(label, stack == null ? List.of() : List.of(stack));
        }

        /** One kind of item in any amount, cut into stacks it can really come in. */
        public static @NotNull Group of(String label, ItemStack template, int amount) {
            List<ItemStack> stacks = new ArrayList<>();
            if (template != null && !template.getType().isAir()) {
                int max = Math.max(1, template.getMaxStackSize());
                for (int left = amount; left > 0 && stacks.size() < MAX_STACKS; left -= max) {
                    ItemStack stack = template.clone();
                    stack.setAmount(Math.min(max, left));
                    stacks.add(stack);
                }
            }
            return new Group(label, stacks);
        }
    }

    /** A copy of what a container holds, to compare with {@link #changed} later. */
    public static ItemStack @NotNull [] snapshot(ItemStack @NotNull [] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    /**
     * What left a container and what came in, by item rather than by slot:
     * a {@code Removed} group and an {@code Added} one, each only when it has
     * something. Empty when nothing changed.
     */
    public static @NotNull List<Group> changed(ItemStack @Nullable [] before, ItemStack @Nullable [] after) {
        Map<ItemStack, Integer> delta = new LinkedHashMap<>();
        count(delta, before, -1);
        count(delta, after, 1);
        List<ItemStack> removed = new ArrayList<>();
        List<ItemStack> added = new ArrayList<>();
        delta.forEach((kind, amount) -> {
            if (amount == 0) return;
            ItemStack stack = kind.clone();
            stack.setAmount(Math.abs(amount));
            (amount < 0 ? removed : added).add(stack);
        });
        List<Group> groups = new ArrayList<>(2);
        if (!removed.isEmpty()) groups.add(new Group("Removed", removed));
        if (!added.isEmpty()) groups.add(new Group("Added", added));
        return groups;
    }

    private static void count(Map<ItemStack, Integer> delta, ItemStack[] contents, int sign) {
        if (contents == null) return;
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) continue;
            delta.merge(stack.asOne(), sign * stack.getAmount(), Integer::sum);
        }
    }

    /** An amount in a currency, as a log reads it: {@code 1500 dollars}. */
    public static @NotNull String money(@NotNull String currency, @Nullable BigDecimal amount) {
        if (amount == null) return "0 " + currency;
        return amount.stripTrailingZeros().toPlainString() + " " + currency;
    }

    /** Several currencies at once; empty when there is none. */
    public static @NotNull String money(@NotNull Map<String, BigDecimal> amounts) {
        StringJoiner joined = new StringJoiner(", ");
        amounts.forEach((currency, amount) -> joined.add(money(currency, amount)));
        return joined.toString();
    }

    /** A list of stacks, compactly: {@code 64x Diamond, 1x "Excalibur" Netherite Sword[Sharpness 5]}. */
    public static @NotNull String items(@NotNull Collection<ItemStack> stacks) {
        return ItemTexts.summary(stacks);
    }

    /** One stack, compactly. */
    public static @NotNull String item(@NotNull ItemStack stack) {
        return ItemTexts.summary(stack);
    }

    /** One kind of item in any amount, such as the units a buy order took. */
    public static @NotNull String item(@NotNull ItemStack template, int amount) {
        return ItemTexts.summary(template, amount);
    }

    /** Groups as a log reads them: {@code Removed: 64x Diamond | Added: 1x Dirt}; empty when there is none. */
    public static @NotNull String text(@NotNull List<Group> groups) {
        StringJoiner joined = new StringJoiner(" | ");
        for (Group group : groups) joined.add(group.label() + ": " + items(group.items()));
        return joined.toString();
    }

    /** An action as it is stored: trimmed, lower case, words joined by dashes. */
    static String key(String action) {
        return action == null ? "" : action.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
    }

    private static String cut(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** The tests' settings, in place of the library's file; {@code null} reads the file. */
    private static volatile LibrarySettings.@Nullable Logs testSettings;

    /** Package-private: the tests' settings; {@code null} goes back to the file. */
    static void settingsForTests(LibrarySettings.@Nullable Logs settings) {
        testSettings = settings;
    }

    private static LibrarySettings.Logs settings() {
        LibrarySettings.Logs forced = testSettings;
        if (forced != null) return forced;
        LibrarySettings.Logs settings = LibrarySettings.get().logs();
        return settings == null ? new LibrarySettings.Logs() : settings;
    }

    /** Package-private: the tests' clock. */
    void clockForTests(LongSupplier clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Writes what a plugin still had queued and stops reading its log, when it
     * is disabled — this load only: a plugin reloaded in place has a newer
     * load alive by the time this runs. Called by the library, before the
     * plugin's database is released.
     */
    public static void release(@NotNull Plugin plugin) {
        Logs[] gone = {null};
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> {
            if (open.plugin != plugin) return open;
            gone[0] = open;
            return null;
        });
        if (gone[0] != null) {
            try {
                gone[0].flush().join();
            } catch (RuntimeException lost) {
                gone[0].debug.error("The last staff logs could not be saved.", lost);
            }
        }
    }

    /** Forgets every plugin, on shutdown. Called by the library. */
    public static void releaseAll() {
        OPEN.clear();
    }
}
