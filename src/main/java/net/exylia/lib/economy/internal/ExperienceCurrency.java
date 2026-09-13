package net.exylia.lib.economy.internal;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A player's experience as a currency: their levels, or their points.
 *
 * <p>Experience is on the player, so only somebody on this server has a
 * balance or can pay. Paying them works anywhere: somebody who is not here is
 * given it on the next server that holds them.
 *
 * <p>Points are the total a player has ever earned towards their current
 * state, read through {@code Player#calculateTotalExperiencePoints}; paying
 * them takes from the top, exactly as an enchantment table does.
 */
public final class ExperienceCurrency implements CurrencyProvider {

    public static final String LEVELS = "xp_levels";
    public static final String POINTS = "xp_points";

    private final boolean levels;
    private final CurrencyInfo info;
    private final StoredEconomy economy;

    ExperienceCurrency(boolean levels, StoredEconomy economy) {
        this.levels = levels;
        this.economy = economy;
        this.info = levels
                ? new CurrencyInfo(LEVELS, "Level", "Levels", "", "EXPERIENCE_BOTTLE", 0, "%amount% %name%", "%amount% Lv")
                : new CurrencyInfo(POINTS, "XP", "XP", "", "EXPERIENCE_BOTTLE", 0, "%amount% %name%", "%amount% XP");
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
        Player online = Bukkit.getPlayer(player);
        if (online == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(levels ? online.getLevel() : online.calculateTotalExperiencePoints());
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        return deposit(player, amount, Transaction.NONE);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount,
                                            @NotNull Transaction transaction) {
        int units = amount.intValue();
        if (units <= 0) return EconomyResponse.invalidAmount();
        EconomyResponse later = economy.give(this, player, BigDecimal.valueOf(units), transaction, online -> {
            if (levels) {
                online.giveExpLevels(units);
            } else {
                online.giveExp(units);
            }
        });
        return later != null ? later : EconomyResponse.success(BigDecimal.valueOf(units), balance(player));
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) return EconomyResponse.failure("Experience can only be taken from a player who is here.");
        int units = amount.intValue();
        if (units <= 0) return EconomyResponse.invalidAmount();
        BigDecimal current = balance(player);
        if (current.compareTo(BigDecimal.valueOf(units)) < 0) {
            return EconomyResponse.insufficientFunds(amount, current);
        }
        if (levels) {
            online.giveExpLevels(-units);
        } else {
            online.setExperienceLevelAndProgress(Math.max(0, online.calculateTotalExperiencePoints() - units));
        }
        return EconomyResponse.success(BigDecimal.valueOf(units), balance(player));
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        return plural ? info.namePlural() : info.name();
    }

    @Override
    public @NotNull String symbol() {
        return "";
    }
}
