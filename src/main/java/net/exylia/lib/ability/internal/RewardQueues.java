package net.exylia.lib.ability.internal;

import net.exylia.lib.util.reward.OverflowPolicy;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.reward.Rewards;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** Finds the reward queue a plugin chose for what does not fit in an inventory. */
public final class RewardQueues {

    private RewardQueues() {
    }

    /** @return the plugin's rewards when it chose {@link OverflowPolicy#QUEUE}, else {@code null} */
    public static @Nullable PluginRewards of(Plugin plugin) {
        String owner = plugin.getName();
        for (PluginRewards rewards : Rewards.all()) {
            if (rewards.plugin().getName().equals(owner) && rewards.overflow() == OverflowPolicy.QUEUE) return rewards;
        }
        return null;
    }
}
