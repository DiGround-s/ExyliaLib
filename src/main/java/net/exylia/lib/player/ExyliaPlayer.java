package net.exylia.lib.player;

import net.exylia.lib.proxy.Proxy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * A player, whether or not they are here.
 *
 * <p>One type for the three answers a command used to need three types for:
 * somebody on this server, somebody who has played here and left, and
 * somebody on another server of the network. The plugins had a
 * {@code Player} parameter for the first, a private {@code resolve(String)}
 * for the second and a {@link net.exylia.lib.proxy.ProxyPlayer} for the
 * third, which is why the same four lines of name-to-id juggling were copied
 * into ten files and behaved slightly differently in each.
 *
 * <h2>The id is never null</h2>
 * That is the whole point of the type. Every table in the ecosystem is keyed
 * by a player's {@link UUID}, so a target that carries one can be handed to
 * any store with no branch at the call site; a target that might not carry
 * one pushes an {@code if} into every command that takes it. Resolution
 * either produces an identity or reports that it could not — see
 * {@link ExyliaPlayers}.
 *
 * <h2>What it is not</h2>
 * Not a snapshot of a player's data, and not something to hold on to. It is
 * an <em>address</em>: an id, the name that id last answered to, and where
 * the network last saw them. Read what you need from it and let it go; a
 * field of type {@code ExyliaPlayer} that outlives a command is a name that
 * will eventually be wrong.
 *
 * @param id     their unique id, which never changes
 * @param name   their name as this server last knew it
 * @param server the backend the proxy has them on, or {@code null} when they
 *               are not connected to the network, or when there is no proxy
 *               bridge to ask
 * @since 1.146.0
 */
public record ExyliaPlayer(@NotNull UUID id, @NotNull String name, @Nullable String server) {

    public ExyliaPlayer {
        if (id == null) {
            throw new IllegalArgumentException("An ExyliaPlayer needs an id; that is what makes it one.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An ExyliaPlayer needs a name: " + id);
        }
    }

    /**
     * An address for a player who is right here.
     *
     * @param player the player
     * @return their address, with this server's name unknown to it
     */
    public static @NotNull ExyliaPlayer of(@NotNull Player player) {
        return new ExyliaPlayer(player.getUniqueId(), player.getName(), null);
    }

    /**
     * An address built from an id and a name that are already known.
     *
     * @param id   their id
     * @param name their name
     * @return their address
     */
    public static @NotNull ExyliaPlayer of(@NotNull UUID id, @NotNull String name) {
        return new ExyliaPlayer(id, name, null);
    }

    /**
     * The live player, if they are on this server.
     *
     * <p>Asked of Bukkit every time rather than held, because an address
     * outlives a connection: a menu that remembered the {@code Player} it
     * opened for is the oldest leak in the ecosystem.
     *
     * @return the player, or {@code null} when they are not on this server
     */
    public @Nullable Player here() {
        try {
            return Bukkit.getPlayer(id);
        } catch (Exception noServer) {
            return null;
        }
    }

    /**
     * The live player, if they are on this server.
     *
     * @return the player, or empty
     */
    public @NotNull Optional<Player> player() {
        return Optional.ofNullable(here());
    }

    /**
     * Them as an {@link OfflinePlayer}, for the APIs that still ask for one.
     *
     * <p>The id overload of {@code getOfflinePlayer}, which reads no file and
     * asks Mojang nothing — unlike the name overload, which is why nothing in
     * this module calls that one on the main thread.
     *
     * @return them, as Bukkit models an account rather than a connection
     */
    public @NotNull OfflinePlayer offline() {
        return Bukkit.getOfflinePlayer(id);
    }

    /** Whether they are on this server right now. */
    public boolean isHere() {
        return here() != null;
    }

    /**
     * Whether the proxy has them connected to some named server.
     *
     * <p>False for a player who is genuinely offline <em>and</em> for one the
     * bridge could not be asked about, which are not the same thing: a
     * command that reports "offline" on this alone will lie on a server whose
     * proxy has no bridge. {@link #isConnected()} is the question worth
     * asking; this one is for deciding <em>where</em> to send something.
     */
    public boolean isOnNetwork() {
        return server != null && !server.isBlank();
    }

    /**
     * The backend the proxy has them on.
     *
     * @return the server's name as the proxy spells it, or empty
     */
    public @NotNull Optional<String> serverName() {
        return isOnNetwork() ? Optional.of(server) : Optional.empty();
    }

    /**
     * Whether they are playing right now, here or anywhere on the network.
     *
     * <p>Costs a set lookup: the proxy's player list is already in memory and
     * refreshed every ten seconds, so this is the cheap question to ask
     * before paying for {@link Proxy#find} to learn <em>which</em> server. Ten
     * seconds stale, which for "are they online" is the difference between a
     * correct message and a slightly early one.
     *
     * @return whether somebody is connected under this name
     */
    public boolean isConnected() {
        return isHere() || isOnNetwork() || Proxy.players().contains(name);
    }

    /**
     * Whether this address points at the same player as another.
     *
     * <p>Which is what {@link #equals(Object)} answers too. Identity is the
     * id alone: the name is what the id last answered to and the server is
     * where they last were, so comparing all three would have the same player
     * read as two after a rename or a server switch — and a
     * {@code Set<ExyliaPlayer>} hold them twice.
     *
     * @param other the other address, or {@code null}
     * @return whether both name the same player
     */
    public boolean is(@Nullable ExyliaPlayer other) {
        return other != null && id.equals(other.id);
    }

    /**
     * Whether this address points at a given player.
     *
     * @param other the player, or {@code null}
     * @return whether this address names them
     */
    public boolean is(@Nullable OfflinePlayer other) {
        return other != null && id.equals(other.getUniqueId());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExyliaPlayer player && id.equals(player.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return name + " (" + id + (isOnNetwork() ? " on " + server : "") + ")";
    }
}
