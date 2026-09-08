package net.exylia.lib.util.reward;

import net.exylia.lib.util.reward.internal.DatabasePending;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Where rewards wait for a player who is not there.
 *
 * <p>The library does not decide this. ExyliaCapture keeps them in
 * {@code capture_pending_rewards}, ExyliaEvents in {@code event_pending_rewards},
 * and both tables are full of rows a player is still owed. A store the library
 * imposed would either ignore those rows or force a migration, and this module
 * exists precisely so nobody has to migrate anything.
 *
 * <p>A plugin that has no table yet, and no reason to want one, asks for the
 * one the library can keep for it:
 *
 * <pre>{@code
 * rewards.pending(PendingRewards.database(this)).claimOnJoin();
 * }</pre>
 *
 * <p>A plugin with rows of its own hands its own store over instead:
 *
 * <pre>{@code
 * rewards.pending(new PendingRewards() {
 *     public void keep(UUID player, List<RewardEntry> owed) {
 *         // Not inline: this runs on the player's thread. See below.
 *         tasks.runAsync(() -> repository.save(new PendingReward(player, event, owed)));
 *     }
 *     public List<RewardEntry> claim(UUID player) {
 *         return repository.takeAllFor(player);
 *     }
 * });
 * }</pre>
 *
 * <h2>Threading</h2>
 * The two are not alike, and getting this wrong costs TPS:
 *
 * <ul>
 *   <li>{@link #claim} is called <b>off the main thread</b>, by
 *       {@link PluginRewards#claim}. Read the database directly; it is already
 *       somewhere it may.</li>
 *   <li>{@link #keep} is called <b>on whatever thread owed the reward</b>, which
 *       for an overflowing item is the player's, and on Spigot and Paper that is
 *       the main thread. It must <b>not</b> write to a database inline: hand the
 *       write to {@code Tasks.of(plugin).runAsync(...)} and return.</li>
 * </ul>
 *
 * @since 1.34.0
 */
public interface PendingRewards {

    /**
     * The store the library keeps, in the plugin's own database.
     *
     * <p>ExyliaLib has no database. This writes to the one the consumer plugin
     * already configured in {@code plugins/<Plugin>/database.yml} — H2 in a
     * file unless the owner said otherwise — in a table named
     * {@code exylia_pending_rewards}. Two plugins pointed at the same database
     * share the table and are told apart by the column that names them.
     *
     * <p>Both halves of the threading contract below are handled: the write is
     * scheduled and the read is already off the main thread. Pair it with
     * {@link PluginRewards#claimOnJoin()} and there is nothing else to write.
     *
     * @param plugin whose database, and whose rewards
     * @return a store backed by that database
     * @since 1.127.0
     */
    static @NotNull PendingRewards database(@NotNull Plugin plugin) {
        return new DatabasePending(plugin);
    }

    /**
     * Keeps rewards for a player who could not receive them.
     *
     * <p>Called with everything that was left over at once, so a store can write
     * a single row.
     *
     * <p>Runs on the thread that owed the reward, which is usually the main
     * one. Schedule the write; do not perform it here.
     *
     * @param player who is owed them
     * @param owed   what they are owed, never empty
     */
    void keep(@NotNull UUID player, @NotNull List<RewardEntry> owed);

    /**
     * Takes everything a player is owed, leaving nothing behind.
     *
     * <p>Must not return the same rewards twice: whatever this hands back is
     * about to be given, and a store that forgets to clear its rows hands out a
     * reward on every join forever. Clearing before delivery rather than after is
     * deliberate &mdash; a duplicated reward is an exploit, a lost one is a
     * support ticket.
     *
     * @param player whose rewards
     * @return what they were owed, possibly empty
     */
    @NotNull List<RewardEntry> claim(@NotNull UUID player);
}
