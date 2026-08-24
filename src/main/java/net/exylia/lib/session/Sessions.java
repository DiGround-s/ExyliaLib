package net.exylia.lib.session;

import net.exylia.lib.debug.Debug;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Who has a player right now, server-wide.
 *
 * <pre>{@code
 * private PluginSessions sessions;
 *
 * @Override
 * public void onEnable() {
 *     sessions = Sessions.of(this);
 * }
 *
 * // Taking a player, and being told no if somebody else already has them:
 * Claim claim = sessions.claim(player, "ffa", () -> leaveFFA(player)).orElse(null);
 * if (claim == null) {
 *     Sessions.holder(player).ifPresent(other -> tell(player, other.plugin()));
 *     return;
 * }
 *
 * // Anything that finishes later is fenced against the claim:
 * teleport(player, spawn).thenRun(() -> claim.ifCurrent(() -> giveKit(player)));
 *
 * // And handing them back:
 * claim.release();
 * }</pre>
 *
 * <h2>The problem this replaces</h2>
 * A network of game modes used to answer "can this player join?" by asking
 * every other plugin in turn, each through its own reflective hook, and each
 * answer was a snapshot of a moment that had already passed by the time the
 * join actually ran. Three things went wrong with that, over and over:
 *
 * <ul>
 *   <li><b>Nothing was atomic.</b> Asking "are you free?" and then joining are
 *       two steps, and a player could be claimed by a second plugin in between.
 *       That is how a player who had just been matched into a duel could still
 *       walk into an arena while the duel was loading them in.</li>
 *   <li><b>Nothing was fenced.</b> Every mode does its work across ticks and
 *       threads, and the tail of that work checked a boolean that said nothing
 *       about <em>which</em> visit it belonged to. A lobby's own teleport
 *       finished after the player had already left for somewhere else and reset
 *       them in place, flight and all.</li>
 *   <li><b>Nothing owned the truth.</b> Each plugin kept its own idea of the
 *       player's state and mirrored the others'; any one of them losing the
 *       thread left the rest permanently wrong, which is why leaving one mode
 *       and entering another repeatedly ended with a player nothing would
 *       accept.</li>
 * </ul>
 *
 * <h2>What replaces it</h2>
 * One exclusive claim per player, taken atomically or refused. The claim is the
 * truth: no plugin mirrors it, and no plugin needs to ask any other plugin
 * anything. A plugin that wants a player either gets a {@link Claim} or is told
 * who has them. A plugin that has one fences its own asynchronous work against
 * it, so work belonging to a finished visit cannot touch a player who has moved
 * on.
 *
 * <h2>What is not a claim</h2>
 * Being idle is not a claim. A player standing in a lobby, or in a party that
 * has not started anything, is unclaimed and free — which is exactly what makes
 * {@link #isFree(UUID)} the single question every mode asks before it starts.
 * A claim is for anything that takes a player somewhere: a queue, a match, an
 * arena, an event, a sandbox world, a kit editor.
 *
 * <h2>Lifetime</h2>
 * A claim ends when its owner {@linkplain Claim#release() releases} it, when
 * somebody {@linkplain Claim#evict() evicts} it, when the player disconnects,
 * or when the owning plugin is disabled. The last two are handled here so that
 * a plugin that forgets cannot strand a player in a state no one holds.
 *
 * @since 1.50.0
 */
public final class Sessions {

    private static final Map<String, PluginSessions> BY_PLUGIN = new ConcurrentHashMap<>();
    private static final Map<UUID, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS = new AtomicLong();
    private static final List<Watcher> WATCHERS = new CopyOnWriteArrayList<>();

    private static volatile Debug debug;

    /** One plugin's interest in what happens to players it does not own. */
    private record Watcher(String plugin, Consumer<Claim> taken, Consumer<Claim> given) {
    }

    private Sessions() {
    }

    /**
     * Binds the module's debug view.
     *
     * <p>Called once by the library. Consumers never call this: they take a
     * view of their own with {@link #of(Plugin)}.
     *
     * @param plugin the library plugin
     */
    public static void init(@NotNull Plugin plugin) {
        debug = Debug.of(plugin);
    }

    /**
     * This plugin's view of the module.
     *
     * @param plugin the plugin
     * @return its view, the same instance every time
     */
    public static @NotNull PluginSessions of(@NotNull Plugin plugin) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), PluginSessions::new);
    }

    /**
     * Who has this player, if anyone.
     *
     * @param player the player
     * @return the claim in force, or empty if the player is free
     */
    public static @NotNull Optional<Claim> holder(@NotNull UUID player) {
        return Optional.ofNullable(CLAIMS.get(player));
    }

    /**
     * Who has this player, if anyone.
     *
     * @param player the player
     * @return the claim in force, or empty if the player is free
     */
    public static @NotNull Optional<Claim> holder(@NotNull Player player) {
        return holder(player.getUniqueId());
    }

    /**
     * Whether nothing has this player.
     *
     * <p>The one question a mode asks before it starts anything. It is true for
     * a player idling in a lobby and false for a player who is queued, loading,
     * playing, spectating, editing, or anywhere else — without the asker having
     * to know which of those it is or which plugin owns it.
     *
     * @param player the player
     * @return true if the player can be claimed
     */
    public static boolean isFree(@NotNull UUID player) {
        return !CLAIMS.containsKey(player);
    }

    /**
     * Whether nothing has this player.
     *
     * @param player the player
     * @return true if the player can be claimed
     */
    public static boolean isFree(@NotNull Player player) {
        return isFree(player.getUniqueId());
    }

    /**
     * Whether this plugin is the one holding the player.
     *
     * @param player the player
     * @param plugin the plugin name
     * @return true if that plugin holds the claim
     */
    public static boolean isHeldBy(@NotNull UUID player, @NotNull String plugin) {
        Claim claim = CLAIMS.get(player);
        return claim != null && claim.plugin().equals(plugin);
    }

    /**
     * Asks whoever has this player to give them back.
     *
     * <p>The replacement for every "make them leave whatever they are in" hook.
     * It does not need to know what the player is in, because the owner
     * registered how to undo its own hold when it took the claim.
     *
     * @param player the player
     * @return true if the player is free afterwards, including when they
     *         already were
     */
    public static boolean release(@NotNull UUID player) {
        Claim claim = CLAIMS.get(player);
        return claim == null || claim.evict();
    }

    /**
     * Asks whoever has this player to give them back.
     *
     * @param player the player
     * @return true if the player is free afterwards
     */
    public static boolean release(@NotNull Player player) {
        return release(player.getUniqueId());
    }

    /**
     * Every claim in force, for a debug command or an admin screen.
     *
     * @return a copy, safe to iterate
     */
    public static @NotNull Collection<Claim> all() {
        return List.copyOf(CLAIMS.values());
    }

    /**
     * Drops whatever claim a player has, running nothing.
     *
     * <p>For a player who left the server. Their owner's own quit handling has
     * already had its turn by the time the library calls this, so the only
     * claims it finds are the ones somebody forgot — and leaving those behind
     * would mean a returning player nothing will accept.
     *
     * @param player the player
     */
    public static void forget(@NotNull UUID player) {
        Claim gone = CLAIMS.remove(player);
        if (gone == null) return;
        if (debug != null) {
            debug.debug("[Sessions] dropped a claim its owner left behind: " + gone);
        }
        fire(gone, false);
    }

    /**
     * Drops every claim held by a plugin that is going away.
     *
     * <p>Running its handlers would call into a classloader that is being
     * dismantled, so this only clears the record. A player left mid-activity by
     * a disabled plugin is free again, which is the state the rest of the
     * server can still act on.
     *
     * @param plugin the plugin name
     */
    public static void forgetPlugin(@NotNull String plugin) {
        WATCHERS.removeIf(watcher -> watcher.plugin().equals(plugin));
        CLAIMS.values().removeIf(claim -> {
            if (!claim.plugin().equals(plugin)) return false;
            fire(claim, false);
            return true;
        });
        BY_PLUGIN.remove(plugin);
    }

    /**
     * Forgets everything, for shutdown.
     */
    public static void releaseAll() {
        CLAIMS.clear();
        BY_PLUGIN.clear();
        WATCHERS.clear();
    }

    /**
     * Asks to be told when any player is taken or given back.
     *
     * <p>What a lobby needs. A plugin whose players can be claimed out from
     * under it — hidden from the lobby, taken off the scoreboard, stripped of
     * their lobby items — used to be told by the plugin doing the claiming,
     * through a method that plugin had to remember to call. Being told here
     * means it is told about every plugin, including ones written later, and
     * cannot be forgotten.
     *
     * <p>Both callbacks receive the claim and run on whichever thread caused
     * the change, which for a join or a leave is the server thread. Keep them
     * short and do not claim from inside one.
     *
     * @param plugin the plugin watching, so the interest goes away with it
     * @param taken  called just after a player is claimed, by anyone
     * @param given  called just after a claim ends, by any means
     */
    public static void watch(@NotNull Plugin plugin,
                             @Nullable Consumer<Claim> taken,
                             @Nullable Consumer<Claim> given) {
        WATCHERS.add(new Watcher(plugin.getName(),
                taken == null ? claim -> { } : taken,
                given == null ? claim -> { } : given));
    }

    // -----------------------------------------------------------------------
    // Package-private: what PluginSessions and Claim work through.
    // -----------------------------------------------------------------------

    static @Nullable Claim current(UUID player) {
        return CLAIMS.get(player);
    }

    /**
     * Takes the claim, or reports who already has it.
     *
     * <p>Atomic on purpose. This is the single point where two plugins racing
     * for the same player is decided, and deciding it with a get followed by a
     * put is the bug the whole module exists to remove.
     */
    static @Nullable Claim open(UUID player, String plugin, String kind, @Nullable Runnable onEvict) {
        Claim taken = new Claim(player, plugin, kind, TOKENS.incrementAndGet(), onEvict);
        Claim existing = CLAIMS.putIfAbsent(player, taken);
        if (existing != null) return null;
        fire(taken, true);
        return taken;
    }

    static boolean drop(Claim claim) {
        if (!CLAIMS.remove(claim.player(), claim)) return false;
        fire(claim, false);
        return true;
    }

    /**
     * Tells the watchers, without letting one of them break the change that
     * has already happened.
     */
    private static void fire(Claim claim, boolean taken) {
        for (Watcher watcher : WATCHERS) {
            if (watcher.plugin().equals(claim.plugin())) continue;
            try {
                (taken ? watcher.taken() : watcher.given()).accept(claim);
            } catch (Throwable failed) {
                if (debug != null) {
                    debug.warn("[Sessions] " + watcher.plugin() + " failed handling " + claim + ": " + failed);
                }
            }
        }
    }
}
