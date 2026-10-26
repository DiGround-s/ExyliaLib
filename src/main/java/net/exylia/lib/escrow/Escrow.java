package net.exylia.lib.escrow;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.economy.CurrencyKind;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.escrow.internal.ItemRow;
import net.exylia.lib.escrow.internal.OwedRow;
import net.exylia.lib.escrow.internal.StakeRow;
import net.exylia.lib.escrow.internal.StakeState;
import net.exylia.lib.item.internal.ItemBytes;
import net.exylia.lib.proxy.internal.ProxyRuntime;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Money and items held while a match is played, so no restart, crash or quit
 * ever loses or doubles a stake.
 *
 * <pre>{@code
 * private Escrow escrow;
 *
 * @Override
 * public void onEnable() {
 *     escrow = Escrow.of(this);          // refunds what the last run left held
 * }
 *
 * escrow.hold(duelId, player.getUniqueId(), "coins", stake).thenAccept(hold -> {
 *     if (hold != Escrow.Hold.HELD) refuse(player, hold);
 * });
 * escrow.holdItems(duelId, player.getUniqueId(), taken);   // items you already removed
 *
 * escrow.settle(duelId, winner, 0.05);    // the whole pot, less 5%
 * escrow.settleItems(duelId, winner);
 * // or, when nobody won:
 * escrow.refund(duelId);
 * escrow.refundItems(duelId);
 * }</pre>
 *
 * <h2>The order is the whole point</h2>
 * There is no escrow account. Each stake is a row, written before anything moves:
 * <ol>
 *   <li>A money stake is written {@code PENDING}, then charged, then marked
 *       {@code HELD}. A server stopping between the two writes leaves a pending
 *       row, which is never refunded: the charge may not have happened.</li>
 *   <li>Settling or refunding marks the row {@code SETTLED} or {@code REFUNDED}
 *       first, with a compare-and-set on {@code HELD}, and moves the money second.
 *       A second call, a racing call or another server finds nothing still held.</li>
 *   <li>Whatever cannot be handed over now &mdash; an offline player, a refused
 *       deposit, a full inventory &mdash; becomes an owed row, delivered on the
 *       player's next join. It is deleted before it is given, so it is given once.</li>
 * </ol>
 * On start, every row this server left {@code HELD} belongs to a match the
 * restart ended, and is refunded; every row left {@code PENDING} is voided and
 * reported. Holds wait for that recovery, and are refused if it failed.
 *
 * <h2>Threads</h2>
 * Every method may be called from any thread and answers with a future that
 * completes off the server thread. Money and items reach a player on that
 * player's own thread; nothing here touches the database from the main thread.
 *
 * <h2>Reload</h2>
 * Nothing here is derived from the palette, so there is no
 * {@code invalidateAll}.
 *
 * @since 1.345.0
 */
public final class Escrow {

    /** How a money hold went. */
    public enum Hold {
        /** Charged and held. */
        HELD,
        /** The player cannot afford it; nothing moved. */
        CANNOT_PAY,
        /** The economy or the database refused; nothing is held, and anything charged went back. */
        FAILED
    }

    /**
     * What one currency of a settled pot split into.
     *
     * @param currency the currency id
     * @param pot      every stake in it together
     * @param tax      what was kept back, destroyed
     * @param prize    what the winner was paid
     */
    public record Payout(@NotNull String currency, @NotNull BigDecimal pot, @NotNull BigDecimal tax,
                         @NotNull BigDecimal prize) {

        static Payout of(String currency, BigDecimal pot, double fraction, int decimals) {
            BigDecimal tax = pot.multiply(BigDecimal.valueOf(fraction))
                    .setScale(Math.max(0, decimals), RoundingMode.DOWN);
            return new Payout(currency, pot, tax, pot.subtract(tax));
        }
    }

    /**
     * How items become text and reach a player. A seam because an
     * {@code ItemStack} cannot be serialised or given without a running server.
     */
    interface Hands {

        @NotNull String encode(@NotNull List<ItemStack> items);

        @NotNull List<ItemStack> decode(@NotNull String stored);

        /** Gives the items on the player's thread; answers what did not fit. */
        @NotNull List<ItemStack> give(@NotNull Player player, @NotNull List<ItemStack> items);

        /** Writes the player's data to their file, on their thread. */
        default void save(@NotNull Player player) {
        }
    }

    private static final Hands BUKKIT = new Hands() {
        @Override
        public @NotNull String encode(@NotNull List<ItemStack> items) {
            return items.stream()
                    .map(item -> Base64.getEncoder().encodeToString(item.serializeAsBytes()))
                    .collect(Collectors.joining(","));
        }

        @Override
        public @NotNull List<ItemStack> decode(@NotNull String stored) {
            List<ItemStack> items = new ArrayList<>();
            for (String one : stored.split(",")) {
                if (!one.isEmpty()) {
                    // Through ItemBytes: a shared database may hold items from a newer game.
                    items.add(ItemBytes.read(Base64.getDecoder().decode(one)));
                }
            }
            return items;
        }

        @Override
        public @NotNull List<ItemStack> give(@NotNull Player player, @NotNull List<ItemStack> items) {
            return new ArrayList<>(player.getInventory().addItem(items.toArray(new ItemStack[0])).values());
        }

        @Override
        public void save(@NotNull Player player) {
            player.saveData();
        }
    };

    private static volatile Hands hands = BUKKIT;

    /** Test seam: items without a server. */
    static void setHands(@NotNull Hands replacement) {
        hands = replacement;
    }

    /** Test seam: back to Bukkit. */
    static void resetHands() {
        hands = BUKKIT;
    }

    private static final int MAX_ID = 64;
    private static final long JOIN_DELAY_TICKS = 60L;
    private static final long RESPAWN_DELAY_TICKS = 5L;
    private static final Map<String, Escrow> OPEN = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final String name;
    private final String server;
    private final Repository<StakeRow> stakes;
    private final Repository<ItemRow> itemRows;
    private final Repository<OwedRow> owed;
    private final TaskScheduler tasks;
    private final Debug debug;
    /**
     * Matches settled or refunded here lately, so a hold that lands after the
     * end goes straight back instead of waiting for a restart to refund it.
     * An hour covers any hold still in flight.
     */
    private final Cache<String, Boolean> closedMoney = closedCache();
    private final Cache<String, Boolean> closedItems = closedCache();
    private volatile CompletableFuture<Void> recovered = CompletableFuture.completedFuture(null);
    /** Players owed items while dead, handed them on respawn rather than on their next join. */
    private final java.util.Set<UUID> owedWhileDead = ConcurrentHashMap.newKeySet();

    private Escrow(Plugin plugin) {
        this.plugin = plugin;
        this.name = plugin.getName();
        // From the files, not the live bridge: the bridge may connect after this plugin enables,
        // and a name that changes between runs would leave rows no restart recovers.
        this.server = ProxyRuntime.networkServerId().orElse("server-1");
        this.stakes = Databases.of(plugin).repository(StakeRow.class);
        this.itemRows = Databases.of(plugin).repository(ItemRow.class);
        this.owed = Databases.of(plugin).repository(OwedRow.class);
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
    }

    private static Cache<String, Boolean> closedCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();
    }

    /**
     * A plugin's escrow, kept in its database.
     *
     * <p>Call it once, in {@code onEnable}: the first call refunds whatever this
     * server's last run left held, and holds wait until that is done. From then
     * on owed rows are delivered on every join. Released with the plugin.
     *
     * @param plugin the plugin whose {@code database.yml} holds the tables
     * @return the same instance for the same plugin load
     */
    public static @NotNull Escrow of(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (key, open) -> open.plugin == plugin ? open : null);
        Escrow[] created = {null};
        Escrow escrow = OPEN.computeIfAbsent(plugin.getName(), key -> created[0] = new Escrow(plugin));
        if (created[0] != null) {
            escrow.recovered = escrow.recover();
        }
        return escrow;
    }

    /**
     * Completes when the last run's leftovers have been dealt with; fails when
     * they could not be read, in which case every hold is refused until the
     * database is reachable and the plugin restarts.
     */
    public @NotNull CompletableFuture<Void> recovery() {
        return recovered;
    }

    /**
     * Whether holds are taken right now: recovery is done and did not fail.
     *
     * <p>For a screen or a command that answers at once, greying out a stake
     * button instead of waiting on {@link #recovery()}. A hold made while this
     * is {@code false} still waits for recovery, or answers {@code FAILED}.
     *
     * @return {@code true} once the last run's leftovers have been dealt with
     * @since 1.356.0
     */
    public boolean isReady() {
        CompletableFuture<Void> current = recovered;
        return current.isDone() && !current.isCompletedExceptionally();
    }

    // ------------------------------------------------------------------ money

    /**
     * Charges one player's stake for a match and holds it.
     *
     * <p>Only for a player online on this server: an economy may accept a
     * charge to somebody who is not before checking they can pay it. A player
     * may hold several stakes on one match; they all go into the pot.
     *
     * @param matchId  what the stake is for, at most 64 characters
     * @param player   who pays
     * @param currency the currency id; empty for the server's default
     * @param amount   how much, above zero
     * @return how it went; never completes exceptionally
     */
    public @NotNull CompletableFuture<Hold> hold(@NotNull String matchId, @NotNull UUID player,
                                                 @NotNull String currency, @NotNull BigDecimal amount) {
        checkStake(matchId, player, currency, amount);
        return take(matchId, player, currency, amount);
    }

    /**
     * Charges a further stake for a player who already holds one on the match:
     * a double, a split, a raise. Written down before the money moves exactly
     * like the first, and settled or refunded with it.
     *
     * <p>Answers {@code FAILED}, charging nothing, when the player holds no
     * stake on the match &mdash; it never started, or it already ended. A
     * charge still in flight when the match ends goes straight back, as with
     * {@link #hold}.
     *
     * @param matchId  the match, which must already hold a stake of this player
     * @param player   who pays, online on this server
     * @param currency the currency id; empty for the server's default
     * @param amount   how much, above zero
     * @return how it went; never completes exceptionally
     * @since 1.356.0
     */
    public @NotNull CompletableFuture<Hold> holdMore(@NotNull String matchId, @NotNull UUID player,
                                                     @NotNull String currency, @NotNull BigDecimal amount) {
        checkStake(matchId, player, currency, amount);
        if (closedMoney.getIfPresent(matchId) != null) {
            return CompletableFuture.completedFuture(Hold.FAILED);
        }
        return stakes.all().where("plugin", name).where("matchId", matchId).where("player", player)
                .where("state", StakeState.HELD).count()
                .thenCompose(held -> held == 0 ? CompletableFuture.completedFuture(Hold.FAILED)
                        : take(matchId, player, currency, amount))
                .exceptionally(failure -> {
                    debug.error("Escrow: could not hold a further stake of " + player + " on " + matchId + ".",
                            failure);
                    return Hold.FAILED;
                });
    }

    private static void checkStake(String matchId, UUID player, String currency, BigDecimal amount) {
        checkId(matchId);
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A stake must be above zero, and " + amount + " is not.");
        }
    }

    private CompletableFuture<Hold> take(String matchId, UUID player, String currency, BigDecimal amount) {
        return ready().thenCompose(ready -> {
            if (!ready) {
                return CompletableFuture.completedFuture(Hold.FAILED);
            }
            Economy.CurrencyView view = Economy.of(currency);
            // Items and experience are counted only on the player's thread, so off it a balance
            // may read zero; their withdraw checks the funds itself.
            if (!livesOnPlayer(currency) && !view.has(player, amount)) {
                return CompletableFuture.completedFuture(Hold.CANNOT_PAY);
            }
            StakeRow pending = StakeRow.pending(name, server, matchId, player, currency, amount);
            return stakes.save(pending).thenCompose(saved -> charge(pending, view));
        }).exceptionally(failure -> {
            debug.error("Escrow: could not hold the stake of " + player + " on " + matchId + ".", failure);
            return Hold.FAILED;
        });
    }

    private static boolean livesOnPlayer(String currency) {
        CurrencyKind kind = Economy.kind(currency.isBlank() ? Economy.defaultId() : currency);
        return kind == CurrencyKind.ITEM || kind == CurrencyKind.EXPERIENCE;
    }

    private CompletableFuture<Hold> charge(StakeRow pending, Economy.CurrencyView view) {
        // Later, not plain withdraw: this runs on a database callback, and items or experience
        // can only be taken on the player's own thread.
        return view.withdrawLater(pending.player(), pending.amount(),
                Transaction.of("escrow:stake").by(pending.player())).thenCompose(response -> charged(pending, response));
    }

    private CompletableFuture<Hold> charged(StakeRow pending, EconomyResponse response) {
        if (!response.isSuccess()) {
            Hold answer = response.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS ? Hold.CANNOT_PAY : Hold.FAILED;
            // A pending row that fails to become VOID is voided by the next start anyway.
            return stakes.save(pending.with(StakeState.VOID)).handle((ignored, failure) -> answer);
        }
        StakeRow held = pending.with(StakeState.HELD);
        return stakes.save(held).handle((ignored, failure) -> {
            if (failure != null) {
                // The row still reads PENDING, which a restart voids and never refunds: give it back now.
                debug.error("Escrow: could not record the stake of " + held.player() + " as held; refunding it.",
                        failure);
                pay(held.player(), held.currency(), held.amount(), "escrow:refund");
                return CompletableFuture.completedFuture(Hold.FAILED);
            }
            if (closedMoney.getIfPresent(held.matchId()) != null) {
                // The match ended while this charge was in flight.
                return giveBack(held).thenApply(done -> Hold.FAILED);
            }
            return CompletableFuture.completedFuture(Hold.HELD);
        }).thenCompose(answer -> answer);
    }

    /** {@link #settle(String, UUID, double)} with no tax. */
    public @NotNull CompletableFuture<List<Payout>> settle(@NotNull String matchId, @NotNull UUID winner) {
        return settle(matchId, winner, 0);
    }

    /**
     * Pays every stake still held on a match to its winner.
     *
     * <p>Each stake is marked settled before any money moves; a second call
     * finds nothing and pays nothing. A winner who is offline, or whose
     * deposit is refused, is paid on their next join.
     *
     * @param matchId the match
     * @param winner  who takes the pot
     * @param tax     the fraction kept back, {@code 0..1}; rounded down to the
     *                currency's decimals, so rounding favours the winner
     * @return one payout per currency in the pot; empty when nothing was held
     */
    public @NotNull CompletableFuture<List<Payout>> settle(@NotNull String matchId, @NotNull UUID winner,
                                                           double tax) {
        checkId(matchId);
        Objects.requireNonNull(winner, "winner");
        double fraction = Double.isNaN(tax) ? 0 : Math.max(0, Math.min(1, tax));
        closedMoney.put(matchId, Boolean.TRUE);
        return claim(matchId, StakeState.SETTLED).thenApply(won -> {
            Map<String, BigDecimal> pots = new LinkedHashMap<>();
            for (StakeRow row : won) {
                pots.merge(row.currency(), row.amount(), BigDecimal::add);
            }
            List<Payout> payouts = new ArrayList<>(pots.size());
            pots.forEach((currency, pot) -> {
                Payout payout = Payout.of(currency, pot, fraction, Economy.info(currency).decimals());
                payouts.add(payout);
                if (payout.prize().signum() > 0) {
                    pay(winner, currency, payout.prize(), "escrow:payout");
                }
            });
            return payouts;
        });
    }

    /** {@link #settleAmount(String, UUID, BigDecimal, int)} counting every stake held. */
    public @NotNull CompletableFuture<Boolean> settleAmount(@NotNull String matchId, @NotNull UUID player,
                                                            @NotNull BigDecimal amount) {
        return settleAmount(matchId, player, amount, Integer.MAX_VALUE);
    }

    /**
     * Ends a match by paying one player an amount the game worked out, rather
     * than the pot: what a round played against the server's rules is now
     * worth, nothing when it was lost.
     *
     * <pre>{@code
     * escrow.settleAmount(roundId, player, stake.multiply(BigDecimal.TWO), hands);   // won at 2x
     * escrow.settleAmount(roundId, player, BigDecimal.ZERO);                         // lost
     * }</pre>
     *
     * <p>The first {@code counted} stakes held on the match, oldest first, are
     * settled; any held after them &mdash; an extra charged while the player
     * was leaving, which the game never used &mdash; goes back to whoever paid
     * it. The amount is paid in the currency of the oldest stake, rounded down
     * to its decimals, and paid once: only the call that settles the oldest
     * stake pays, so a second call, a racing {@link #refund} or another server
     * pays nothing. A player who is offline, or whose deposit is refused, is
     * paid on their next join.
     *
     * @param matchId the match
     * @param player  who is paid
     * @param amount  what they are paid, zero or above
     * @param counted how many of the held stakes the game used, from 1
     * @return whether this call settled the match; {@code false} when nothing
     *         was held or another call got there first
     * @since 1.356.0
     */
    public @NotNull CompletableFuture<Boolean> settleAmount(@NotNull String matchId, @NotNull UUID player,
                                                            @NotNull BigDecimal amount, int counted) {
        checkId(matchId);
        Objects.requireNonNull(player, "player");
        if (amount.signum() < 0 || counted < 1) {
            throw new IllegalArgumentException("An amount is zero or above and counted is 1 or more, and "
                    + amount + " / " + counted + " are not.");
        }
        closedMoney.put(matchId, Boolean.TRUE);
        return stakes.all().where("plugin", name).where("matchId", matchId).where("state", StakeState.HELD)
                .find().thenCompose(rows -> {
                    if (rows.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    List<StakeRow> ordered = new ArrayList<>(rows);
                    ordered.sort(java.util.Comparator.comparingLong(StakeRow::updatedAt).thenComparing(StakeRow::id));
                    List<StakeRow> used = ordered.subList(0, Math.min(counted, ordered.size()));
                    List<StakeRow> extra = ordered.subList(used.size(), ordered.size());
                    StakeRow oldest = used.getFirst();
                    // The oldest stake first: whoever moves it owns the match, and a call that loses
                    // it touches nothing else, so the amount is paid once.
                    return stakes.updateIf(oldest.with(StakeState.SETTLED), "state", StakeState.HELD)
                            .thenCompose(won -> {
                                if (!won) {
                                    return CompletableFuture.completedFuture(false);
                                }
                                extra.forEach(this::giveBack);
                                return each(used.subList(1, used.size()),
                                        row -> stakes.updateIf(row.with(StakeState.SETTLED), "state", StakeState.HELD))
                                        .thenApply(rest -> {
                                            BigDecimal paid = amount.setScale(
                                                    Math.max(0, Economy.info(oldest.currency()).decimals()),
                                                    RoundingMode.DOWN);
                                            if (paid.signum() > 0) {
                                                pay(player, oldest.currency(), paid, "escrow:payout");
                                            }
                                            return true;
                                        });
                            });
                });
    }

    /**
     * Gives every stake still held on a match back to whoever paid it. A
     * second call, or one after {@link #settle}, does nothing.
     *
     * @param matchId the match
     * @return completes when every refund has been recorded and dispatched
     */
    public @NotNull CompletableFuture<Void> refund(@NotNull String matchId) {
        checkId(matchId);
        closedMoney.put(matchId, Boolean.TRUE);
        return claim(matchId, StakeState.REFUNDED).thenAccept(won ->
                won.forEach(row -> pay(row.player(), row.currency(), row.amount(), "escrow:refund")));
    }

    /** Moves every held stake of a match to {@code next}; answers only the rows this call moved. */
    private CompletableFuture<List<StakeRow>> claim(String matchId, StakeState next) {
        return stakes.all().where("plugin", name).where("matchId", matchId).where("state", StakeState.HELD)
                .find().thenCompose(rows -> each(rows, row -> stakes.updateIf(row.with(next), "state", StakeState.HELD)));
    }

    private CompletableFuture<Void> giveBack(StakeRow row) {
        return stakes.updateIf(row.with(StakeState.REFUNDED), "state", StakeState.HELD).handle((won, failure) -> {
            if (failure != null) {
                debug.error("Escrow: could not record the refund of stake " + row.id()
                        + "; it is refunded on the next start.", failure);
            } else if (won) {
                pay(row.player(), row.currency(), row.amount(), "escrow:refund");
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ items

    /**
     * Records items already taken from a player as their stake on a match.
     *
     * <p>Take the items first, then call this: until the future completes they
     * exist only in memory. Once the row has landed, the player's data is
     * written to their file (when they are online here, on their thread;
     * since 1.350.0), so a crash before the next autosave cannot load them
     * still holding items the escrow also refunds. When it answers {@code false} &mdash; the database
     * refused, recovery failed, or the match already ended &mdash; the items
     * have been given back to the player (or owed to them).
     *
     * @param matchId what they are staked on, at most 64 characters
     * @param player  whose they are
     * @param items   the items; air and nulls are skipped, and each is copied
     * @return whether they are held; never completes exceptionally
     */
    public @NotNull CompletableFuture<Boolean> holdItems(@NotNull String matchId, @NotNull UUID player,
                                                         @NotNull List<ItemStack> items) {
        checkId(matchId);
        Objects.requireNonNull(player, "player");
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                copy.add(item.clone());
            }
        }
        if (copy.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        String encoded = hands.encode(copy);
        return ready().thenCompose(ready -> {
            if (!ready) {
                give(player, encoded, "escrow:refund");
                return CompletableFuture.completedFuture(false);
            }
            ItemRow row = ItemRow.held(name, server, matchId, player, encoded);
            return itemRows.save(row).handle((ignored, failure) -> {
                if (failure != null) {
                    debug.error("Escrow: could not record the items of " + player + " on " + matchId
                            + "; giving them back.", failure);
                    give(player, encoded, "escrow:refund");
                    return CompletableFuture.completedFuture(false);
                }
                if (closedItems.getIfPresent(matchId) != null) {
                    return giveBackItems(row).thenApply(done -> false);
                }
                saveData(player);
                return CompletableFuture.completedFuture(true);
            }).thenCompose(answer -> answer);
        });
    }

    /**
     * Hands every item still held on a match to its winner: into their
     * inventory, and whatever does not fit &mdash; or all of it, when they are
     * offline &mdash; on their next join. Nothing is ever dropped on the ground.
     * A second call does nothing.
     *
     * @param matchId the match
     * @param winner  who takes the items
     * @return completes when the items have been recorded as settled and dispatched
     */
    public @NotNull CompletableFuture<Void> settleItems(@NotNull String matchId, @NotNull UUID winner) {
        return settleItems(matchId, winner, 1);
    }

    /**
     * Hands every item still held on a match to its winner {@code multiplier}
     * times over: a stake won at 3x is paid back three times, one lost at 0x
     * is settled and nothing is given. Delivered as {@link #settleItems(String, UUID)}
     * delivers, and a second call does nothing.
     *
     * @param matchId    the match
     * @param winner     who takes the items
     * @param multiplier how many times over, {@code 0} or above
     * @return completes when the items have been recorded as settled and dispatched
     * @since 1.356.0
     */
    public @NotNull CompletableFuture<Void> settleItems(@NotNull String matchId, @NotNull UUID winner,
                                                        int multiplier) {
        checkId(matchId);
        Objects.requireNonNull(winner, "winner");
        if (multiplier < 0) {
            throw new IllegalArgumentException("A multiplier is 0 or above, and " + multiplier + " is not.");
        }
        closedItems.put(matchId, Boolean.TRUE);
        return claimItems(matchId, StakeState.SETTLED).thenAccept(won -> {
            String all = won.stream().map(ItemRow::items).filter(items -> !items.isEmpty())
                    .collect(Collectors.joining(","));
            if (!all.isEmpty() && multiplier > 0) {
                // ponytail: the encoded stacks repeated, which the inventory merges; grows with the multiplier,
                // so scale each stack's amount instead if multipliers ever reach the hundreds.
                give(winner, String.join(",", java.util.Collections.nCopies(multiplier, all)), "escrow:payout");
            }
        });
    }

    /**
     * Gives every player their own items still held on a match. A second
     * call, or one after {@link #settleItems}, does nothing.
     *
     * @param matchId the match
     * @return completes when every refund has been recorded and dispatched
     */
    public @NotNull CompletableFuture<Void> refundItems(@NotNull String matchId) {
        checkId(matchId);
        closedItems.put(matchId, Boolean.TRUE);
        return claimItems(matchId, StakeState.REFUNDED).thenAccept(won ->
                won.forEach(row -> give(row.player(), row.items(), "escrow:refund")));
    }

    /**
     * The matches a player still has items held on, on any server sharing the database.
     *
     * <p>For a join handler that must know whether the player's inventory is in
     * the escrow before restoring anything: a risk duel interrupted by a crash
     * after the loser's items were held, say.
     *
     * @param player the player
     * @return the match ids, empty when nothing of theirs is held
     * @since 1.348.0
     */
    public @NotNull CompletableFuture<Set<String>> heldItemMatches(@NotNull UUID player) {
        // ponytail: filtered by (plugin, player, state) without an index on player; add one if a table grows large.
        return itemRows.all().where("plugin", name).where("player", player).where("state", StakeState.HELD)
                .find().thenApply(rows -> rows.stream().map(ItemRow::matchId)
                        .collect(Collectors.toCollection(java.util.TreeSet::new)));
    }

    private CompletableFuture<List<ItemRow>> claimItems(String matchId, StakeState next) {
        return itemRows.all().where("plugin", name).where("matchId", matchId).where("state", StakeState.HELD)
                .find().thenCompose(rows ->
                        each(rows, row -> itemRows.updateIf(row.with(next), "state", StakeState.HELD)));
    }

    private CompletableFuture<Void> giveBackItems(ItemRow row) {
        return itemRows.updateIf(row.with(StakeState.REFUNDED), "state", StakeState.HELD).handle((won, failure) -> {
            if (failure != null) {
                debug.error("Escrow: could not record the refund of items " + row.id()
                        + "; they are refunded on the next start.", failure);
            } else if (won) {
                give(row.player(), row.items(), "escrow:refund");
            }
            return null;
        });
    }

    /**
     * Gives items to a player that belong to nobody's match: into their
     * inventory on their own thread, and whatever does not fit &mdash; or all
     * of it, when they are offline or dead &mdash; on their next join or
     * respawn. Nothing is ever dropped on the ground.
     *
     * <p>For items a caller already took from somewhere and must not lose: a
     * cancelled trade, the two sides of a plain trade swapped over.
     *
     * @param player who receives them
     * @param items  the items; air and nulls are skipped, and each is copied
     * @since 1.349.0
     */
    public void giveItems(@NotNull UUID player, @NotNull List<ItemStack> items) {
        Objects.requireNonNull(player, "player");
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                copy.add(item.clone());
            }
        }
        if (!copy.isEmpty()) {
            give(player, hands.encode(copy), "escrow:give");
        }
    }

    // --------------------------------------------------------------- delivery

    /**
     * Delivers everything owed to a player: refused deposits, payouts while
     * offline, items that did not fit. The library calls it a few seconds after
     * every join; call it yourself after a player frees inventory space.
     *
     * <p>Each owed row is deleted before it is given, so two deliveries racing
     * &mdash; two joins, two servers &mdash; hand it over once. Whatever still
     * cannot be delivered is written back as a new owed row.
     *
     * @param player the player, online on this server
     * @return completes when every owed row has been taken and dispatched
     */
    public @NotNull CompletableFuture<Void> deliver(@NotNull Player player) {
        UUID id = player.getUniqueId();
        owedWhileDead.remove(id);
        return owed.all().where("plugin", name).where("player", id).find()
                .thenCompose(rows -> each(rows, row -> owed.delete(row.id())))
                .thenAccept(taken -> taken.forEach(row -> {
                    if (row.isItems()) {
                        give(id, row.items(), row.reason());
                    } else {
                        pay(id, row.currency(), row.amount(), row.reason());
                    }
                }))
                .exceptionally(failure -> {
                    debug.error("Escrow: could not read what is owed to " + id + ".", failure);
                    return null;
                });
    }

    /** Pays a player on their own thread, or owes it when they are not here or the economy refuses. */
    private void pay(UUID player, String currency, BigDecimal amount, String reason) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            owe(OwedRow.money(name, player, currency, amount, reason));
            return;
        }
        tasks.runAtEntity(online, () -> {
            EconomyResponse response;
            try {
                response = Economy.of(currency).deposit(player, amount, Transaction.of(reason));
            } catch (RuntimeException failure) {
                debug.error("Escrow: the economy failed to pay " + player + ".", failure);
                response = EconomyResponse.failure("exception");
            }
            if (!response.isSuccess()) {
                debug.warn("Escrow: could not pay " + amount + " " + currency + " to " + player + " ("
                        + response.type() + "); it is paid on their next join.");
                owe(OwedRow.money(name, player, currency, amount, reason));
            }
        }, () -> owe(OwedRow.money(name, player, currency, amount, reason)));
    }

    /** Gives items on the player's own thread; what does not fit, or all of it when they are not here, is owed. */
    private void give(UUID player, String items, String reason) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            owe(OwedRow.items(name, player, items, reason));
            return;
        }
        tasks.runAtEntity(online, () -> {
            if (online.isDead()) {
                // A dead player's inventory is cleared on respawn: owed, and handed over then.
                owe(OwedRow.items(name, player, items, reason));
                owedWhileDead.add(player);
                return;
            }
            List<ItemStack> left;
            try {
                left = hands.give(online, hands.decode(items));
            } catch (RuntimeException failure) {
                debug.error("Escrow: could not give items to " + player + "; they stay owed.", failure);
                owe(OwedRow.items(name, player, items, reason));
                return;
            }
            if (!left.isEmpty()) {
                owe(OwedRow.items(name, player, hands.encode(left), reason));
            }
            // The row that held these is already settled, refunded or deleted:
            // a crash before the next autosave would load the player without them.
            save(online);
        }, () -> owe(OwedRow.items(name, player, items, reason)));
    }

    /**
     * Writes an online player's data to their file on their thread, so the file
     * agrees with a row that just took items from them or gave items to them.
     */
    private void saveData(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            tasks.runAtEntity(online, () -> save(online), () -> { });
        }
    }

    private void save(Player player) {
        if (!player.isOnline()) {
            return;
        }
        try {
            hands.save(player);
        } catch (RuntimeException failure) {
            debug.error("Escrow: could not save " + player.getName() + "'s data.", failure);
        }
    }

    private void owe(OwedRow row) {
        owed.save(row).exceptionally(failure -> {
            debug.error("Escrow: could not record " + (row.isItems() ? "items" : row.amount() + " " + row.currency())
                    + " owed to " + row.player() + " (" + row.reason() + "). This is lost; " + row, failure);
            return null;
        });
    }

    // --------------------------------------------------------------- recovery

    private CompletableFuture<Void> recover() {
        CompletableFuture<Void> money = stakes.all().where("plugin", name).where("server", server)
                .where("state", StakeState.HELD).find().thenCompose(rows -> {
                    rows.forEach(row -> debug.warn("Escrow: refunding " + row.amount() + " " + row.currency()
                            + " to " + row.player() + ": match " + row.matchId() + " was cut short by a restart."));
                    return CompletableFuture.allOf(rows.stream().map(this::giveBack)
                            .toArray(CompletableFuture[]::new));
                });
        CompletableFuture<Void> pending = stakes.all().where("plugin", name).where("server", server)
                .where("state", StakeState.PENDING).find().thenCompose(rows -> {
                    rows.forEach(row -> debug.warn("Escrow: a stake of " + row.amount() + " " + row.currency()
                            + " by " + row.player() + " was interrupted mid-charge (match " + row.matchId()
                            + "). It is not refunded automatically; check the economy history if they report it."));
                    return CompletableFuture.allOf(rows.stream()
                            .map(row -> stakes.updateIf(row.with(StakeState.VOID), "state", StakeState.PENDING)
                                    .exceptionally(failure -> false))
                            .toArray(CompletableFuture[]::new));
                });
        CompletableFuture<Void> items = itemRows.all().where("plugin", name).where("server", server)
                .where("state", StakeState.HELD).find().thenCompose(rows -> {
                    rows.forEach(row -> debug.warn("Escrow: giving back the items " + row.player()
                            + " staked on match " + row.matchId() + ", cut short by a restart."));
                    return CompletableFuture.allOf(rows.stream().map(this::giveBackItems)
                            .toArray(CompletableFuture[]::new));
                });
        return CompletableFuture.allOf(money, pending, items).whenComplete((ignored, failure) -> {
            if (failure != null) {
                // Stay closed: taking new stakes while old ones may still be owed is how money doubles.
                debug.error("Escrow: could not read the stakes left by the last run; every hold is refused"
                        + " until the database is reachable and the plugin is restarted.", failure);
            }
        });
    }

    private CompletableFuture<Boolean> ready() {
        return recovered.handle((ignored, failure) -> failure == null);
    }

    // ---------------------------------------------------------------- helpers

    /** Runs a compare-and-set per row and answers the rows whose write this call won. */
    private <R> CompletableFuture<List<R>> each(List<R> rows, Function<R, CompletableFuture<Boolean>> claim) {
        List<CompletableFuture<R>> claims = new ArrayList<>(rows.size());
        for (R row : rows) {
            claims.add(claim.apply(row).handle((won, failure) -> {
                if (failure != null) {
                    debug.error("Escrow: could not move " + row + "; it is left as it was.", failure);
                    return null;
                }
                return Boolean.TRUE.equals(won) ? row : null;
            }));
        }
        return CompletableFuture.allOf(claims.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<R> won = new ArrayList<>(claims.size());
            for (CompletableFuture<R> one : claims) {
                R row = one.join();
                if (row != null) {
                    won.add(row);
                }
            }
            return won;
        });
    }

    private static void checkId(String matchId) {
        if (matchId == null || matchId.isBlank() || matchId.length() > MAX_ID) {
            throw new IllegalArgumentException("A match id must be 1 to " + MAX_ID + " characters, and '"
                    + matchId + "' is not.");
        }
    }

    // -------------------------------------------------------------- lifecycle

    /** Delivers what every plugin owes a player who just joined, after the join settles. Called by the library. */
    public static void joined(@NotNull Player player) {
        for (Escrow escrow : OPEN.values()) {
            escrow.tasks.runAtEntityLater(player, JOIN_DELAY_TICKS, () -> escrow.deliver(player));
        }
    }

    /** Delivers what a player was owed while dead, once they are back. Called by the library. */
    public static void respawned(@NotNull Player player) {
        for (Escrow escrow : OPEN.values()) {
            if (escrow.owedWhileDead.remove(player.getUniqueId())) {
                escrow.tasks.runAtEntityLater(player, RESPAWN_DELAY_TICKS, () -> escrow.deliver(player));
            }
        }
    }

    /**
     * Forgets a plugin's escrow when it is disabled &mdash; this load only.
     * Nothing is written: every stake was a row the moment it was taken.
     * Called by the library.
     */
    public static void release(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (key, open) -> open.plugin == plugin ? null : open);
    }

    /** Forgets every plugin, on shutdown. Called by the library. */
    public static void releaseAll() {
        OPEN.clear();
    }
}
