package net.exylia.lib.shop.restock;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.network.NetworkGroups;
import net.exylia.lib.shop.restock.internal.RestockRow;
import net.exylia.lib.task.Tasks;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A shop whose stock is drawn from a pool each window and limited per player.
 *
 * <p>The stock is a pure function of the shop id and the window
 * ({@code epoch millis / period}), so every server shows the same offers with
 * no storage and no leader. Purchases are counted in the database with a
 * compare-and-set, so two servers cannot sell a player more than the limit.
 * A count belongs to its window: the next window starts again from zero.
 *
 * @since 1.632.0
 */
public final class Restock {

    private static final int ATTEMPTS = 8;

    private final Plugin plugin;
    private final String id;
    private final long period;
    private final int slots;
    private final List<Offer> pool;
    private final String scope;
    private final List<Consumer<List<Offer>>> listeners = new CopyOnWriteArrayList<>();
    private volatile LongSupplier clock = System::currentTimeMillis;
    private volatile @Nullable Repository<RestockRow> rows;
    private volatile long seen;
    private volatile boolean timer;

    Restock(Builder b) {
        this.plugin = b.plugin;
        this.id = b.id;
        this.period = b.period.toMillis();
        this.slots = b.slots;
        this.pool = List.copyOf(b.pool);
        this.scope = b.scope;
        this.seen = window();
    }

    public static final class Builder {
        final Plugin plugin;
        final String id;
        Duration period = Duration.ofMinutes(30);
        int slots = 4;
        List<Offer> pool = new ArrayList<>();
        String scope = "default";

        Builder(Plugin plugin, String id) {
            this.plugin = plugin;
            this.id = id;
        }

        /** How long a stock lasts. At least one second. */
        public @NotNull Builder every(@NotNull Duration period) {
            this.period = period.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : period;
            return this;
        }

        /** How many offers are on sale at once. */
        public @NotNull Builder slots(int slots) {
            this.slots = Math.max(1, slots);
            return this;
        }

        /** Everything that can appear. */
        public @NotNull Builder pool(@NotNull List<Offer> pool) {
            this.pool = new ArrayList<>(pool);
            return this;
        }

        /** Whether purchases are shared: {@code default}, {@code global}, {@code server} or a group's name (see NetworkGroups). */
        public @NotNull Builder scope(@Nullable String setting) {
            this.scope = setting == null ? "default" : setting;
            return this;
        }

        public @NotNull Restock build() {
            return new Restock(this);
        }
    }

    public @NotNull String id() {
        return id;
    }

    private long window() {
        return Rotation.window(clock.getAsLong(), period);
    }

    /** What is on sale now. */
    public @NotNull List<Offer> current() {
        return Rotation.draw(id, window(), slots, pool);
    }

    /** When the stock changes. */
    public @NotNull Instant nextRestock() {
        return Instant.ofEpochMilli((window() + 1) * period);
    }

    public @NotNull Duration untilRestock() {
        return Duration.ofMillis((window() + 1) * period - clock.getAsLong());
    }

    /** How many of an offer the player may still take; 0 when it is not on sale. */
    public @NotNull CompletableFuture<Integer> remaining(@NotNull UUID player, @NotNull String offerId) {
        Offer offer = onSale(offerId);
        if (offer == null) return CompletableFuture.completedFuture(0);
        long window = window();
        return repository().findFresh(key(offerId, player))
                .thenApply(row -> Math.max(0, offer.stockPerPlayer() - taken(row.orElse(null), window)));
    }

    /**
     * Takes {@code amount} of an offer for a player, all or nothing.
     *
     * @return {@code true} when it was on sale and within the player's limit; the plugin then charges and delivers
     */
    public @NotNull CompletableFuture<Boolean> take(@NotNull UUID player, @NotNull String offerId, int amount) {
        Offer offer = onSale(offerId);
        if (offer == null || amount <= 0) return CompletableFuture.completedFuture(false);
        return attempt(player, offer, amount, window(), ATTEMPTS);
    }

    /** Gives back what a failed purchase took (a payment that was refused after {@link #take}). */
    public @NotNull CompletableFuture<Void> release(@NotNull UUID player, @NotNull String offerId, int amount) {
        return release(player, offerId, amount, window(), ATTEMPTS);
    }

    private CompletableFuture<Void> release(UUID player, String offerId, int amount, long window, int left) {
        Repository<RestockRow> repo = repository();
        String key = key(offerId, player);
        return repo.findFresh(key).thenCompose(found -> {
            RestockRow row = found.orElse(null);
            int now = taken(row, window);
            if (row == null || left <= 0 || now == 0) return CompletableFuture.<Void>completedFuture(null);
            RestockRow next = new RestockRow(key, player, window + ":" + Math.max(0, now - amount));
            return repo.updateIf(next, "state", row.state()).thenCompose(won ->
                    won ? CompletableFuture.<Void>completedFuture(null) : release(player, offerId, amount, window, left - 1));
        });
    }

    private CompletableFuture<Boolean> attempt(UUID player, Offer offer, int amount, long window, int left) {
        Repository<RestockRow> repo = repository();
        String key = key(offer.id(), player);
        return repo.findFresh(key).thenCompose(found -> {
            RestockRow row = found.orElse(null);
            int now = taken(row, window);
            if (now + amount > offer.stockPerPlayer()) return CompletableFuture.completedFuture(false);
            if (left <= 0) return CompletableFuture.completedFuture(false);
            RestockRow next = new RestockRow(key, player, window + ":" + (now + amount));
            CompletableFuture<Boolean> won = row == null ? repo.insertIfAbsent(next) : repo.updateIf(next, "state", row.state());
            return won.thenCompose(ok -> ok ? CompletableFuture.completedFuture(true)
                    : attempt(player, offer, amount, window, left - 1));
        });
    }

    private @Nullable Offer onSale(String offerId) {
        for (Offer offer : current()) {
            if (offer.id().equals(offerId)) return offer;
        }
        return null;
    }

    private static int taken(@Nullable RestockRow row, long window) {
        if (row == null) return 0;
        int cut = row.state().indexOf(':');
        if (cut < 0 || Long.parseLong(row.state().substring(0, cut)) != window) return 0;
        return Integer.parseInt(row.state().substring(cut + 1));
    }

    private String key(String offerId, UUID player) {
        return id + '|' + offerId + '|' + player;
    }

    private Repository<RestockRow> repository() {
        Repository<RestockRow> current = rows;
        if (current == null) {
            current = Databases.of(plugin).repository(RestockRow.class, NetworkGroups.scope(plugin, scope));
            rows = current;
        }
        return current;
    }

    /**
     * Runs {@code listener} once each time the stock changes, on this server.
     * Not run for the stock already on sale when it is registered.
     */
    public void onRestock(@NotNull Consumer<List<Offer>> listener) {
        listeners.add(listener);
        if (!timer) {
            timer = true;
            try {
                Tasks.of(plugin).runTimer(20L, 20L, this::check);
            } catch (RuntimeException noServer) {
                // No scheduler behind this call: a test drives check() itself.
            }
        }
    }

    /** Fires the listeners when the window moved since the last look. */
    void check() {
        long now = window();
        if (now == seen) return;
        seen = now;
        List<Offer> stock = current();
        for (Consumer<List<Offer>> listener : listeners) {
            try {
                listener.accept(stock);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Restock '" + id + "' listener failed: " + failure);
            }
        }
    }

    /** For tests. */
    void clockForTests(LongSupplier clock) {
        this.clock = clock;
        this.seen = window();
    }
}
