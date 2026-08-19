package net.exylia.lib.nametag.internal;

import net.exylia.lib.nametag.NametagStyle;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Where a nametag goes.
 *
 * <p>Exists so the module's decisions — which team a style belongs to, when a
 * packet is worth sending, what a viewer already knows — can be tested without
 * PacketEvents, a client, or a server. The real one is
 * {@link NametagPackets}; a test installs one that records.
 */
public interface NametagSink extends AutoCloseable {

    /** Creates a team on the viewer's client with these members in it. */
    void createTeam(Player viewer, String name, NametagStyle style, Collection<String> members);

    /** Adds members to a team the viewer already knows. */
    void addToTeam(Player viewer, String name, Collection<String> members);

    /** Removes members from a team the viewer knows. */
    void removeFromTeam(Player viewer, String name, Collection<String> members);

    /** Removes a team from the viewer's client. */
    void removeTeam(Player viewer, String name);

    /** Re-sends a player's entity flags, so a glow starts or stops now. */
    void refreshFlags(Player viewer, Player target);

    /** Stops listening. Never throws. */
    @Override
    void close();
}
