package net.exylia.lib.economy;

import net.exylia.lib.economy.internal.PlayerThreadBalances;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.reward.RewardEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A player's experience as a currency: their levels, or their points.
 *
 * <pre>{@code
 * PluginRewards rewards = Rewards.of(this).pending(PendingRewards.database(this));
 * Economy.register(ExperienceCurrency.levels(rewards));
 * }</pre>
 *
 * <p>Experience is on the player, so only somebody on this server has a
 * balance or can pay, and only on their own thread. Paying them works
 * anywhere: somebody who is not here, or who leaves before their thread runs
 * the deposit, is owed it through the plugin's pending rewards and receives it
 * when they next join. A plugin without a pending store is told the deposit
 * failed rather than losing it silently.
 *
 * <p>A balance asked off the player's thread answers the last one counted
 * there and refreshes it (see {@code PlayerThreadBalances}), so a scoreboard
 * on an async timer never touches the player.
 *
 * <p>Points are the player's total, {@code Player#calculateTotalExperiencePoints};
 * paying them takes from the top, exactly as an enchantment table does.
 *
 * @since 1.163.0
 */
public final class ExperienceCurrency implements CurrencyProvider {

    /** The id of the levels currency. */
    public static final String LEVELS = "xp_levels";
    /** The id of the points currency. */
    public static final String POINTS = "xp_points";

    private final boolean levels;
    private final CurrencyInfo info;
    private final PluginRewards rewards;
    private final TaskScheduler tasks;
    private final PlayerThreadBalances balances;

    private ExperienceCurrency(boolean levels, PluginRewards rewards) {
        this.levels = levels;
        this.rewards = rewards;
        this.tasks = Tasks.of(rewards.plugin());
        this.info = levels
                ? new CurrencyInfo(LEVELS, "Level", "Levels", "", "EXPERIENCE_BOTTLE", 0, "%amount% %name%", "%amount% Lv")
                : new CurrencyInfo(POINTS, "XP", "XP", "", "EXPERIENCE_BOTTLE", 0, "%amount% %name%", "%amount% XP");
        this.balances = new PlayerThreadBalances(tasks, player ->
                BigDecimal.valueOf(levels ? player.getLevel() : player.calculateTotalExperiencePoints()));
    }

    /**
     * Experience levels, as {@value #LEVELS}.
     *
     * @param rewards the plugin's rewards, whose pending store keeps what an absent player is owed
     * @return the currency, to register
     */
    public static @NotNull ExperienceCurrency levels(@NotNull PluginRewards rewards) {
        return new ExperienceCurrency(true, rewards);
    }

    /**
     * Experience points, as {@value #POINTS}.
     *
     * @param rewards the plugin's rewards, whose pending store keeps what an absent player is owed
     * @return the currency, to register
     */
    public static @NotNull ExperienceCurrency points(@NotNull PluginRewards rewards) {
        return new ExperienceCurrency(false, rewards);
    }

    @Override
    public @NotNull String id() {
        return info.id();
    }

    @Override
    public @NotNull String displayName() {
        return levels ? "Experience levels" : "Experience points";
    }

    @Override
    public @NotNull CurrencyInfo info() {
        return info;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        return balances.balance(player);
    }

    @Override
    public @NotNull java.util.concurrent.CompletableFuture<BigDecimal> balanceLater(@NotNull UUID player) {
        return balances.later(player);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        int units = PlayerThreadBalances.units(amount);
        if (units <= 0) {
            return EconomyResponse.invalidAmount();
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null && tasks.isOwnedBy(online)) {
            give(online, units);
            return EconomyResponse.success(BigDecimal.valueOf(units), balances.read(online));
        }
        boolean queued = true;
        if (online != null) {
            try {
                tasks.runAtEntity(online, () -> give(online, units), () -> owe(player, units));
            } catch (RuntimeException stopped) {
                queued = owe(player, units);
            }
        } else {
            queued = owe(player, units);
        }
        return queued
                ? EconomyResponse.success(BigDecimal.valueOf(units), balances.last(player))
                : EconomyResponse.failure("The experience could not be kept for a player who is not here.");
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        Player online = Bukkit.getPlayer(player);
        if (online == null || !tasks.isOwnedBy(online)) {
            return EconomyResponse.failure("Experience can only be taken on the player's own thread, on their server.");
        }
        int units = PlayerThreadBalances.units(amount);
        if (units <= 0) {
            return EconomyResponse.invalidAmount();
        }
        BigDecimal current = balances.read(online);
        if (current.compareTo(BigDecimal.valueOf(units)) < 0) {
            return EconomyResponse.insufficientFunds(amount, current);
        }
        if (levels) {
            online.giveExpLevels(-units);
        } else {
            online.setExperienceLevelAndProgress(Math.max(0, online.calculateTotalExperiencePoints() - units));
        }
        return EconomyResponse.success(BigDecimal.valueOf(units), balances.read(online));
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        return plural ? info.namePlural() : info.name();
    }

    @Override
    public @NotNull String symbol() {
        return "";
    }

    private void give(Player online, int units) {
        if (levels) {
            online.giveExpLevels(units);
        } else {
            online.giveExp(units);
        }
        balances.read(online);
    }

    /**
     * Keeps a deposit for a player who is not here.
     *
     * <p>Points are a reward of their own. Levels have none, and how many
     * points a level is worth depends on the level the player will be at, so
     * they are owed as the vanilla command that adds levels.
     */
    private boolean owe(UUID player, int units) {
        if (!levels) {
            return rewards.giveLater(player, List.of(RewardEntry.experience(units).build()));
        }
        String name = Bukkit.getOfflinePlayer(player).getName();
        return name != null && rewards.giveLater(player,
                List.of(RewardEntry.command("minecraft:xp add " + name + " " + units + " levels").build()));
    }
}
