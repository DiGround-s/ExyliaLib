package net.exylia.lib.util.reward.internal;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.reward.PendingRewardRow;
import net.exylia.lib.util.reward.PendingRewards;
import net.exylia.lib.util.reward.RewardEntry;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * @since 1.127.0
 */
public final class DatabasePending implements PendingRewards {

    /**
     * How many batches one player may be owed by one plugin.
     *
     * <p>A queue is a place a bug writes to in a loop. Past this the write is
     * refused and said once, which is a bounded table and a line in the console
     * rather than a million rows nobody notices until the disk is full.
     */
    private static final int MOST_PER_PLAYER = 200;

    private final String name;
    private final Repository<PendingRewardRow> rows;
    private final TaskScheduler tasks;
    private final Debug debug;

    public DatabasePending(@NotNull Plugin plugin) {
        this.name = plugin.getName();
        this.rows = Databases.of(plugin).repository(PendingRewardRow.class);
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called on the thread that owed the reward, so the write is handed
     * straight to an asynchronous task and nothing here touches the database on
     * a game thread.
     */
    @Override
    public void keep(@NotNull UUID player, @NotNull List<RewardEntry> owed) {
        PendingRewardRow row = new PendingRewardRow(name, player, owed);
        tasks.runAsync(() -> {
            try {
                long held = rows.all()
                        .where("plugin", name)
                        .where("owner", player.toString())
                        .count()
                        .join();
                if (held >= MOST_PER_PLAYER) {
                    debug.error(player + " is already owed " + held + " batches of rewards, which is"
                            + " the ceiling. The newest is refused rather than written: something is"
                            + " queueing rewards in a loop.");
                    return;
                }
                rows.save(row).join();
            } catch (RuntimeException unwritable) {
                debug.error("Could not keep the rewards owed to " + player + ".", unwritable);
            }
        });
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
        List<PendingRewardRow> held = rows.all()
                .where("plugin", name)
                .where("owner", player.toString())
                .orderBy("owedAt")
                .find()
                .join();
        if (held.isEmpty()) return List.of();

        List<RewardEntry> owed = new ArrayList<>();
        for (PendingRewardRow row : held) {
            // Taken before it is handed over, and only what this call took: a
            // duplicated reward is an exploit, and a lost one is a ticket.
            if (Boolean.TRUE.equals(rows.delete(row.id()).join())) {
                owed.addAll(row.rewards());
            }
        }
        return owed;
    }
}
