package net.exylia.lib.util.reward.internal;

import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.Effects;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.RewardOutcome;
import net.exylia.lib.util.reward.RewardResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Handing one reward to one player.
 *
 * <p>Every method here runs on the thread that owns the player: the runtime puts
 * it there. None of them decide whether the reward <em>should</em> be given, only
 * how.
 */
public final class Providers {

    private Providers() {
        throw new AssertionError("No instances.");
    }

    /** What to do with an item that did not fit. Set by the runtime. */
    public interface Overflow {
        /**
         * Called with how many were left over.
         *
         * @param player    who was receiving it
         * @param entry     the reward
         * @param remaining how many did not fit
         * @return how the reward ended
         */
        @NotNull RewardOutcome leftOver(@NotNull Player player,
                                        @NotNull RewardEntry entry,
                                        int remaining);
    }

    /**
     * Gives a reward.
     *
     * @param entry    the reward
     * @param player   who gets it
     * @param amount   how many, already rolled
     * @param overflow what to do with an item that does not fit
     * @param items    how an item reaches a player
     * @return how it ended
     */
    public static @NotNull RewardResult give(@NotNull RewardEntry entry,
                                             @NotNull Player player,
                                             int amount,
                                             @NotNull Overflow overflow,
                                             @NotNull ItemGiver items) {
        try {
            return switch (entry.type()) {
                case COMMAND -> command(entry, player);
                case MESSAGE -> message(entry, player);
                case ITEM -> item(entry, player, amount, overflow, items);
                case ECONOMY -> economy(entry, player, amount);
                case EXPERIENCE -> experience(entry, player, amount);
                case POTION -> potion(entry, player);
            };
        } catch (RuntimeException failure) {
            // Per reward, so one bad row does not cost a player the rest of the
            // list. Commons caught this too, but only around the future, so a
            // cast that threw synchronously still killed the whole batch.
            return RewardResult.failed(entry, "could not be given", failure);
        }
    }

    // ------------------------------------------------------------------ types

    private static RewardResult command(RewardEntry entry, Player player) {
        String command = entry.command();
        if (command == null || command.isBlank()) {
            return RewardResult.failed(entry, "names no command");
        }
        String resolved = Placeholders.apply(command, player).trim();
        if (resolved.startsWith("/")) {
            resolved = resolved.substring(1);
        }
        if (resolved.isEmpty()) {
            return RewardResult.failed(entry, "resolves to an empty command");
        }
        boolean ran = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        return ran
                ? RewardResult.given(entry)
                : RewardResult.failed(entry, "the console refused \"" + resolved + "\"");
    }

    private static RewardResult message(RewardEntry entry, Player player) {
        String message = entry.message();
        if (message == null) {
            return RewardResult.failed(entry, "names no message");
        }
        Text.of(message).forPlayer(player).send(player);
        return RewardResult.given(entry);
    }

    private static RewardResult item(RewardEntry entry, Player player, int amount,
                                     Overflow overflow, ItemGiver items) {
        String snapshot = entry.itemSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return RewardResult.failed(entry, "names no item");
        }
        int remaining = items.give(player, snapshot, amount);
        if (remaining == ItemGiver.UNREADABLE) {
            return RewardResult.failed(entry, "has an item that could not be read");
        }
        if (remaining == 0) {
            return RewardResult.given(entry);
        }
        RewardOutcome outcome = overflow.leftOver(player, entry, remaining);
        return outcome == RewardOutcome.GIVEN
                ? RewardResult.given(entry)
                : RewardResult.skipped(entry, outcome);
    }

    private static RewardResult economy(RewardEntry entry, Player player, int amount) {
        BigDecimal money = money(entry, amount);
        if (money == null) {
            return RewardResult.failed(entry, "has an amount that is not a number: " + entry.value());
        }
        if (money.signum() <= 0) {
            return RewardResult.failed(entry, "pays nothing");
        }
        if (!Economy.isAvailable()) {
            return RewardResult.failed(entry, "pays money but no economy is installed");
        }
        EconomyResponse response = entry.currency() != null
                ? Economy.of(entry.currency()).deposit(player.getUniqueId(), money)
                : Economy.deposit(player.getUniqueId(), money);
        return response.isSuccess()
                ? RewardResult.given(entry)
                : RewardResult.failed(entry, "the economy refused it: " + response.message());
    }

    private static RewardResult experience(RewardEntry entry, Player player, int amount) {
        Integer points = entry.isRanged() ? amount : integer(entry.value());
        if (points == null) {
            return RewardResult.failed(entry, "has an amount that is not a number: " + entry.value());
        }
        if (points <= 0) {
            return RewardResult.failed(entry, "grants no experience");
        }
        player.giveExp(points);
        return RewardResult.given(entry);
    }

    private static RewardResult potion(RewardEntry entry, Player player) {
        String effect = entry.value();
        if (effect == null || effect.isBlank()) {
            return RewardResult.failed(entry, "names no effect");
        }
        if (Effects.parse(effect) == null) {
            return RewardResult.failed(entry, "has an effect that could not be read: " + effect);
        }
        Effects.apply(player, effect);
        return RewardResult.given(entry);
    }

    // ----------------------------------------------------------------- values

    private static @Nullable BigDecimal money(RewardEntry entry, int amount) {
        if (entry.isRanged()) {
            return BigDecimal.valueOf(amount);
        }
        String written = entry.value();
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(written.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static @Nullable Integer integer(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(written.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

}
