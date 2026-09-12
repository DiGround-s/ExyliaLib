package net.exylia.lib.player;

import net.exylia.lib.player.internal.PlayerRuntime;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Finding a player who may not be here.
 *
 * <p>Every table in the ecosystem is keyed by a player's {@link UUID} and
 * every command is typed on their name, so the one thing standing between a
 * plugin and working on somebody who is offline is turning that name into
 * that id. Ten files were doing it privately, in four slightly different
 * ways, two of them from the main thread. This is the one way.
 *
 * <h2>Which method to call</h2>
 * <table>
 *   <caption>The two questions worth asking</caption>
 *   <tr><th>Question</th><th>Method</th><th>Cost</th></tr>
 *   <tr>
 *     <td>Who is this, if we already know?</td>
 *     <td>{@link #cached(String)}</td>
 *     <td>Memory. Safe anywhere, including inside a tab completion.</td>
 *   </tr>
 *   <tr>
 *     <td>Who is this, whatever it takes?</td>
 *     <td>{@link #resolve(String)}, {@link #then}</td>
 *     <td>Up to a proxy round trip and a Mojang request. Never blocks.</td>
 *   </tr>
 * </table>
 *
 * <p>{@code cached} answers for anybody online here and anybody who has ever
 * played here, which on a live server is nearly everybody a command is ever
 * pointed at. {@code resolve} adds the players on the rest of the network and
 * the ones nobody here has seen.
 *
 * <h2>In a command</h2>
 * Declare the parameter and it resolves itself. The two parameter types live
 * in each plugin as {@code PlayerArguments}, twenty lines over the methods
 * here, because Lamp is loaded once per plugin and no Lamp object may cross
 * that boundary — see {@code docs/players.md}:
 *
 * <pre>{@code
 * // Somebody this server knows. Rejected before the handler runs otherwise.
 * @Command("stats")
 * public void stats(Player viewer, ExyliaPlayer target) {
 *     stats.of(target.id()).thenAccept(...);
 * }
 *
 * // Somebody who may be anywhere. Looked up in the handler.
 * @Command("punish")
 * public void punish(Player staff, PlayerTarget target, String reason) {
 *     target.then(staff, found -> punishments.apply(found.id(), reason));
 * }
 * }</pre>
 *
 * <h2>Threading</h2>
 * Nothing here blocks and nothing here touches the world. {@code resolve}
 * completes off the server thread, so {@link #then} exists to hop back; a
 * future handled by hand needs {@code Tasks} before it touches anything in
 * the game, as everywhere else in the library.
 *
 * @since 1.146.0
 */
public final class ExyliaPlayers {

    private ExyliaPlayers() {
        throw new AssertionError("No instances.");
    }

    /**
     * The address of a player who is here.
     *
     * @param player the player
     * @return their address
     */
    public static @NotNull ExyliaPlayer of(@NotNull Player player) {
        PlayerRuntime.remember(player);
        return ExyliaPlayer.of(player);
    }

    /**
     * The address of a player whose id and name are both already known.
     *
     * <p>Remembers the pairing, so a plugin reading its own rows teaches the
     * directory the names it already has: a leaderboard loaded on start makes
     * every name on it resolvable without a single lookup.
     *
     * @param id   their id
     * @param name their name
     * @return their address
     */
    public static @NotNull ExyliaPlayer of(@NotNull UUID id, @NotNull String name) {
        PlayerRuntime.remember(id, name);
        return ExyliaPlayer.of(id, name);
    }

    /**
     * The address of an {@link OfflinePlayer}, when Bukkit knows their name.
     *
     * @param player the account
     * @return their address, or empty when even Bukkit has no name for them
     */
    public static @NotNull Optional<ExyliaPlayer> of(@NotNull OfflinePlayer player) {
        String name = player.getName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(of(player.getUniqueId(), name));
    }

    /**
     * Whoever this names, if that can be answered from memory.
     *
     * <p>Online here, then the directory, then the server's user cache. No
     * file is read, no request goes out, and nothing blocks: safe from a tab
     * completion, a placeholder, a packet handler.
     *
     * @param nameOrId a player name, or a uuid as text
     * @return their address, or {@code null} when a lookup would be needed
     */
    public static @Nullable ExyliaPlayer cached(@NotNull String nameOrId) {
        return PlayerRuntime.cached(nameOrId);
    }

    /**
     * Whoever this id belongs to, if their name is known from memory.
     *
     * @param id their id
     * @return their address, or {@code null} when no name for it is known
     */
    public static @Nullable ExyliaPlayer cached(@NotNull UUID id) {
        return PlayerRuntime.cached(id);
    }

    /**
     * The name an id last answered to, from memory only.
     *
     * <p>Online here, then the directory, then the server's profile cache.
     * What a menu listing rows by id should call: it puts a name on a hundred
     * stored ids without leaving the thread it was drawn on.
     *
     * @param id their id
     * @return their name, or {@code null} when nothing here knows it
     */
    public static @Nullable String nameOf(@NotNull UUID id) {
        return PlayerRuntime.nameOf(id);
    }

    /**
     * The name an id last answered to, or a stand-in.
     *
     * @param id       their id
     * @param fallback what to show when no name is known
     * @return their name, or {@code fallback}
     */
    public static @NotNull String nameOr(@NotNull UUID id, @NotNull String fallback) {
        String name = PlayerRuntime.nameOf(id);
        return name != null ? name : fallback;
    }

    /**
     * Whoever this names, going out to the network and to Mojang if need be.
     *
     * <p>Never fails exceptionally: a player nobody can find is an empty
     * answer, not an error. Completes off the server thread — see
     * {@link #then} for the version that hops back before running anything.
     *
     * @param nameOrId a player name, or a uuid as text
     * @return their address, or empty
     */
    public static @NotNull CompletableFuture<Optional<ExyliaPlayer>> resolve(@NotNull String nameOrId) {
        return PlayerRuntime.resolve(nameOrId);
    }

    /**
     * Whoever this id belongs to, going out to the network if need be.
     *
     * @param id their id
     * @return their address, or empty when not even their name can be found
     */
    public static @NotNull CompletableFuture<Optional<ExyliaPlayer>> resolve(@NotNull UUID id) {
        return PlayerRuntime.resolve(id);
    }

    /**
     * Resolves a target and runs an action where it is safe to touch the
     * world, telling the sender when nobody answers.
     *
     * <p>The whole of what a command needs:
     *
     * <pre>{@code
     * ExyliaPlayers.then(sender, name, target -> {
     *     // On the server thread. The sender is still here.
     *     history.open(sender, target);
     * });
     * }</pre>
     *
     * @param sender   who asked, and who is told when nobody is found
     * @param nameOrId what they typed
     * @param action   what to do with the target
     */
    public static void then(@NotNull CommandSender sender,
                            @NotNull String nameOrId,
                            @NotNull Consumer<ExyliaPlayer> action) {
        PlayerRuntime.then(sender, nameOrId, action);
    }

    /**
     * The same, when the plugin has its own wording for nobody found.
     *
     * <p>For a command whose "never played here" line is already a key in the
     * plugin's own {@code messages.yml}: the lookup and the threading are the
     * library's, the sentence stays the plugin's. Both branches run on the
     * sender's thread.
     *
     * @param sender   who asked
     * @param nameOrId what they typed
     * @param action   what to do with the target
     * @param notFound what to do when nobody answers
     */
    public static void then(@NotNull CommandSender sender,
                            @NotNull String nameOrId,
                            @NotNull Consumer<ExyliaPlayer> action,
                            @NotNull Runnable notFound) {
        PlayerRuntime.then(sender, nameOrId, action, notFound);
    }

    /**
     * Tells a sender that nobody answers to a name, in the library's words.
     *
     * <p>Exposed for the commands that resolve by hand; {@link #then} sends
     * it for you.
     *
     * @param sender who is told
     * @param name   what they typed
     */
    public static void notFound(@NotNull CommandSender sender, @NotNull String name) {
        PlayerRuntime.notFound(sender, name);
    }

    /**
     * Records a name against an id, so the cheap tiers know it from now on.
     *
     * <p>Worth calling when a plugin learns a pairing the server has no other
     * way to know — a row from its own database, an answer from an external
     * service. Joins are recorded by the library already.
     *
     * @param id   their id
     * @param name their name
     */
    public static void remember(@NotNull UUID id, @NotNull String name) {
        PlayerRuntime.remember(id, name);
    }

    /**
     * Every name worth offering as a tab completion: this server's players
     * and the network's.
     *
     * <p>Synchronous, from memory. The registered parameter types already
     * suggest from it, so this is for a command that takes a name as plain
     * text and wants the same list.
     *
     * @return the names, compared case-insensitively
     */
    public static @NotNull Set<String> names() {
        return PlayerRuntime.names();
    }
}
