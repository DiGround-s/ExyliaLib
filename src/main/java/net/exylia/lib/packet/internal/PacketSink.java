package net.exylia.lib.packet.internal;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the module sends, without saying how.
 *
 * <p>{@link PacketHooks} is the one implementation that names PacketEvents.
 * Everything that decides what to send talks to this, so those decisions can
 * be tested with a recording sink.
 */
public interface PacketSink {

    /** Despawns a player and drops them from the tab list, for one viewer. */
    void despawn(Player viewer, int entityId, UUID profile);

    /** Shows one section's worth of blocks to one viewer. */
    void blocks(Player viewer, SectionGroups.Section section, List<Location> positions,
                Map<Location, BlockData> data);

    /** Tells a client its game mode, 0–3 in vanilla order. */
    void gameMode(Player viewer, int mode);

    /** Tells a client its abilities. */
    void abilities(Player viewer, boolean invulnerable, boolean flying,
                   boolean allowFlight, float flySpeed);

    /** Stops listening. */
    void close();
}
