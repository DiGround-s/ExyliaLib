package net.exylia.lib.util.reward.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.reward.PendingBatch;
import net.exylia.lib.util.reward.PendingRewardRow;
import net.exylia.lib.util.reward.PendingRewards;
import net.exylia.lib.util.reward.RewardEntry;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The store a plugin gets when it does not want to write one.
 *
 * <p>Every plugin that queued a reward before this existed wrote the same
 * hundred lines: a table, a repository, a join listener and the delete that
 * must not be forgotten. The one that forgot the last of them handed the same
 * reward out on every join; the one that never wrote any of it lost the reward
 * entirely. Neither is a decision anybody made — they are what happens when the
 * boring half is left to each caller.
 *
 * <p>Not API. Reached through {@link PendingRewards#database(Plugin)}.
 *
 * @since 1.128.0
 */
public final class DatabasePending implements PendingRewards {

    /**
     * How many batches one player may be owed by one plugin through
     * {@link #keep(UUID, List, Consumer)}.
     *
     * <p>A queue is a place a bug writes to in a loop. Past this a batch is
     * handed back to the caller, who gives it to a player who is here, rather
     * than written: a bounded table and a line in the console rather than a
     * million rows nobody notices until the disk is full.
     */
    public static final int MOST_PER_PLAYER = 200;

    /**
     * How many a store writes when nobody can take a batch back.
     *
     * <p>A player who is not here cannot be handed anything, so past
     * {@link #MOST_PER_PLAYER} their batch is still written, said once a window.
     * This bounds a loop that writes for an offline player, and past it the
     * batch is lost and said so: a thousand batches is not a reward any more.
     */
    public static final int MOST_UNREFUSABLE = 1000;

    /** How long a counted ceiling is trusted before the table is counted again. */
    private static final long RECOUNT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /** One line per player per this long about refused batches, with how many. */
    private static final long REPORT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final String name;
    private final Repository<PendingRewardRow> rows;
    private final TaskScheduler tasks;
    private final Debug debug;

    /**
     * What this server knows of each player's rows. Another server sharing the
     * table writes past it, which the recount catches; the ceiling is a guard
     * against a loop, not a quota.
     */
    private final Cache<UUID, Ledger> ledgers = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    public DatabasePending(@NotNull Plugin plugin) {
        this(plugin, "");
    }

    /** A store in one scope's table: see {@code PluginDatabase#repository(Class, String)}. */
    public DatabasePending(@NotNull Plugin plugin, @NotNull String scope) {
        this.name = plugin.getName();
        this.rows = Databases.of(plugin).repository(PendingRewardRow.class, scope);
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nobody can take the batch back, so it is written past
     * {@link #MOST_PER_PLAYER}, up to {@link #MOST_UNREFUSABLE}.
     */
    @Override
    public void keep(@NotNull UUID player, @NotNull List<RewardEntry> owed) {
        keep(player, owed, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called on the thread that owed the reward. Once the player's count is
     * known a refusal is decided right here, with no task and no query; the
     * write itself goes to an asynchronous task, one player's writes in order,
     * so two batches racing each other cannot both slip under the ceiling.
     */
    @Override
    public void keep(@NotNull UUID player, @NotNull List<RewardEntry> owed,
                     @Nullable Consumer<List<RewardEntry>> refused) {
        int ceiling = refused == null ? MOST_UNREFUSABLE : MOST_PER_PLAYER;
        Ledger ledger = ledgers.get(player, id -> new Ledger());
        synchronized (ledger) {
            if (ledger.counted(System.currentTimeMillis()) && ledger.held + ledger.queued >= ceiling) {
                ledger.refused++;
            } else {
                ledger.queued++;
                PendingRewardRow row = new PendingRewardRow(name, player, owed);
                ledger.writes.add(() -> write(ledger, player, row, ceiling, refused));
                if (!ledger.writing) {
                    ledger.writing = true;
                    tasks.runAsync(() -> drain(ledger));
                }
                return;
            }
        }
        refuse(ledger, player, owed, refused);
    }

    /** Runs one player's writes in the order they were asked for. */
    private void drain(Ledger ledger) {
        while (true) {
            Runnable next;
            synchronized (ledger) {
                next = ledger.writes.poll();
                if (next == null) {
                    ledger.writing = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException broken) {
                // A refusal handler that threw must not leave the player's
                // writes stuck behind a drain that never finished.
                debug.error("A pending reward could not be handed back.", broken);
            }
        }
    }

    private void write(Ledger ledger, UUID player, PendingRewardRow row, int ceiling,
                       @Nullable Consumer<List<RewardEntry>> refused) {
        boolean decided = false;
        boolean written = false;
        try {
            if (!ledger.counted(System.currentTimeMillis())) {
                int held = rows.all()
                        .where("plugin", name)
                        .where("owner", player.toString())
                        .count()
                        .join()
                        .intValue();
                synchronized (ledger) {
                    ledger.held = held;
                    ledger.countedAt = System.currentTimeMillis();
                }
            }
            synchronized (ledger) {
                ledger.queued--;
                decided = true;
                if (ledger.held >= ceiling) {
                    ledger.refused++;
                } else {
                    // Counted before the save so a claim racing it cannot see a
                    // row the ledger does not; undone below if the save fails.
                    ledger.held++;
                    written = true;
                }
            }
            if (!written) {
                refuse(ledger, player, row.rewards(), refused);
                return;
            }
            rows.save(row).join();
        } catch (RuntimeException unwritable) {
            synchronized (ledger) {
                if (!decided) ledger.queued--;
                if (written) ledger.held--;
            }
            if (refused != null) {
                debug.error("Could not keep the rewards owed to " + player + "; they are handed back.",
                        unwritable);
                refused.accept(row.rewards());
            } else {
                debug.error("Could not keep the rewards owed to " + player + ".", unwritable);
            }
        }
    }

    /** Hands a refused batch back, and says so once a window rather than once a batch. */
    private void refuse(Ledger ledger, UUID player, List<RewardEntry> owed,
                        @Nullable Consumer<List<RewardEntry>> refused) {
        if (refused != null) {
            refused.accept(owed);
        }
        int count;
        int held;
        long now = System.currentTimeMillis();
        synchronized (ledger) {
            if (now - ledger.reportedAt < REPORT_MILLIS) return;
            count = ledger.refused;
            held = ledger.held;
            ledger.refused = 0;
            ledger.reportedAt = now;
        }
        String said = player + " is owed " + held + " batches of " + name + " rewards, past the ceiling. "
                + count + " more in the last " + TimeUnit.MILLISECONDS.toMinutes(REPORT_MILLIS) + " minutes";
        if (refused != null) {
            debug.warn(said + " were given to them now instead of kept: something is queueing rewards in a loop.");
        } else {
            debug.error(said + " were lost: something is queueing rewards in a loop for a player who is not here.");
        }
    }

    /** One player's rows as this server knows them. Guarded by its own monitor. */
    private static final class Ledger {
        /** Rows in the table, as last counted plus what this server did since. */
        int held;
        /** When {@link #held} was counted; {@code 0} before it ever was. */
        long countedAt;
        /** Accepted and not yet written. */
        int queued;
        /** Refused since the last line about it. */
        int refused;
        long reportedAt;
        final ArrayDeque<Runnable> writes = new ArrayDeque<>();
        boolean writing;

        boolean counted(long now) {
            return countedAt != 0 && now - countedAt < RECOUNT_MILLIS;
        }
    }

    /** A claim or a take removed rows; the ledger follows, never below zero. */
    private void forget(UUID player, int removed) {
        Ledger ledger = ledgers.getIfPresent(player);
        if (ledger == null || removed == 0) return;
        synchronized (ledger) {
            ledger.held = Math.max(0, ledger.held - removed);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Already off the main thread when it is called, so it reads and deletes
     * inline. The rows are deleted one by one, by id, and only the ones that
     * were actually deleted are returned: two claims racing each other — a
     * relog fast enough to overlap its own join — then split the batches
     * between them instead of both handing over the same one.
     */
    @Override
    public @NotNull List<RewardEntry> claim(@NotNull UUID player) {
        List<PendingRewardRow> held = held(player);
        if (held.isEmpty()) return List.of();

        List<RewardEntry> owed = new ArrayList<>();
        int taken = 0;
        for (PendingRewardRow row : held) {
            // Taken before it is handed over, and only what this call took: a
            // duplicated reward is an exploit, and a lost one is a ticket.
            if (Boolean.TRUE.equals(rows.delete(row.id()).join())) {
                owed.addAll(row.rewards());
                taken++;
            }
        }
        forget(player, taken);
        return owed;
    }

    @Override
    public boolean browsable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads every row this plugin keeps, payloads included. Bounded by
     * {@link #MOST_PER_PLAYER}, and only a staff screen asks.
     */
    @Override
    public @NotNull Map<UUID, Integer> owed() {
        Map<UUID, Integer> owed = new LinkedHashMap<>();
        for (PendingRewardRow row : rows.all().where("plugin", name).orderBy("owedAt").find().join()) {
            try {
                owed.merge(UUID.fromString(row.owner()), 1, Integer::sum);
            } catch (IllegalArgumentException unreadable) {
                debug.warn("Pending reward " + row.id() + " names no player: " + row.owner());
            }
        }
        return owed;
    }

    /** {@inheritDoc} One count query; no payload is read. */
    @Override
    public int count(@NotNull UUID player) {
        return rows.all().where("plugin", name).where("owner", player.toString()).count().join().intValue();
    }

    @Override
    public @NotNull List<PendingBatch> peek(@NotNull UUID player) {
        return held(player).stream()
                .map(row -> new PendingBatch(row.id(), row.owedAt(), null, row.rewards()))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deleted before it is returned, and returned only when this call was
     * the one that deleted it.
     */
    @Override
    public @NotNull List<RewardEntry> take(@NotNull UUID player, @NotNull String batch) {
        PendingRewardRow row = rows.find(batch).join().orElse(null);
        if (row == null || !row.plugin().equals(name) || !row.owner().equals(player.toString())) {
            return List.of();
        }
        if (!Boolean.TRUE.equals(rows.delete(row.id()).join())) {
            return List.of();
        }
        forget(player, 1);
        return row.rewards();
    }

    private List<PendingRewardRow> held(UUID player) {
        return rows.all()
                .where("plugin", name)
                .where("owner", player.toString())
                .orderBy("owedAt")
                .find()
                .join();
    }
}
