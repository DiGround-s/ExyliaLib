package net.exylia.lib.economy;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * The rules {@code currencies.yml} sets on a stored currency.
 *
 * <p>What a plugin that puts commands on a currency needs and nothing it does
 * not: the library keeps the balances and enforces the ceiling and the
 * decimals itself; these are the terms a {@code /pay} or a {@code /exchange}
 * command applies before it calls the API.
 *
 * @param aliases            the command names the owner wants: {@code /coins}
 * @param start              what a new player begins with
 * @param max                the ceiling, or a non-positive number for none
 * @param permission         needed to use the currency's commands; blank for nobody
 * @param transferable       whether players may send it to each other
 * @param minimumTransfer    the least they may send
 * @param transferTaxPercent kept back from every transfer
 * @param exchangeable       whether it may be swapped for other currencies
 * @param rates              other currency id → how much of it one unit is worth
 * @param leaderboard        whether it is ranked
 * @param networked          whether balances follow the player across servers
 * @param commands           whether the owner wants commands on it at all
 * @since 1.151.0
 */
public record CurrencyRules(
        @NotNull String id,
        @NotNull List<String> aliases,
        @NotNull BigDecimal start,
        @NotNull BigDecimal max,
        @NotNull String permission,
        boolean transferable,
        @NotNull BigDecimal minimumTransfer,
        double transferTaxPercent,
        boolean exchangeable,
        @NotNull Map<String, BigDecimal> rates,
        boolean leaderboard,
        boolean networked,
        boolean commands) {

    /** What the transfer tax keeps back from an amount, rounded in the sender's favour. */
    public @NotNull BigDecimal tax(@NotNull BigDecimal amount) {
        if (transferTaxPercent <= 0) return BigDecimal.ZERO;
        return Economy.info(id).scale(amount.multiply(BigDecimal.valueOf(transferTaxPercent / 100.0))
                .setScale(8, RoundingMode.DOWN));
    }
}
