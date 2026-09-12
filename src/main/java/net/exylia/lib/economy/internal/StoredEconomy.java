package net.exylia.lib.economy.internal;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.LedgerEntry;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The runtime behind every currency the library provides itself.
 *
 * <p>Reads {@code currencies.yml}, registers a {@link StoredCurrency} per
 * block (and the item and experience currencies), loads a player's balances
 * when they join and lets go when they leave, writes the snapshot and the
 * ledger, folds in what other servers queued, and answers leaderboards and
 * history.
 *
 * <h2>Cross-server</h2>
 * Ownership, not synchronisation. The server a player is on is the only one
 * that writes their snapshot. Anybody else queues a {@link PendingRow} and
 * publishes the player's id on the {@code economy} channel; the owner, if
 * there is one, claims the rows at once, and otherwise they wait for the next
 * join. Each row is taken by deleting it, so a change lands exactly once no
 * matter how many servers heard about it.
 */
public final class StoredEconomy implements Listener {

    private static final String CHANNEL = "economy";
    private static final long TOP_CACHE_MILLIS = 60_000L;

    private static volatile StoredEconomy instance;

    private final Plugin plugin;
    private final Logger logger;
    private final Debug debug;
    private final TaskScheduler tasks;
    private final Repository<BalanceRow> balances;
    private final Repository<PendingRow> pending;
    private final Repository<LedgerRow> ledger;
    private final String server;

    private volatile CurrencyFile.Contents contents;
    private final Map<String, StoredCurrency> stored = new LinkedHashMap<>();
    private final List<CurrencyProvider> extras = new ArrayList<>();
    private final Map<String, CachedTop> tops = new ConcurrentHashMap<>();
    /** The names of the players held here, stamped on every row written. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    /**
     * One queue of writes per player.
     *
     * <p>Every write is asynchronous and the pool has more than one
     * connection, so two writes for one player can land in either order — and
     * a snapshot of 100 landing after the snapshot of 70 that followed it is a
     * balance that grew on its own. Chained, they land in the order they were
     * made, and the ledger reads in the order things happened.
     */
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    private final VaultBridge vault;
    private volatile boolean ready;
    private Channel channel;
    private Channel.Subscription subscription;
    private TaskHandle sweeper;

    private record CachedTop(long at, List<Economy.TopEntry> entries) { }

    private StoredEconomy(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.debug = Debug.of(plugin);
        this.tasks = Tasks.of(plugin);
        this.balances = Databases.of(plugin).repository(BalanceRow.class);
        this.pending = Databases.of(plugin).repository(PendingRow.class);
        this.ledger = Databases.of(plugin).repository(LedgerRow.class);
        this.server = Redis.serverId(plugin);
        this.vault = new VaultBridge(plugin);
    }

    // ------------------------------------------------------------ lifecycle

    /** Called once by ExyliaLib after the database is up. */
    public static void init(@NotNull Plugin plugin) {
        StoredEconomy economy = new StoredEconomy(plugin);
        instance = economy;
        economy.load();
        Bukkit.getPluginManager().registerEvents(economy, plugin);
        economy.channel = Channels.of(plugin).channel(CHANNEL);
        economy.subscription = economy.channel.subscribe(message -> {
            if (message.local()) return;
            economy.wake(message.payload());
        });
        // Pending rows for players already here — a reload — and a periodic
        // sweep for the message that never arrived.
        economy.sweeper = economy.tasks.runAsyncTimer(200L, 1200L, economy::sweep);
        for (Player online : Bukkit.getOnlinePlayers()) {
            economy.load(online);
        }
    }

    /** Re-reads {@code currencies.yml}: {@code /exylialib reload}. */
    public static void reload() {
        StoredEconomy economy = instance;
        if (economy != null) economy.load();
    }

    /** Called by ExyliaLib as it disables, before the database goes. */
    public static void shutdown() {
        StoredEconomy economy = instance;
        if (economy == null) return;
        instance = null;
        economy.ready = false;
        if (economy.sweeper != null) economy.sweeper.cancel();
        if (economy.subscription != null) economy.subscription.close();
        economy.vault.unpublish();
        economy.unregisterAll();
    }

    static @Nullable StoredEconomy get() {
        return instance;
    }

    boolean isReady() {
        return ready;
    }

    private void load() {
        CurrencyFile.Contents read = CurrencyFile.load(plugin, logger);
        contents = read;
        CurrencyRegistry.overlays(read.display());
        unregisterAll();

        for (CurrencyFile.Stored settings : read.stored().values()) {
            StoredCurrency currency = new StoredCurrency(settings, this);
            stored.put(settings.id(), currency);
            register(currency);
        }
        if (read.experienceLevels()) register(new ExperienceCurrency(true));
        if (read.experiencePoints()) register(new ExperienceCurrency(false));
        for (CurrencyFile.Item item : read.items().values()) {
            register(new ItemCurrency(item, plugin));
        }
        ready = true;

        for (Player online : Bukkit.getOnlinePlayers()) load(online);
        // Neither exists everywhere the library runs — a test, a tool — and
        // neither is worth a currency that fails to load.
        safely("register the currency commands", () -> AliasCommands.install(plugin, this));
        safely("publish the Vault economy", () -> vault.publish(
                read.vaultProvide().isBlank() ? null : stored.get(read.vaultProvide().toLowerCase(Locale.ROOT)),
                read.vaultForce()));
        logger.info("Economy: " + stored.size() + " stored, " + read.items().size() + " item and "
                + ((read.experienceLevels() ? 1 : 0) + (read.experiencePoints() ? 1 : 0))
                + " experience currencies from currencies.yml.");
    }

    private void register(CurrencyProvider provider) {
        try {
            CurrencyRegistry.register(provider);
            if (!(provider instanceof StoredCurrency)) extras.add(provider);
        } catch (RuntimeException taken) {
            logger.warning("Economy: currencies.yml names '" + provider.id() + "' but " + taken.getMessage());
        }
    }

    private void unregisterAll() {
        for (StoredCurrency currency : stored.values()) CurrencyRegistry.unregister(currency.id());
        for (CurrencyProvider extra : extras) CurrencyRegistry.unregister(extra.id());
        stored.clear();
        extras.clear();
        tops.clear();
    }

    /** Every stored currency, in file order. */
    public @NotNull Collection<StoredCurrency> currencies() {
        return stored.values();
    }

    /** The item and experience currencies, for the alias commands. */
    public @NotNull List<CurrencyProvider> extras() {
        return extras;
    }

    public @NotNull CurrencyFile.Contents contents() {
        return contents;
    }

    public @Nullable StoredCurrency currency(@NotNull String id) {
        return stored.get(id.toLowerCase(Locale.ROOT));
    }

    // --------------------------------------------------------------- players

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        for (StoredCurrency currency : stored.values()) currency.unload(player);
        names.remove(player);
        // The chain is dropped once it is drained, not before: a write still
        // in flight for a player who just left is the write that matters most.
        CompletableFuture<Void> tail = chains.get(player);
        if (tail != null) tail.whenComplete((ignored, failure) -> chains.remove(player, tail));
        BalanceCache.invalidateAll();
    }

    /**
     * Takes ownership of a player's balances.
     *
     * <p>The snapshot is read, whatever other servers queued is claimed row by
     * row and folded in, and the result is written back. Until the read lands
     * the player's balance reads as the snapshot the cache last saw or zero,
     * and a write in that window is queued like any other server's: nothing
     * is lost, it is simply applied a moment later.
     */
    private void load(Player player) {
        load(player.getUniqueId(), player.getName());
    }

    /** The same, for a player named by id: what a test and a reload call. */
    void load(UUID id, String name) {
        if (name != null && !name.isBlank()) names.put(id, name);
        for (StoredCurrency currency : stored.values()) {
            if (currency.isLoaded(id)) continue;
            balances.find(BalanceRow.id(id, currency.id())).thenAccept(found -> {
                BigDecimal start = found.map(BalanceRow::amount).orElse(currency.settings().start());
                currency.load(id, start);
                if (found.isEmpty()) {
                    balances.save(new BalanceRow(id, name, currency.id(), currency.clamp(start)));
                } else if (!name.equals(found.get().name())) {
                    balances.save(new BalanceRow(id, name, currency.id(), found.get().amount()));
                }
                BalanceCache.invalidate(currency.id(), id);
                claimPending(id, currency);
            });
        }
    }

    /** Folds in what other servers queued for a player held here. */
    private void claimPending(UUID player, StoredCurrency currency) {
        pending.where("player", player.toString()).where("currency", currency.id())
                .orderBy("created_at").find().thenAccept(rows -> {
                    for (PendingRow row : rows) {
                        pending.delete(row.id()).thenAccept(taken -> {
                            if (!Boolean.TRUE.equals(taken)) return;
                            if (!currency.isLoaded(player)) {
                                // They left between the read and the claim. The row is
                                // ours now, so it goes back in the queue rather than away.
                                pending.insert(new PendingRow(player, currency.id(), row.amount(),
                                        row.absolute(), row.reason(),
                                        row.initiator() == null ? null : UUID.fromString(row.initiator())));
                                return;
                            }
                            currency.applyPending(player, row);
                        });
                    }
                });
    }

    /** A message from another server: somebody's balance has something waiting. */
    private void wake(String payload) {
        UUID player;
        try {
            player = UUID.fromString(payload.trim());
        } catch (IllegalArgumentException notAnId) {
            return;
        }
        for (StoredCurrency currency : stored.values()) {
            if (currency.isLoaded(player)) claimPending(player, currency);
        }
    }

    /** Every so often: what the network did not tell us about. */
    private void sweep() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            for (StoredCurrency currency : stored.values()) {
                if (currency.isLoaded(online.getUniqueId())) claimPending(online.getUniqueId(), currency);
            }
        }
    }

    // ---------------------------------------------------------------- writes

    /** A balance changed in memory: write it down, after whatever came before. */
    void written(StoredCurrency currency, UUID player, BigDecimal after, BigDecimal moved,
                 Transaction transaction) {
        String held = names.get(player);
        String name = held != null ? held : ExyliaPlayers.nameOr(player, "");
        BalanceRow row = new BalanceRow(player, name, currency.id(), after);
        LedgerRow line = contents.ledger() && moved.signum() != 0
                ? new LedgerRow(player, currency.id(), moved, after, transaction.reason(),
                        transaction.initiator(), server)
                : null;
        tops.remove(currency.id());
        chain(player, () -> {
            CompletableFuture<Void> saved = balances.save(row);
            return line == null ? saved : saved.thenCompose(ignored -> ledger.insert(line)).thenApply(id -> null);
        });
    }

    /** Runs a write once the player's earlier writes have landed. */
    private void chain(UUID player, java.util.function.Supplier<CompletableFuture<Void>> write) {
        chains.compute(player, (id, previous) -> {
            CompletableFuture<Void> start = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return start.handle((ignored, failure) -> null).thenCompose(ignored -> write.get())
                    .exceptionally(failure -> {
                        debug.error("Could not write a balance of " + id, failure);
                        return null;
                    });
        });
    }

    /** A change for somebody this server does not hold: queue it and say so. */
    void queue(StoredCurrency currency, UUID player, BigDecimal amount, boolean absolute,
               Transaction transaction) {
        pending.insert(new PendingRow(player, currency.id(), amount, absolute, transaction.reason(),
                transaction.initiator())).thenAccept(id -> {
            if (channel != null) channel.publish(player.toString());
        });
    }

    /**
     * The last snapshot of a player who is not held here.
     *
     * <p>Answered from the balance cache when it has one, and warmed from the
     * database otherwise: the first read of an absent player is zero and the
     * second is right. Reading the database in line here would block whatever
     * thread asked, which is usually the one running the game.
     */
    BigDecimal snapshot(String currency, UUID player) {
        BigDecimal cached = BalanceCache.peek(currency, player);
        if (cached != null) return cached;
        balances.find(BalanceRow.id(player, currency)).thenAccept(found ->
                BalanceCache.remember(currency, player, found.map(BalanceRow::amount).orElse(BigDecimal.ZERO)));
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------- questions

    public static @NotNull CompletableFuture<List<LedgerEntry>> history(String id, UUID player, int limit) {
        StoredEconomy economy = instance;
        if (economy == null || economy.currency(id) == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return economy.ledger.where("player", player.toString()).where("currency", id.toLowerCase(Locale.ROOT))
                // By key, not by time: two lines written in the same millisecond
                // have one order in the table and none by their timestamp.
                .orderByDescending("id").limit(Math.max(1, Math.min(200, limit))).find()
                .thenApply(rows -> rows.stream().map(LedgerRow::entry).toList());
    }

    public static @NotNull List<Economy.TopEntry> top(String id, int limit) {
        StoredEconomy economy = instance;
        if (economy == null) return List.of();
        StoredCurrency currency = economy.currency(id);
        if (currency == null || !currency.settings().leaderboard()) return List.of();
        String key = currency.id();
        CachedTop cached = economy.tops.get(key);
        long now = System.currentTimeMillis();
        if (cached == null || now - cached.at() > TOP_CACHE_MILLIS) {
            // Refreshed in the background; whoever asked gets what was there.
            economy.tops.put(key, new CachedTop(now, cached == null ? List.of() : cached.entries()));
            economy.balances.where("currency", key).orderByDescending("amount").limit(100).find()
                    .thenAccept(rows -> {
                        List<Economy.TopEntry> entries = new ArrayList<>(rows.size());
                        int position = 1;
                        for (BalanceRow row : rows) {
                            entries.add(new Economy.TopEntry(position++, row.uuid(),
                                    ExyliaPlayers.nameOr(row.uuid(), row.name()), row.amount()));
                        }
                        economy.tops.put(key, new CachedTop(System.currentTimeMillis(), entries));
                    });
            if (cached == null) return List.of();
        }
        List<Economy.TopEntry> entries = cached == null ? List.of() : cached.entries();
        return entries.subList(0, Math.min(Math.max(0, limit), entries.size()));
    }

    public static @NotNull EconomyResponse exchange(UUID player, String fromId, String toId, BigDecimal amount) {
        StoredEconomy economy = instance;
        if (economy == null) return EconomyResponse.notAvailable();
        StoredCurrency from = economy.currency(fromId);
        if (from == null || !from.settings().exchangeable()) {
            return EconomyResponse.failure("That currency cannot be exchanged.");
        }
        BigDecimal rate = from.settings().rates().get(toId.toLowerCase(Locale.ROOT));
        Optional<CurrencyProvider> to = CurrencyRegistry.provider(toId.toLowerCase(Locale.ROOT));
        if (rate == null || to.isEmpty()) {
            return EconomyResponse.failure("There is no rate from " + from.id() + " to " + toId + ".");
        }
        BigDecimal received = Economy.info(to.get().id()).scale(amount.multiply(rate));
        if (received.signum() <= 0) return EconomyResponse.invalidAmount();

        Transaction transaction = Transaction.of("exchange:" + from.id() + ">" + to.get().id()).by(player);
        EconomyResponse taken = Economy.of(from.id()).withdraw(player, amount, transaction);
        if (!taken.isSuccess()) return taken;
        EconomyResponse given = Economy.of(to.get().id()).deposit(player, received, transaction);
        if (!given.isSuccess()) {
            Economy.of(from.id()).deposit(player, amount, Transaction.of("exchange:refund").by(player));
            return given;
        }
        return EconomyResponse.success(received, given.balance());
    }

    /** What a transfer tax keeps back, rounded in the receiver's favour. */
    static BigDecimal tax(StoredCurrency currency, BigDecimal amount) {
        double percent = currency.settings().transferTaxPercent();
        if (percent <= 0) return BigDecimal.ZERO;
        return currency.info().scale(amount.multiply(BigDecimal.valueOf(percent / 100.0))
                .setScale(8, RoundingMode.DOWN));
    }

    private void safely(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException | LinkageError failure) {
            logger.warning("Economy: could not " + what + ": " + failure);
        }
    }

    /** Waits for every write in flight, for a test or a shutdown. */
    Repository<BalanceRow> balances() {
        return balances;
    }

    Repository<PendingRow> pendingRows() {
        return pending;
    }

    Repository<LedgerRow> ledgerRows() {
        return ledger;
    }

    Plugin plugin() {
        return plugin;
    }

    Debug debug() {
        return debug;
    }
}
