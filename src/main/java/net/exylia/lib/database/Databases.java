package net.exylia.lib.database;

import net.exylia.lib.database.internal.CodecRegistry;
import net.exylia.lib.database.internal.DatabaseRuntime;
import net.exylia.lib.database.internal.SqlSettings;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records stored in a database, configured by each consumer plugin.
 *
 * <pre>{@code
 * @Table("player_stats")
 * public record PlayerStats(
 *         @Id UUID uuid,
 *         @Column int elo,
 *         @Indexed @Column(length = 32) String clan) {
 * }
 *
 * private Repository<PlayerStats> stats;
 *
 * @Override
 * public void onEnable() {
 *     stats = Databases.of(this).repository(PlayerStats.class);
 * }
 *
 * // later, anywhere
 * stats.find(player.getUniqueId()).thenAccept(found ->
 *         tasks.runAtEntity(player, () -> found.ifPresent(this::show)));
 * }</pre>
 *
 * <h2>One client per datasource, owned by the library</h2>
 * Each consumer owns {@code plugins/<Plugin>/database.yml}. ExyliaLib opens the
 * client and shares it only with plugins whose fully resolved settings match,
 * so plugins retain independent defaults while intentional shared databases do
 * not duplicate pools.
 *
 * <p>H2 is the default and the fallback: a file next to the consumer plugin, no daemon,
 * no credentials, nothing to install. A server owner who wants MySQL says so.
 *
 * <h2>Nothing blocks and nothing runs on a game thread</h2>
 * Every repository method returns a {@link java.util.concurrent.CompletableFuture}
 * and there is no synchronous form of anything. Come back to the game with
 * {@code Tasks}, exactly as anywhere else. Registering a repository does not
 * block either: the pool opens and the table is created in the background, and
 * an operation issued before that finishes is chained onto it rather than raced
 * against it.
 *
 * <h2>Threads</h2>
 * Every method here is safe from any thread.
 *
 * @see Repository
 * @see DatabaseSettings
 * @since 1.24.0
 */
public final class Databases {

    /**
     * One view per plugin.
     *
     * <p>Keyed by name rather than by instance, so that the view a plugin gets
     * survives a {@code /reload} handing out a new {@code Plugin} object, and so
     * that {@link #release(String)} — which only has the name — finds it.
     */
    private static final Map<String, PluginDatabase> VIEWS = new ConcurrentHashMap<>();

    private Databases() {
        throw new AssertionError("No instances.");
    }

    /**
     * The database view of a plugin.
     *
     * <p>Cheap and cached: calling it repeatedly returns the same instance,
     * though storing the repositories it hands out reads better.
     *
     * @param plugin the plugin that will own the repositories
     * @return its view of the shared database
     */
    public static @NotNull PluginDatabase of(@NotNull Plugin plugin) {
        return VIEWS.computeIfAbsent(plugin.getName(), ignored -> new PluginDatabase(plugin));
    }

    /**
     * Teaches the library how to store a type it does not already know.
     *
     * <pre>{@code
     * Databases.codec(Kit.class, Codec.of(Kit::id, kits::byId));
     * }</pre>
     *
     * <p>Global, because the encoding belongs to the type and not to whoever
     * happened to register it: two plugins storing a {@code Kit} in two formats
     * would each be unable to read the other's rows, and one table written by
     * both would be unreadable by either.
     *
     * <p>Register before the first repository that needs it. A record is
     * compiled once, when its repository is created, and it resolves each column
     * to a codec then and holds it — that is what keeps a row cheap. A codec
     * registered afterwards does not reach a model that was already compiled.
     *
     * <p>The library already carries codecs for {@code UUID}, enums,
     * {@code ItemStack}, {@code ItemStack[]}, {@code Location} and lists of any
     * of them, in the exact format ExyliaCommons wrote, so a server that swaps
     * the library reads its existing rows unchanged. Registering one for a type
     * the library already knows replaces it, which is the escape hatch for a
     * table whose rows are in a shape of their own.
     *
     * @param type  the type stored
     * @param codec how it is stored
     * @param <T>   the type stored
     */
    public static <T> void codec(@NotNull Class<T> type, @NotNull Codec<T> codec) {
        CodecRegistry.register(type, codec);
    }

    /**
     * Whether any active database target is open and usable.
     *
     * <p>{@code false} both before any plugin asked for a repository — nothing
     * connects until something needs it — and after a failure to open. From a
     * caller's point of view those are the same thing: there is nowhere to store
     * anything yet. A plugin does not need to check this before using a
     * repository; operations wait for the connection on their own.
     *
     * @return whether the database is connected
     */
    public static boolean isReady() {
        return DatabaseRuntime.isReady();
    }

    /**
     * The engine in use, for a global diagnostics command.
     *
     * @return one target engine, {@code multiple} when more than one target is
     *         active, or {@code unconfigured} when none is active
     */
    public static @NotNull String engine() {
        return DatabaseRuntime.engine();
    }

    /**
     * How many plugins hold repositories, for diagnostics.
     *
     * @return the count
     */
    public static int registered() {
        return VIEWS.size();
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Starts the database lifecycle.
     *
     * <p>Called once by ExyliaLib on enable. This does not load configuration or
     * connect: a consumer's {@code database.yml} is loaded when it asks for its
     * view, and its first repository opens the target.
     *
     * @param plugin the library plugin
     */
    public static void init(@NotNull Plugin plugin) {
        DatabaseRuntime.init(plugin);
    }

    /**
     * Forgets one plugin's repositories.
     *
     * <p>Called by ExyliaLib when the plugin is disabled; consumers do not need
     * to. The target stays open while another plugin holds the same datasource.
     *
     * @param pluginName the name of the plugin being disabled
     */
    public static void release(@NotNull String pluginName) {
        PluginDatabase view = VIEWS.remove(pluginName);
        if (view != null) {
            view.release();
        }
    }

    /**
     * Closes the connection and forgets every repository.
     *
     * <p>Called by ExyliaLib on shutdown; consumers do not need to. A pool that
     * outlives its plugin holds threads and sockets nothing will ever close.
     */
    public static void releaseAll() {
        VIEWS.values().forEach(PluginDatabase::release);
        VIEWS.clear();
        DatabaseRuntime.shutdown();
    }

    /**
     * Test seam: points the shared connection at explicit settings, bypassing
     * the config file.
     *
     * <p>Not part of the API and not usable from outside the library — the
     * settings type is internal. Tests run against H2 in memory, because a
     * wire-format bug is invisible to a mock by construction.
     *
     * @param plugin   the plugin whose scheduler runs the work
     * @param settings where to connect
     */
    static void installForTests(@NotNull Plugin plugin, @NotNull SqlSettings settings) {
        VIEWS.values().forEach(PluginDatabase::release);
        VIEWS.clear();
        DatabaseRuntime.installForTests(plugin, settings);
    }

    static void installForTests(@NotNull Plugin plugin, @NotNull Map<String, SqlSettings> settings) {
        VIEWS.values().forEach(PluginDatabase::release);
        VIEWS.clear();
        DatabaseRuntime.installForTests(plugin, settings);
    }

    static int targetsForTests() {
        return DatabaseRuntime.targetCount();
    }
}
