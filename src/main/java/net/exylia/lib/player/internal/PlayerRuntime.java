package net.exylia.lib.player.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.proxy.Proxy;
import net.exylia.lib.proxy.ProxyPlayer;
import net.exylia.lib.skull.internal.SkullRuntime;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.LibraryMessages;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The name-to-id directory the whole ecosystem was missing.
 *
 * <h2>The tiers, and why they are in this order</h2>
 * Each one is cheaper than the next by an order of magnitude, so the
 * expensive ones are only reached by a name none of the cheap ones knew:
 *
 * <ol>
 *   <li><b>Online here.</b> A map lookup in the server.</li>
 *   <li><b>This directory.</b> A Caffeine lookup. Holds what the tiers below
 *       resolved, so a name costs the network once, not once per command.</li>
 *   <li><b>Bukkit's user cache.</b> The {@code usercache.json} the server
 *       already holds in memory: {@code getOfflinePlayerIfCached} for a name,
 *       the id overload of {@code getOfflinePlayer} for an id. A map lookup
 *       either way — no file, no Mojang, nothing to block on. This is the
 *       tier that makes offline commands free, and on an established server
 *       it answers for almost everyone.</li>
 *   <li><b>The proxy.</b> Somebody connected to another backend who has never
 *       set foot here. A Redis round trip, five seconds at worst.</li>
 *   <li><b>Mojang.</b> Somebody nobody on the network has ever seen. Shares
 *       the skull module's client, so one rate limit backs both off at once
 *       rather than each discovering it separately.</li>
 * </ol>
 *
 * <p>The first three are synchronous and touch nothing but memory, which is
 * what lets a Lamp parameter type use them: Lamp calls {@code parse} for
 * every tab keystroke and several times per execution, so a parameter that
 * did I/O would do it dozens of times a second.
 *
 * <h2>What is never done</h2>
 * {@code Bukkit.getOfflinePlayer(String)} is not called anywhere. It looks
 * like tier 3 and behaves like tier 5: for a <em>name</em> the user cache has
 * never seen it goes to Mojang, on whatever thread asked, with no back-off
 * and no negative cache. Three plugins were calling it from a command
 * handler. The id overload is a different method with a different
 * implementation and is safe; the asymmetry is the single most useful thing
 * to know about this module.
 *
 * @since 1.146.0
 */
@ApiStatus.Internal
public final class PlayerRuntime {

    /**
     * How many names the directory remembers.
     *
     * <p>Sized for a network's active population rather than its history:
     * anybody it forgets is still one user-cache lookup away, and the only
     * names that cost anything to rediscover are the ones that came from the
     * proxy or from Mojang.
     */
    private static final int MAX_NAMES = 20_000;

    /** How long a name is trusted to still belong to the same id. */
    private static final Duration NAME_TTL = Duration.ofHours(6);

    /**
     * How long a name nobody could resolve stays unresolvable.
     *
     * <p>Short on purpose: it exists so a typo in a command does not cost a
     * proxy round trip and a Mojang request every time somebody presses
     * enter, not to remember that somebody does not exist. A player who joins
     * within the window is still found by tier 1 before this is consulted.
     */
    private static final Duration UNKNOWN_TTL = Duration.ofMinutes(5);

    private static final Cache<String, UUID> BY_NAME = Caffeine.newBuilder()
            .maximumSize(MAX_NAMES)
            .expireAfterAccess(NAME_TTL)
            .build();

    private static final Cache<UUID, String> BY_ID = Caffeine.newBuilder()
            .maximumSize(MAX_NAMES)
            .expireAfterAccess(NAME_TTL)
            .build();

    private static final Cache<String, Boolean> UNKNOWN = Caffeine.newBuilder()
            .maximumSize(2_000)
            .expireAfterWrite(UNKNOWN_TTL)
            .build();

    /**
     * The lookups in flight, by what is being looked up.
     *
     * <p>Six staff members reacting to the same name in chat is one request,
     * not six. The same collapsing the skull module does for heads, and for
     * the same reason: the expensive tiers are shared, and a rate limit
     * reached by one of them is reached by all.
     */
    private static final Map<String, CompletableFuture<Optional<ExyliaPlayer>>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private static volatile @Nullable Plugin library;

    private PlayerRuntime() {
        throw new AssertionError("No instances.");
    }

    /** Takes the library plugin, whose scheduler the async tiers run on. */
    public static void init(@NotNull Plugin plugin) {
        library = plugin;
    }

    /** Drops the directory. Called when the library is disabled. */
    public static void shutdown() {
        library = null;
        BY_NAME.invalidateAll();
        BY_ID.invalidateAll();
        UNKNOWN.invalidateAll();
        IN_FLIGHT.clear();
    }

    /**
     * Records a name against an id, in both directions.
     *
     * <p>Called on every join, and by anything that learns a pairing the
     * cheap tiers did not know: a row read from a database, a proxy answer.
     */
    public static void remember(@NotNull UUID id, @NotNull String name) {
        if (name.isBlank()) {
            return;
        }
        String key = key(name);
        BY_NAME.put(key, id);
        BY_ID.put(id, name);
        // A name that has just been resolved is not unknown any more, and
        // leaving it in the negative cache would have the next lookup skip
        // every tier and report nobody for up to five minutes.
        UNKNOWN.invalidate(key);
    }

    /** Records the name of a player who just joined. */
    public static void remember(@NotNull Player player) {
        remember(player.getUniqueId(), player.getName());
    }

    /**
     * Whoever the cheap tiers know by this name or id, without any I/O.
     *
     * @param nameOrId a player name, or a uuid as text
     * @return their address, or {@code null} when the cheap tiers do not know
     */
    public static @Nullable ExyliaPlayer cached(@NotNull String nameOrId) {
        String input = nameOrId.trim();
        if (input.isEmpty()) {
            return null;
        }
        UUID asId = asUuid(input);
        if (asId != null) {
            return cached(asId);
        }
        Player here = online(input);
        if (here != null) {
            remember(here);
            return ExyliaPlayer.of(here);
        }
        UUID known = BY_NAME.getIfPresent(key(input));
        if (known != null) {
            String name = BY_ID.getIfPresent(known);
            return new ExyliaPlayer(known, name != null ? name : input, null);
        }
        OfflinePlayer seen = userCache(input);
        if (seen != null && seen.getName() != null) {
            remember(seen.getUniqueId(), seen.getName());
            return new ExyliaPlayer(seen.getUniqueId(), seen.getName(), null);
        }
        return null;
    }

    /**
     * Whoever the cheap tiers know by this id, without any I/O.
     *
     * @param id their id
     * @return their address, or {@code null} when no tier knows their name
     */
    public static @Nullable ExyliaPlayer cached(@NotNull UUID id) {
        String name = nameOf(id);
        return name == null ? null : new ExyliaPlayer(id, name, null);
    }

    /**
     * The name this id last answered to, from memory only.
     *
     * <p>Online here, then the directory, then the server's profile cache.
     *
     * <p>That last tier is {@code getOfflinePlayer(id).getName()}, which is
     * the <em>id</em> overload and reads the same {@code usercache.json} the
     * server already holds — a map lookup, no request. It is the name
     * direction, {@code getOfflinePlayer(String)}, that goes to Mojang when
     * it misses, and that one is never called anywhere in this module. The
     * distinction is the whole reason a menu can put names on a hundred
     * stored ids without leaving the thread it was drawn on.
     */
    public static @Nullable String nameOf(@NotNull UUID id) {
        Player here = online(id);
        if (here != null) {
            remember(here);
            return here.getName();
        }
        String known = BY_ID.getIfPresent(id);
        if (known != null) {
            return known;
        }
        String cached = profileCache(id);
        if (cached != null) {
            remember(id, cached);
        }
        return cached;
    }

    /**
     * Whoever answers to this name or id, going out to the network and to
     * Mojang if the cheap tiers do not know.
     *
     * <p>Never fails exceptionally, and never blocks the caller. Completes on
     * whatever thread finished the work, which is never the server thread:
     * callers that touch the world hop back themselves, or use
     * {@link #then}.
     *
     * @param nameOrId a player name, or a uuid as text
     * @return their address, or empty when nobody answers to it
     */
    public static @NotNull CompletableFuture<Optional<ExyliaPlayer>> resolve(@NotNull String nameOrId) {
        String input = nameOrId.trim();
        if (input.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        ExyliaPlayer known = cached(input);
        if (known != null) {
            return CompletableFuture.completedFuture(Optional.of(known));
        }
        if (Boolean.TRUE.equals(UNKNOWN.getIfPresent(key(input)))) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return collapse(key(input), () -> fromNetwork(input));
    }

    /**
     * Whoever answers to this id, going out to the network if needed.
     *
     * @param id their id
     * @return their address, or empty when not even their name can be found
     */
    public static @NotNull CompletableFuture<Optional<ExyliaPlayer>> resolve(@NotNull UUID id) {
        ExyliaPlayer known = cached(id);
        if (known != null) {
            return CompletableFuture.completedFuture(Optional.of(known));
        }
        // Only the proxy is left: the profile cache is a synchronous tier, so
        // cached() above has already consulted it, and Mojang has no name for
        // an id it will hand over — that endpoint answers the other way round.
        return collapse(id.toString(), () -> fromProxy(id.toString()));
    }

    /**
     * Resolves a target and hands it back on the thread that may touch the
     * world, telling the sender itself when nobody answers.
     *
     * <p>This is the shape every plugin was writing by hand, and getting
     * wrong in the same two places: the failure branch sent its message from
     * the Redis thread, and the success branch assumed the sender was still
     * connected.
     *
     * @param sender   who asked, and who is told when nobody is found
     * @param nameOrId what they typed
     * @param action   what to do with the target, on the server thread
     */
    public static void then(@NotNull CommandSender sender,
                            @NotNull String nameOrId,
                            @NotNull Consumer<ExyliaPlayer> action) {
        then(sender, nameOrId, action, () -> notFound(sender, nameOrId));
    }

    /**
     * The same, for a plugin that has its own wording for nobody found.
     *
     * <p>Both branches run on the sender's thread, which is the half plugins
     * were getting wrong: the failure one used to send its line from whatever
     * thread the answer arrived on.
     *
     * @param sender   who asked
     * @param nameOrId what they typed
     * @param action   what to do with the target, on the server thread
     * @param notFound what to do when nobody answers, on the server thread
     */
    public static void then(@NotNull CommandSender sender,
                            @NotNull String nameOrId,
                            @NotNull Consumer<ExyliaPlayer> action,
                            @NotNull Runnable notFound) {
        resolve(nameOrId).thenAccept(found -> onSenderThread(sender, () -> {
            if (found.isEmpty()) {
                notFound.run();
                return;
            }
            action.accept(found.get());
        }));
    }

    /**
     * Tells a sender that nobody answers to a name.
     *
     * <p>One sentence for the whole server, from the library's own
     * {@code messages.yml}, so a plugin that adopts this module does not have
     * to invent a twelfth wording of it.
     */
    public static void notFound(@NotNull CommandSender sender, @NotNull String name) {
        Text.of(LibraryMessages.get().players().notFound()).with("%player%", name).send(sender);
    }

    /**
     * Every name worth offering as a completion.
     *
     * <p>This server's players and the network's — not the user cache, which
     * on an established server is a hundred thousand names, and not the
     * directory either: what it holds beyond those two is whatever somebody
     * once typed, which is not a suggestion.
     */
    public static @NotNull Set<String> names() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(Proxy.players());
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
        } catch (Exception noServer) {
            // A directory with no server is a directory of what the proxy and
            // the cache know, which is the right answer for a test.
        }
        return names;
    }

    // --- the expensive tiers -------------------------------------------------

    private static CompletableFuture<Optional<ExyliaPlayer>> fromNetwork(String name) {
        return fromProxy(name).thenCompose(found -> {
            if (found.isPresent()) {
                return CompletableFuture.completedFuture(found);
            }
            return async(() -> fromMojang(name));
        }).thenApply(found -> {
            if (found.isEmpty()) {
                UNKNOWN.put(key(name), Boolean.TRUE);
            }
            return found;
        });
    }

    private static CompletableFuture<Optional<ExyliaPlayer>> fromProxy(String nameOrId) {
        return Proxy.find(nameOrId).thenApply(found -> found.map(PlayerRuntime::adopt));
    }

    private static ExyliaPlayer adopt(ProxyPlayer player) {
        remember(player.id(), player.name());
        return new ExyliaPlayer(player.id(), player.name(),
                player.isOnAServer() ? player.server() : null);
    }

    /**
     * Mojang, through the skull module's client.
     *
     * <p>Its client and not a second one: the back-off is a single deadline
     * per client, and two clients mean the second one keeps asking after the
     * first has been told to stop.
     */
    private static Optional<ExyliaPlayer> fromMojang(String name) {
        if (!isOnlineMode()) {
            // An offline-mode server does not use Mojang's ids, so Mojang's
            // answer would be an id no row in any table is keyed by — and
            // worse, one that looks valid. Deriving the offline id from the
            // name instead is not safe either: a Bungee network runs its
            // backends in offline mode while forwarding the real Mojang ids,
            // so the derivation would be wrong exactly where it looks most
            // obviously right. On such a server the user cache and the proxy
            // are the last tiers, which is the honest answer.
            return Optional.empty();
        }
        UUID id = SkullRuntime.idOf(name);
        if (id == null) {
            return Optional.empty();
        }
        // The name as typed: Mojang's name-to-id endpoint answers with an id
        // and nothing else, so the casing the player actually uses is only
        // learned when they join. Close enough to address them by, and
        // corrected the moment they turn up.
        remember(id, name);
        return Optional.of(new ExyliaPlayer(id, name, null));
    }

    // --- plumbing -----------------------------------------------------------

    /**
     * Runs one lookup per key, however many ask for it.
     *
     * <p>The entry is removed when the work finishes rather than cached, so
     * the caches above stay the only place an answer lives and there is one
     * eviction policy instead of two.
     */
    private static CompletableFuture<Optional<ExyliaPlayer>> collapse(
            String key, Supplier<CompletableFuture<Optional<ExyliaPlayer>>> work) {
        CompletableFuture<Optional<ExyliaPlayer>> promise = new CompletableFuture<>();
        CompletableFuture<Optional<ExyliaPlayer>> running = IN_FLIGHT.putIfAbsent(key, promise);
        if (running != null) {
            return running;
        }
        // Started and cleaned up outside computeIfAbsent on purpose. A tier
        // that answers without leaving the thread — no bridge, or the library
        // already shut down — completes while the mapping function is still
        // running, and a ConcurrentHashMap modified from inside its own
        // computation throws "Recursive update". Two stores in the ecosystem
        // found that out in production.
        CompletableFuture<Optional<ExyliaPlayer>> lookup;
        try {
            lookup = work.get();
        } catch (RuntimeException failed) {
            IN_FLIGHT.remove(key, promise);
            promise.complete(Optional.empty());
            return promise;
        }
        lookup.whenComplete((found, error) -> {
            IN_FLIGHT.remove(key, promise);
            promise.complete(error != null || found == null ? Optional.empty() : found);
        });
        return promise;
    }

    /**
     * Runs blocking work on the library's async scheduler.
     *
     * <p>The library's and not the caller's: a proxy answer arrives on the
     * Redis subscriber thread, and a Mojang request started from there would
     * hold up every other message on the bridge for as long as it took.
     */
    private static CompletableFuture<Optional<ExyliaPlayer>> async(
            Supplier<Optional<ExyliaPlayer>> work) {
        Plugin plugin = library;
        if (plugin == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CompletableFuture<Optional<ExyliaPlayer>> future = new CompletableFuture<>();
        Tasks.of(plugin).runAsync(() -> {
            try {
                future.complete(work.get());
            } catch (Exception failed) {
                future.complete(Optional.empty());
            }
        });
        return future;
    }

    /**
     * Runs an action on the thread that owns the sender.
     *
     * <p>The sender's own region on Folia when they are a player, the global
     * thread otherwise, and inline when it is already the right one — the
     * same choice the command module makes, and for the same reason: a
     * console command that took a player's thread threw
     * {@code Dispatching command async} and the failure was swallowed.
     */
    private static void onSenderThread(CommandSender sender, Runnable action) {
        Plugin plugin = library;
        if (plugin == null) {
            action.run();
            return;
        }
        if (!(sender instanceof Player player)) {
            if (Tasks.of(plugin).isGlobalThread()) {
                action.run();
            } else {
                Tasks.of(plugin).run(action);
            }
            return;
        }
        if (Tasks.of(plugin).isOwnedBy(player)) {
            action.run();
            return;
        }
        // A sender who left before the answer came back is told nothing,
        // which is the whole of what the retired callback has to do.
        Tasks.of(plugin).runAtEntity(player, action, null);
    }

    private static @Nullable Player online(String name) {
        try {
            return Bukkit.getPlayerExact(name);
        } catch (Exception noServer) {
            return null;
        }
    }

    private static @Nullable Player online(UUID id) {
        try {
            return Bukkit.getPlayer(id);
        } catch (Exception noServer) {
            return null;
        }
    }

    private static boolean isOnlineMode() {
        try {
            return Bukkit.getOnlineMode();
        } catch (Exception noServer) {
            return false;
        }
    }

    /**
     * The name the server's profile cache has for an id, or {@code null}.
     *
     * <p>Guarded rather than trusted: the contract above holds on every
     * platform this library runs on, but a fork that made this overload go
     * looking would make it slow rather than wrong, and an exception here
     * must not take a menu down with it.
     */
    private static @Nullable String profileCache(UUID id) {
        try {
            String name = Bukkit.getOfflinePlayer(id).getName();
            return name == null || name.isBlank() ? null : name;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static @Nullable OfflinePlayer userCache(String name) {
        try {
            return Bukkit.getOfflinePlayerIfCached(name);
        } catch (Exception unsupported) {
            // Spigot has no user cache accessor. The tier is skipped rather
            // than replaced by the name overload, which would go to Mojang on
            // whatever thread asked.
            return null;
        }
    }

    private static @Nullable UUID asUuid(String input) {
        // Cheap enough to be worth checking before paying for the exception:
        // a name is at most 16 characters and a uuid is 32 or 36.
        if (input.length() < 32 || input.indexOf(' ') >= 0) {
            return null;
        }
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
