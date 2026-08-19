package net.exylia.lib.util.reward;

import net.exylia.lib.util.reward.internal.Rolls;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Giving a player what they earned.
 *
 * <pre>{@code
 * PluginRewards rewards = Rewards.of(this);
 *
 * rewards.give(winner, event.rewards());
 * }</pre>
 *
 * <h2>What it does</h2>
 * A reward is a thing a server owner configured &mdash; an item, a command, some
 * money &mdash; along with the odds of getting it and who is allowed to. This
 * module holds them, decides which ones happen, and hands them over on the right
 * thread.
 *
 * <h2>The stored form is ExyliaCommons'</h2>
 * Every reward already configured across the ecosystem lives in a database
 * column, written by the old module. {@link RewardCodec} reads and writes exactly
 * that shape, so a plugin migrating to this library changes its imports and
 * nothing else: no migration, no dual-read window, no lost configuration. Three
 * of its bugs are not reproduced, and each is named where it was fixed.
 *
 * <h2>What it guarantees</h2>
 * <ul>
 *   <li><b>Nothing vanishes.</b> An item a player has no room for is dropped,
 *       queued or reported, never destroyed. Commons destroyed it.</li>
 *   <li><b>One bad reward costs only itself.</b> A broken row is reported once
 *       and skipped; the rest of the list is still given.</li>
 *   <li><b>A skip is not a failure.</b> Losing a roll, lacking a permission and
 *       throwing are three different outcomes and report as three.</li>
 *   <li><b>Nothing outlives its plugin.</b> A disabled plugin's view is dropped
 *       and its pending store forgotten.</li>
 * </ul>
 *
 * <h2>Ready for an editor</h2>
 * ExyliaCommons carried a hardcoded menu for editing rewards. That menu is not
 * here yet, and everything it will need is: {@link RewardEntry} is immutable with
 * a {@link RewardEntry#toBuilder() builder} that keeps its identity,
 * {@link RewardEntry#copy()} duplicates a row, {@link RewardEntry#displayName()}
 * and {@link RewardEntry#resolvedIcon()} draw one without a server, and
 * {@link RewardCodec} round-trips a list. An editor built on those touches no
 * internals.
 *
 * @since 1.34.0
 */
public final class Rewards {

    private static final Map<String, PluginRewards> BY_PLUGIN = new ConcurrentHashMap<>();

    private Rewards() {
        throw new AssertionError("No instances.");
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginRewards of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), key -> new PluginRewards(plugin));
    }

    // ------------------------------------------------------------------ config

    /**
     * Reads a list of rewards from configuration.
     *
     * <p>The section is a list of maps whose keys are the stored field names, so
     * a file and a database column say the same thing:
     *
     * <pre>{@code
     * rewards:
     *   - type: ITEM
     *     itemSnapshot: DIAMOND
     *     itemAmount: 4
     *     chance: 25.0
     *   - type: COMMAND
     *     command: "eco give %player_name% 500"
     * }</pre>
     *
     * @param section  the section holding the list
     * @param key      the key of the list within it
     * @param problems told where the trouble was and what it was
     * @return the rewards that could be read
     */
    public static @NotNull List<RewardEntry> read(@NotNull ConfigurationSection section,
                                                  @NotNull String key,
                                                  @NotNull BiConsumer<String, String> problems) {
        List<?> raw = section.getList(key);
        if (raw == null) {
            return List.of();
        }
        List<RewardEntry> rewards = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Object element = raw.get(index);
            String where = key + "[" + index + "]";
            if (element instanceof String line) {
                // A bare string is a command. Hundreds of files already write a
                // reward list that way and none of them are going to be rewritten.
                rewards.add(RewardEntry.command(line).build());
                continue;
            }
            if (!(element instanceof Map<?, ?> map)) {
                problems.accept(where, "is not a reward");
                continue;
            }
            RewardEntry entry = fromMap(map, where, problems);
            if (entry != null) {
                rewards.add(entry);
            }
        }
        return List.copyOf(rewards);
    }

    private static @Nullable RewardEntry fromMap(Map<?, ?> map, String where,
                                                 BiConsumer<String, String> problems) {
        // Routed through the codec so a file and a column cannot drift apart:
        // there is exactly one place that knows what a stored reward looks like.
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        for (Map.Entry<?, ?> field : map.entrySet()) {
            Object value = field.getValue();
            String name = String.valueOf(field.getKey());
            if (value instanceof Number number) {
                json.addProperty(name, number);
            } else if (value instanceof Boolean flag) {
                json.addProperty(name, flag);
            } else if (value != null) {
                json.addProperty(name, String.valueOf(value));
            }
        }
        List<RewardEntry> read = RewardCodec.decode("[" + json + "]",
                (ignored, problem) -> problems.accept(where, problem));
        return read.isEmpty() ? null : read.get(0);
    }

    // ------------------------------------------------------------------ items

    /**
     * Serialises an item the way a reward stores one.
     *
     * <p>What an editor calls when a server owner drops an item into a slot.
     * Byte-identical to ExyliaCommons' {@code ItemSnapshot}, so a reward written
     * here is readable by a plugin still on the old module.
     *
     * @param item the item
     * @return the stored form
     */
    public static @NotNull String snapshot(@Nullable ItemStack item) {
        return net.exylia.lib.util.reward.internal.ItemGiver.snapshot(item);
    }

    /**
     * Rebuilds an item a reward stored.
     *
     * @param snapshot the stored form
     * @return the item, or {@code null} if the string names nothing
     */
    public static @Nullable ItemStack item(@NotNull String snapshot) {
        return net.exylia.lib.util.reward.internal.ItemGiver.build(snapshot);
    }

    // -------------------------------------------------------------- lifecycle

    /**
     * Forgets one plugin's view.
     *
     * <p>Called by the library when the plugin is disabled. Nothing has to be
     * stopped: a delivery is over by the time it returns, and a pending store
     * belongs to the plugin that is going away.
     *
     * @param pluginName the plugin's name
     */
    public static void release(@NotNull String pluginName) {
        BY_PLUGIN.remove(pluginName);
    }

    /** Forgets every plugin's view, on shutdown. */
    public static void releaseAll() {
        BY_PLUGIN.clear();
    }

    /** How many plugins are using the module. */
    public static int registered() {
        return BY_PLUGIN.size();
    }

    /** Replaces the dice for one plugin. Tests only. */
    static void dice(@NotNull PluginRewards rewards, @NotNull Rolls.Dice replacement) {
        rewards.dice(replacement);
    }

    /** Replaces how items reach players, for one plugin. Tests only. */
    static void items(@NotNull PluginRewards rewards,
                      @NotNull net.exylia.lib.util.reward.internal.ItemGiver replacement) {
        rewards.items(replacement);
    }
}
