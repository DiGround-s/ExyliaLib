package net.exylia.lib.modifier;

import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.modifier.internal.ModifierRuntime;
import net.exylia.lib.modifier.internal.PermissionModifiers;
import net.exylia.lib.util.Multipliers;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.RewardType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One multiplier pipeline for everything players earn: money, experience,
 * drops, and any type a plugin adds.
 *
 * <pre>{@code
 * // a plugin that pays: run the payout through the pipeline
 * BigDecimal paid = Modifiers.pay(player, "missions", mission.money());
 * List<ItemStack> loot = Modifiers.drops(id, "mines", drops, "mine:" + mine.id());
 * int xp = Modifiers.xp(id, "mines", points);
 * rewards.give(player, Modifiers.rewards(id, "crates", won));
 * int elo = (int) Modifiers.apply(id, "elo", "ranked", gained);
 *
 * // a plugin that boosts: hand over a factor
 * Modifiers.source(this, "ranked", "IRON_SWORD", "elo");
 * Modifiers.register(this, (player, type, source, scope) -> "elo".equals(type) ? eloBoost(player) : 1.0);
 * }</pre>
 *
 * <h2>How factors combine</h2>
 * Every provider's answer is <b>multiplied</b>: a booster plugin at x2 and a
 * rank at x1.5 make x3. A provider stacks its own boosts however it likes and
 * hands over one number, so a booster plugin's own rule (add, multiply,
 * highest, a cap) still holds among its boosters. No provider, or every
 * provider answering {@code 1.0}, is the identity: the payout is returned as
 * it came.
 *
 * <h2>Types</h2>
 * Open strings, lower case. {@link #MONEY}, {@link #XP} and {@link #DROPS} are
 * the built-ins; a plugin may use its own ({@code elo}, {@code clan-exp}) by
 * passing it here and naming it in {@link #source}. A provider moves only the
 * types it names, so a money booster for every source ({@code *}) never moves
 * Elo.
 *
 * <h2>Permissions</h2>
 * Built in: {@code exylia.modifier.<type>.<source>.<factor>}, for example
 * {@code exylia.modifier.money.*.1.5} (every money source) or
 * {@code exylia.modifier.drops.mines.2}. Among a player's matching nodes the
 * highest wins, and that one is multiplied with the other providers. Read
 * once and kept a few seconds, dropped on join, quit and
 * {@code /exylialib reload}; only a player online on this server has them.
 *
 * <h2>What is boosted</h2>
 * Money a player <em>earns</em>. Refunds, transfers between players, market
 * sales and admin gives are never run through here. Money is boosted in the
 * default currency only; another currency is paid as it is. A payout kept for
 * an offline player is boosted when it is queued, not when it is claimed, so
 * it keeps the boost it was earned with.
 *
 * <h2>Threads and lifecycle</h2>
 * Every method is synchronous, memory only and safe from any thread. What a
 * plugin registered is released when it disables; a plugin that switches a
 * provider off at runtime calls {@link #unregister(ModifierProvider)}.
 *
 * @since 1.263.0
 */
public final class Modifiers {

    /** Money paid in the default currency. */
    public static final String MONEY = "money";
    /** Experience points. */
    public static final String XP = "xp";
    /** Items. */
    public static final String DROPS = "drops";
    /** As a source: every source of a type. */
    public static final String ANY = "*";

    private Modifiers() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------ registry

    /**
     * Adds a provider. Released with the plugin.
     *
     * @param plugin   whose it is
     * @param provider the provider
     */
    public static void register(@NotNull Plugin plugin, @NotNull ModifierProvider provider) {
        ModifierRuntime.register(plugin.getName(), provider);
    }

    /** Takes a provider away before its plugin disables, such as a module switched off. */
    public static void unregister(@NotNull ModifierProvider provider) {
        ModifierRuntime.unregister(provider);
    }

    /**
     * Lists a source for booster screens. Listing it again replaces the
     * plugin's own entry; when two plugins list one id, the first stays.
     *
     * <pre>{@code
     * Modifiers.source(this, "mines", "IRON_PICKAXE", Modifiers.MONEY, Modifiers.XP, Modifiers.DROPS);
     * }</pre>
     *
     * @param plugin whose it is
     * @param id     the source id callers pass
     * @param icon   what a screen draws it with
     * @param types  the types it pays
     * @return the listed source
     */
    public static @NotNull ModifierSource source(@NotNull Plugin plugin, @NotNull String id, @NotNull String icon,
                                                 @NotNull String... types) {
        Set<String> normalised = new java.util.LinkedHashSet<>();
        for (String type : types) normalised.add(normalise(type));
        ModifierSource source = new ModifierSource(normalise(id), icon, normalised);
        ModifierRuntime.source(plugin.getName(), source);
        return source;
    }

    /** Every listed source, in the order they were listed. */
    public static @NotNull List<ModifierSource> sources() {
        return ModifierRuntime.sources();
    }

    /** The listed sources that pay a type. */
    public static @NotNull List<ModifierSource> sources(@NotNull String type) {
        return sources().stream().filter(source -> source.supports(type)).toList();
    }

    /** The listed source an id means, if anybody listed it. */
    public static @NotNull Optional<ModifierSource> source(@Nullable String id) {
        if (id == null) return Optional.empty();
        String wanted = normalise(id);
        return sources().stream().filter(source -> source.id().equals(wanted)).findFirst();
    }

    /** Drops a player's cached permission modifiers, after a rank change the server knows about. */
    public static void invalidate(@NotNull UUID player) {
        PermissionModifiers.forget(player);
    }

    // ------------------------------------------------------------------ factors

    /** The multiplier on a payout; {@code 1.0} when nothing applies. */
    public static double factor(@NotNull UUID player, @NotNull String type, @NotNull String source) {
        return factor(player, type, source, null);
    }

    /**
     * The multiplier on a payout inside a scope.
     *
     * @param scope where inside the source, such as {@code mine:gold}, for providers that boost one place
     */
    public static double factor(@NotNull UUID player, @NotNull String type, @NotNull String source,
                                @Nullable String scope) {
        return ModifierRuntime.factor(player, normalise(type), normalise(source), scope);
    }

    /**
     * Any amount multiplied, for a plugin's own type.
     *
     * <pre>{@code
     * int elo = (int) Math.round(Modifiers.apply(id, "elo", "ranked", 25));
     * }</pre>
     */
    public static double apply(@NotNull UUID player, @NotNull String type, @NotNull String source, double amount) {
        return amount * factor(player, type, source);
    }

    /** {@link #apply(UUID, String, String, double)} inside a scope. */
    public static double apply(@NotNull UUID player, @NotNull String type, @NotNull String source,
                               @Nullable String scope, double amount) {
        return amount * factor(player, type, source, scope);
    }

    /**
     * The money multiplier for a price in one currency: {@code 1.0} for any
     * currency but the default, which is the only one boosted.
     *
     * @param currency the currency id; {@code null} or blank is the default
     */
    public static double moneyFactor(@NotNull UUID player, @NotNull String source, @Nullable String currency) {
        if (currency != null && !currency.isBlank()
                && !Economy.canonical(currency).equalsIgnoreCase(Economy.defaultId())) {
            return 1.0;
        }
        return factor(player, MONEY, source);
    }

    // ------------------------------------------------------------------ money

    /**
     * Money in the default currency, boosted and rounded down to its decimals.
     *
     * @return the same amount when nothing applies
     */
    public static @NotNull BigDecimal money(@NotNull UUID player, @NotNull String source, @NotNull BigDecimal amount) {
        return money(player, source, null, amount);
    }

    /** {@link #money(UUID, String, BigDecimal)} for an online player. */
    public static @NotNull BigDecimal money(@NotNull Player player, @NotNull String source, @NotNull BigDecimal amount) {
        return money(player.getUniqueId(), source, amount);
    }

    /** {@link #money(UUID, String, BigDecimal)} inside a scope. */
    public static @NotNull BigDecimal money(@NotNull UUID player, @NotNull String source, @Nullable String scope,
                                            @NotNull BigDecimal amount) {
        return scaledMoney(amount, factor(player, MONEY, source, scope));
    }

    private static BigDecimal scaledMoney(BigDecimal amount, double factor) {
        if (factor == 1.0) return amount;
        CurrencyInfo currency = Economy.info(null);
        int decimals = currency.decimals() < 0 ? 2 : currency.decimals();
        return currency.scale(amount.multiply(BigDecimal.valueOf(factor)).setScale(decimals, RoundingMode.DOWN));
    }

    /**
     * Pays money a player earned in the default currency, boosted.
     *
     * <p>Never for a refund or a transfer: those give back exactly what was taken.
     *
     * @return what was paid, zero when nothing was (no amount, no economy, the deposit refused)
     */
    public static @NotNull BigDecimal pay(@NotNull Player player, @NotNull String source, @Nullable BigDecimal amount) {
        return pay(player.getUniqueId(), source, null, amount);
    }

    /**
     * {@link #pay(Player, String, BigDecimal)} by id, inside a scope. A player
     * not online here gets no permission modifier.
     */
    public static @NotNull BigDecimal pay(@NotNull UUID player, @NotNull String source, @Nullable String scope,
                                          @Nullable BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || !Economy.isAvailable()) return BigDecimal.ZERO;
        BigDecimal paid = money(player, source, scope, amount);
        return Economy.pay(player, paid) ? paid : BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ xp and drops

    /** Experience points, boosted, the fraction rolled (see {@link Multipliers#scale(int, double)}). */
    public static int xp(@NotNull UUID player, @NotNull String source, int points) {
        return xp(player, source, null, points);
    }

    /** {@link #xp(UUID, String, int)} inside a scope. */
    public static int xp(@NotNull UUID player, @NotNull String source, @Nullable String scope, int points) {
        if (points <= 0) return points;
        return Multipliers.scale(points, factor(player, XP, source, scope));
    }

    /**
     * Items, boosted, each fraction rolled and cut into stacks no larger than
     * the item allows.
     *
     * @param items never changed
     * @return new stacks; copies of the same ones when nothing applies
     */
    public static @NotNull List<ItemStack> drops(@NotNull UUID player, @NotNull String source,
                                                 @NotNull Collection<ItemStack> items) {
        return drops(player, source, items, null);
    }

    /** {@link #drops(UUID, String, Collection)} inside a scope. */
    public static @NotNull List<ItemStack> drops(@NotNull UUID player, @NotNull String source,
                                                 @NotNull Collection<ItemStack> items, @Nullable String scope) {
        return Multipliers.scale(items, factor(player, DROPS, source, scope));
    }

    // ------------------------------------------------------------------ reward lists

    /**
     * A reward list, boosted: money lines in the default currency by
     * {@link #MONEY}, experience lines by {@link #XP}, item lines by
     * {@link #DROPS}. A ranged amount has both ends boosted; commands and the
     * rest are handed over as they are.
     *
     * <p>The rewards module pays a list itself, never through {@link #pay}, so
     * boost the list before handing it over; one kept for a later join keeps
     * the boost it was earned with.
     *
     * <pre>{@code
     * plugin.rewards().give(player, Modifiers.rewards(id, "crates", won));
     * }</pre>
     *
     * @return the same list when nothing applies
     */
    public static @NotNull List<RewardEntry> rewards(@NotNull UUID player, @NotNull String source,
                                                     @NotNull List<RewardEntry> rewards) {
        if (rewards.isEmpty()) return rewards;
        double money = factor(player, MONEY, source);
        double xp = factor(player, XP, source);
        double drops = factor(player, DROPS, source);
        if (money == 1.0 && xp == 1.0 && drops == 1.0) return rewards;
        List<RewardEntry> out = new ArrayList<>(rewards.size());
        for (RewardEntry entry : rewards) {
            out.add(switch (entry.type()) {
                case ECONOMY -> money == 1.0 ? entry : boostMoney(entry, player, source);
                case EXPERIENCE -> scaled(entry, xp);
                case ITEM -> scaled(entry, drops);
                default -> entry;
            });
        }
        return out;
    }

    private static RewardEntry scaled(RewardEntry entry, double factor) {
        if (factor == 1.0) return entry;
        if (entry.isRanged()) {
            int min = Multipliers.scale(entry.minAmount(), factor);
            return entry.toBuilder().amountBetween(min, Math.max(min, Multipliers.scale(entry.maxAmount(), factor)))
                    .build();
        }
        // An experience line keeps its points in the value, an item line its count in the amount.
        if (entry.type() == RewardType.EXPERIENCE) {
            try {
                int points = Integer.parseInt(String.valueOf(entry.value()).trim());
                return entry.toBuilder().value(String.valueOf(Multipliers.scale(points, factor))).build();
            } catch (NumberFormatException notAnAmount) {
                // Refused by the delivery as it is, boosted or not.
                return entry;
            }
        }
        return entry.toBuilder().fixedAmount(Multipliers.scale(Math.max(1, entry.itemAmount()), factor)).build();
    }

    private static RewardEntry boostMoney(RewardEntry entry, UUID player, String source) {
        double factor = moneyFactor(player, source, entry.currency());
        if (factor == 1.0) return entry;
        if (entry.isRanged()) {
            return entry.toBuilder().amountBetween((int) Math.floor(entry.minAmount() * factor),
                    (int) Math.floor(entry.maxAmount() * factor)).build();
        }
        try {
            BigDecimal amount = new BigDecimal(entry.value().trim());
            return entry.toBuilder().value(scaledMoney(amount, factor).toPlainString()).build();
        } catch (RuntimeException notAnAmount) {
            // Not a number: refused by the delivery as it is, boosted or not.
            return entry;
        }
    }

    /** Lower case and trimmed, the form types and sources are compared in. */
    static @NotNull String normalise(@NotNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
