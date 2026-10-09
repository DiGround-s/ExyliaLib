package net.exylia.lib.modifier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Something that multiplies what players earn: a booster plugin, a rank, a
 * per-mine boost.
 *
 * <pre>{@code
 * Modifiers.register(this, (player, type, source, scope) -> {
 *     if (!Modifiers.XP.equals(type)) return 1.0;     // never touch a type you do not name
 *     return events.doubleXpWeekend() ? 2.0 : 1.0;
 * });
 * }</pre>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>Memory only, synchronous, any thread.</b> It is asked on hot paths
 *       — every block broken, every orb picked up — so it reads a map or a
 *       field and never a database, a file or the network.</li>
 *   <li><b>{@code 1.0} means "nothing from me".</b> Every provider's answer is
 *       multiplied together, so a provider stacks its own boosts however it
 *       likes (adding, multiplying, taking the highest, capping) and hands
 *       over the one result.</li>
 *   <li><b>Name the type.</b> Answer {@code 1.0} for a type this provider does
 *       not know. A plugin's own type ({@code elo}, {@code clan-exp}) must
 *       never be moved by a provider that was written for money: a source of
 *       {@code *} means every source <em>of a type it names</em>, never every
 *       type.</li>
 *   <li><b>A failure is {@code 1.0}.</b> An exception is caught and counted as
 *       no modifier, so one broken provider cannot stop a payout.</li>
 * </ul>
 *
 * @since 1.263.0
 */
@FunctionalInterface
public interface ModifierProvider {

    /**
     * This provider's multiplier on one payout.
     *
     * @param player who earns it
     * @param type   what is earned, lower case: {@link Modifiers#MONEY}, {@link Modifiers#XP},
     *               {@link Modifiers#DROPS} or a plugin's own
     * @param source what pays it, lower case, such as {@code mines} or {@code shop-sell}
     * @param scope  where inside that source, such as {@code mine:gold}; {@code null} when the
     *               caller names none
     * @return the multiplier, {@code 1.0} when nothing applies; below zero counts as zero
     */
    double factor(@NotNull UUID player, @NotNull String type, @NotNull String source, @Nullable String scope);
}
