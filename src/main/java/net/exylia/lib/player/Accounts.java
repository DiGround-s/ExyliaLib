package net.exylia.lib.player;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.player.internal.AccountKeyRow;
import net.exylia.lib.player.internal.AccountRow;
import net.exylia.lib.player.internal.AddressRow;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Whether two accounts are likely the same person, and how long the network has
 * known a player — asked of the whole network, whether they are online or not.
 *
 * <pre>{@code
 * private final Accounts accounts = Accounts.of(this);
 *
 * accounts.sameAddress(buyer, seller).thenAccept(alt -> {
 *     if (alt) return; // no reputation between a player and their alt
 *     ...
 * });
 * accounts.firstSeen(invited).thenAccept(first -> ...);
 * }</pre>
 *
 * <h2>What is kept</h2>
 * Every join, on every server where a plugin opened this, writes into that
 * plugin's database: the earliest time the player was seen
 * ({@code exylia_accounts}) and a keyed hash of the address they joined from with
 * when it was last used ({@code exylia_account_addresses}). The address itself
 * is never stored. The key lives in {@code exylia_account_keys}, created by the
 * first server that needs it, so every server that shares the database hashes
 * the same address to the same value. An IPv6 address is hashed by its
 * {@code /64}, the block one household gets, since the rest of it rotates.
 *
 * <p>Point the plugins of every server at one database and the answers are
 * network-wide. A join costs one read and one or two writes, all off the thread
 * that joined.
 *
 * <h2>First seen</h2>
 * The earliest join any server recorded, which also takes the joining server's
 * own {@code getFirstPlayed}: a player who was here long before this module
 * existed is not new the first time it sees them.
 *
 * <h2>Threads</h2>
 * Safe from any thread. Answers complete off the server thread; hop back
 * before touching the world.
 *
 * @since 1.184.0
 */
public final class Accounts {

    /** How recent an address has to be to count, unless {@link #window} says otherwise. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    /** By plugin name; the load that owns each is compared by identity, see {@link #release}. */
    private static final Map<String, Accounts> OPEN = new ConcurrentHashMap<>();
    private static final String KEY_ID = "address";

    private final Plugin plugin;
    private final Repository<AccountRow> accounts;
    private final Repository<AddressRow> addresses;
    private final Repository<AccountKeyRow> keys;
    private final Duration window;
    private final Logger logger;
    private volatile @Nullable CompletableFuture<byte[]> key;
    private volatile LongSupplier clock = System::currentTimeMillis;

    private Accounts(Plugin plugin, Duration window, @Nullable Accounts shared) {
        this.plugin = plugin;
        this.window = window;
        this.logger = plugin.getLogger();
        if (shared != null) {
            this.accounts = shared.accounts;
            this.addresses = shared.addresses;
            this.keys = shared.keys;
            this.key = shared.key;
            this.clock = shared.clock;
        } else {
            this.accounts = Databases.of(plugin).repository(AccountRow.class);
            this.addresses = Databases.of(plugin).repository(AddressRow.class);
            this.keys = Databases.of(plugin).repository(AccountKeyRow.class);
        }
    }

    /**
     * The accounts kept in a plugin's database, recording every join from now
     * on — and the players already online, so a reload loses nobody.
     *
     * <p>Call it once, in {@code onEnable}. Released with the plugin.
     *
     * @param plugin the plugin whose {@code database.yml} holds the tables
     * @return the same instance for the same plugin
     */
    public static @NotNull Accounts of(@NotNull Plugin plugin) {
        // Reloaded in place: an instance owned by a different Plugin object
        // belongs to the previous load, whose release has not run yet.
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? open : null);
        Accounts[] created = {null};
        Accounts accounts = OPEN.computeIfAbsent(plugin.getName(), name -> created[0] = new Accounts(plugin, DEFAULT_WINDOW, null));
        if (created[0] != null) {
            try {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    accounts.recordJoin(online);
                }
            } catch (RuntimeException noServer) {
                // No server behind this call — a test — and nobody to record.
            }
        }
        return accounts;
    }

    /**
     * The same accounts, counting an address as shared only when both players
     * used it within this long.
     *
     * @param window how far back an address counts; {@link #DEFAULT_WINDOW} otherwise
     * @return a view over the same tables
     */
    public @NotNull Accounts window(@NotNull Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("The window has to be positive, not " + window + '.');
        }
        return new Accounts(plugin, window, this);
    }

    /**
     * Whether two players share an address used by both within the window, or
     * are online here from the same address right now.
     *
     * <p>A player is the same person as themselves. Completes exceptionally
     * when the database cannot answer, and the caller decides what that means:
     * a reward refuses ({@code .exceptionally(failure -> true)}), a warning
     * stays quiet.
     *
     * @param first  one player
     * @param second the other
     * @return whether they are likely the same person
     */
    public @NotNull CompletableFuture<Boolean> sameAddress(@NotNull UUID first, @NotNull UUID second) {
        if (first.equals(second)) {
            return CompletableFuture.completedFuture(true);
        }
        long since = clock.getAsLong() - window.toMillis();
        return key().thenCompose(secret -> {
            String here = hashOf(secret, online(first));
            if (here != null && here.equals(hashOf(secret, online(second)))) {
                return CompletableFuture.completedFuture(true);
            }
            return recent(first, since).thenCombine(recent(second, since), (mine, theirs) -> {
                mine.retainAll(theirs);
                return !mine.isEmpty();
            });
        });
    }

    /**
     * When the network first saw a player.
     *
     * @param player the player
     * @return the earliest join any server recorded, empty for somebody never
     *         seen; completes exceptionally when the database cannot answer,
     *         which must not read as "new"
     */
    public @NotNull CompletableFuture<Optional<Instant>> firstSeen(@NotNull UUID player) {
        return accounts.find(player).thenApply(row -> row.map(found -> Instant.ofEpochMilli(found.firstSeen())));
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    /** Records a join in every plugin's accounts. Called by the library on join. */
    public static void joined(@NotNull Player player) {
        for (Accounts accounts : OPEN.values()) {
            accounts.recordJoin(player);
        }
    }

    /**
     * Stops using a plugin's database, when it is disabled — this load only: a
     * plugin reloaded in place has a newer load alive by the time this runs.
     */
    public static void release(@NotNull Plugin plugin) {
        OPEN.computeIfPresent(plugin.getName(), (name, open) -> open.plugin == plugin ? null : open);
    }

    /** Forgets every plugin, on shutdown. */
    public static void releaseAll() {
        OPEN.clear();
    }

    private void recordJoin(Player player) {
        long firstPlayed;
        try {
            firstPlayed = player.getFirstPlayed();
        } catch (RuntimeException unsupported) {
            firstPlayed = 0L;
        }
        record(player.getUniqueId(), addressOf(player), firstPlayed);
    }

    /**
     * Writes a join: the earliest sighting, and the address it came from.
     *
     * @param firstPlayed what the joining server remembers, or {@code 0} for nothing
     */
    CompletableFuture<Void> record(UUID player, @Nullable InetAddress address, long firstPlayed) {
        long now = clock.getAsLong();
        long earliest = firstPlayed > 0 && firstPlayed < now ? firstPlayed : now;
        // A read before the write, and no compare-and-set: the only writer that
        // could race it is the same player joining two servers at once.
        CompletableFuture<Void> seen = accounts.find(player).thenCompose(found -> {
            if (found.isPresent() && found.get().firstSeen() <= earliest) {
                return CompletableFuture.completedFuture(null);
            }
            return accounts.save(new AccountRow(player, earliest));
        });
        CompletableFuture<Void> from = address == null ? CompletableFuture.completedFuture(null)
                : key().thenCompose(secret -> {
                    String hash = hash(secret, address);
                    // ponytail: rows are never pruned; one per player per address
                    // block is small, and a sweep by age needs a range delete.
                    return addresses.save(new AddressRow(player + "/" + hash, player, hash, now));
                });
        return CompletableFuture.allOf(seen, from).exceptionally(failure -> {
            logger.warning("Accounts: could not record the join of " + player + ": " + failure.getMessage());
            return null;
        });
    }

    private CompletableFuture<Set<String>> recent(UUID player, long since) {
        return addresses.where("player", player).find().thenApply(rows -> {
            Set<String> hashes = new HashSet<>();
            for (AddressRow row : rows) {
                if (row.lastSeen() >= since) {
                    hashes.add(row.address());
                }
            }
            return hashes;
        });
    }

    /**
     * The network's hashing key: read once, created by whoever asks first.
     *
     * <p>{@code increment} creates the row and never overwrites one, so two
     * servers asking at once agree on the first one's key, and the read that
     * follows returns it to both.
     */
    private CompletableFuture<byte[]> key() {
        CompletableFuture<byte[]> known = key;
        if (known != null && !known.isCompletedExceptionally()) {
            return known;
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        CompletableFuture<byte[]> loading = keys
                .increment(new AccountKeyRow(KEY_ID, HexFormat.of().formatHex(fresh), 1L), "reads")
                .thenCompose(ignored -> keys.find(KEY_ID))
                .thenApply(row -> HexFormat.of().parseHex(row.orElseThrow().secret()));
        key = loading;
        return loading;
    }

    private static @Nullable InetAddress addressOf(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        InetSocketAddress socket = player.getAddress();
        return socket == null ? null : socket.getAddress();
    }

    private static @Nullable InetAddress online(UUID player) {
        try {
            return addressOf(Bukkit.getPlayer(player));
        } catch (RuntimeException noServer) {
            return null;
        }
    }

    private static @Nullable String hashOf(byte[] secret, @Nullable InetAddress address) {
        return address == null ? null : hash(secret, address);
    }

    /** The first 16 bytes of an HMAC-SHA256 of the address, or its /64 for IPv6. */
    static String hash(byte[] secret, InetAddress address) {
        byte[] raw = address.getAddress();
        if (address instanceof Inet6Address) {
            raw = Arrays.copyOf(raw, 8);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw), 0, 16);
        } catch (GeneralSecurityException missing) {
            // Every Java runtime ships HmacSHA256; this is unreachable.
            throw new IllegalStateException(missing);
        }
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** For tests: replaces the clock. */
    void clockForTests(LongSupplier replacement) {
        clock = replacement;
    }

    /** For tests: the rows recorded for a player. */
    CompletableFuture<List<AddressRow>> addressesForTests(UUID player) {
        return addresses.where("player", player).find();
    }
}
