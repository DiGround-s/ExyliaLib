package net.exylia.lib.util.crate.internal;

import net.exylia.lib.util.crate.CrateCatalogue;
import net.exylia.lib.util.crate.CrateReward;
import net.exylia.lib.util.crate.CrateSettings;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * One key in, one reward out.
 *
 * <p>Two rolls rather than one: a rarity first, by the weights the owner wrote,
 * over the rarities that have anything in them, and then a reward inside it
 * with every one equally likely. That is what keeps the odds honest — a
 * legendary at three percent stays at three percent whether the catalogue has
 * two legendaries or forty.
 *
 * <p>An opening is two steps, because the reel is not scenery: {@link #draw}
 * spends the key and writes down what it bought, and {@link #award} hands it
 * over as the reel lands — or {@link #awardLater}, for a player who left first.
 *
 * @param <T> the plugin's reward type
 */
public final class Prizes<T> {

    /** How an opening ended. */
    public enum Status {
        /** A key was spent and a prize drawn, with nothing handed over yet. */
        DRAWN,
        /** Something new. */
        WON,
        /** Something they already owned, so keys came back instead. */
        DUPLICATE,
        /** No key to spend. */
        NO_KEYS,
        /** Nothing to hand over: no rarities, or none of them has anything in it. */
        EMPTY,
        /** The owner turned the crate off. */
        DISABLED
    }

    /**
     * What one opening did.
     *
     * @param status   how it ended
     * @param prize    what came out, or {@code null} when nothing did
     * @param tierId   the rarity it came from
     * @param refunded keys handed back for a duplicate
     */
    public record Outcome<T>(@NotNull Status status, @Nullable T prize, @NotNull String tierId, int refunded) {
    }

    /**
     * A key spent and a prize written down, before anything is handed over.
     *
     * @param status why there is no prize, or {@link Status#DRAWN} when there is
     * @param prize  what the key bought, or {@code null}
     */
    public record Draw<T>(@NotNull Status status, @Nullable T prize) {

        public boolean isPrize() {
            return prize != null;
        }
    }

    /** A prize's token item, ready to be handed over. */
    public interface Token {

        /** Hands it to a player who is here. */
        void give(@NotNull Player player);

        /**
         * Keeps it for a player who is leaving, until their next join.
         *
         * @return whether it could be kept
         */
        boolean keep(@NotNull UUID player);
    }

    private final CrateCatalogue<T> catalogue;
    private final CrateStore store;
    private final Supplier<CrateSettings> settings;
    private final BiFunction<T, Player, @Nullable Token> tokens;

    /**
     * @param tokens a prize's token for a player, or {@code null} when the
     *               plugin has none
     */
    public Prizes(@NotNull CrateCatalogue<T> catalogue, @NotNull CrateStore store,
                  @NotNull Supplier<CrateSettings> settings,
                  @NotNull BiFunction<T, Player, @Nullable Token> tokens) {
        this.catalogue = catalogue;
        this.store = store;
        this.settings = settings;
        this.tokens = tokens;
    }

    public @NotNull CrateCatalogue<T> catalogue() {
        return catalogue;
    }

    public @NotNull TierTable tiers() {
        return new TierTable(settings.get().tiers());
    }

    public boolean enabled() {
        return settings.get().enabled();
    }

    /** The rarity a reward resolves to. */
    public @NotNull String tierOf(@NotNull T reward) {
        return tiers().resolveId(catalogue.tier(reward));
    }

    /** A reward's id, as it is stored. */
    public @NotNull String idOf(@NotNull T reward) {
        return TierTable.normalise(catalogue.id(reward));
    }

    /**
     * Spends a key on the player's row and writes down what it bought.
     *
     * <p>Must be called on the thread that owns the player.
     */
    public @NotNull Draw<T> draw(@NotNull Player player) {
        if (!enabled()) return new Draw<>(Status.DISABLED, null);
        T prize = roll();
        // Rolled before the key is taken: a crate with nothing in it must not
        // charge for the disappointment.
        if (prize == null) return new Draw<>(Status.EMPTY, null);
        if (!store.spendKey(player.getUniqueId())) return new Draw<>(Status.NO_KEYS, null);
        return new Draw<>(Status.DRAWN, prize);
    }

    /** Draws for a key that was already paid with: an item taken out of the hand. */
    public @NotNull Draw<T> drawPaid() {
        if (!enabled()) return new Draw<>(Status.DISABLED, null);
        T prize = roll();
        return prize == null ? new Draw<>(Status.EMPTY, null) : new Draw<>(Status.DRAWN, prize);
    }

    /**
     * Hands over a prize that was drawn and paid for.
     *
     * <p>Whether it is a duplicate is decided here rather than at the draw: the
     * unlock is the same write that answers the question, and asking early
     * would let two reels of the same reward both call themselves new.
     */
    public @NotNull Outcome<T> award(@NotNull Player player, @NotNull T prize) {
        return award(player, prize, true);
    }

    /**
     * Hands a prize over to a player on their way out: unlocked on their row,
     * and any token kept in the pending-reward table until their next join.
     */
    public @NotNull Outcome<T> awardLater(@NotNull Player player, @NotNull T prize) {
        return award(player, prize, false);
    }

    private Outcome<T> award(Player player, T prize, boolean present) {
        CrateSettings crate = settings.get();
        CrateReward reward = crate.reward();
        UUID uuid = player.getUniqueId();
        String tier = tierOf(prize);

        Token token = reward.givesItem() ? tokens.apply(prize, player) : null;
        // A plugin with no token for it cannot hand the item over, so the prize
        // is unlocked instead: a key must never buy nothing.
        boolean unlocks = reward.unlocks() || token == null;
        // Nothing is unlocked under ITEM, so every opening is a win: the prize
        // is the token itself, and a player may pull the same one forever.
        boolean isNew = !unlocks || store.unlockNow(uuid, idOf(prize));
        if (isNew) {
            if (token != null) {
                if (present) {
                    token.give(player);
                } else if (!token.keep(uuid)) {
                    // No pending-reward table, or a database that would not take
                    // the row. The token cannot reach somebody who is leaving,
                    // so the key that bought it goes back instead.
                    store.edit(uuid, row -> row.withKeys(row.keys() + 1));
                }
            }
            return new Outcome<>(Status.WON, prize, tier, 0);
        }

        int refund = Math.max(0, crate.duplicateRefund());
        if (refund > 0) store.edit(uuid, row -> row.withKeys(row.keys() + refund));
        return new Outcome<>(Status.DUPLICATE, prize, tier, refund);
    }

    /**
     * Picks a rarity by weight among those with something in them, and then a
     * reward inside it.
     *
     * @return the prize, or {@code null} when there is nothing to land on
     */
    public @Nullable T roll() {
        Map<String, List<T>> pools = pools();
        String tier = tiers().roll(ThreadLocalRandom.current().nextDouble(), pools::containsKey);
        if (tier == null) return null;
        List<T> pool = pools.get(tier);
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Every reward, by the rarity it resolves to; a rarity with nothing in it is absent. */
    public @NotNull Map<String, List<T>> pools() {
        TierTable tiers = tiers();
        Map<String, List<T>> pools = new HashMap<>();
        if (tiers.isEmpty()) return pools;
        for (T reward : catalogue.all()) {
            pools.computeIfAbsent(tiers.resolveId(catalogue.tier(reward)), ignored -> new ArrayList<>()).add(reward);
        }
        return pools;
    }
}
