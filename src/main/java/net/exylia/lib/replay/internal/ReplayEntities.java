package net.exylia.lib.replay.internal;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.packet.internal.Broadcast;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The things in an arena that are not people, drawn on one client.
 *
 * <p>An arrow in flight, a crystal on the ground, a block of TNT counting down.
 * Far simpler than a packet player: there is no profile to announce, no skin to
 * hang on it and no player list entry to withdraw afterwards &mdash; a type and
 * a position is the whole of it, and the client draws the rest from its own
 * model.
 *
 * <p>The only class in the replay module that names PacketEvents, so a server
 * without it never loads this one.
 */
@ApiStatus.Internal
final class ReplayEntities {

    private ReplayEntities() {
    }

    /** Reserves an id that cannot collide with a real entity. */
    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    /**
     * The protocol's name for a Bukkit entity type.
     *
     * @param name the type, as Bukkit names it
     * @return the type, or {@code null} when this server has never heard of it
     */
    static EntityType typeOf(String name) {
        try {
            org.bukkit.entity.EntityType bukkit = org.bukkit.entity.EntityType.valueOf(name);
            return SpigotConversionUtil.fromBukkitEntityType(bukkit);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            // A recording from a server with a mod or a newer version. Drawn as
            // nothing rather than taking the rest of the replay down.
            return null;
        }
    }

    /** Whether a type is one this server can be asked to draw. */
    static boolean isKnown(EntityType type) {
        return type != null && type != EntityTypes.PLAYER;
    }

    /** Draws one, where it was. */
    static void spawn(List<Player> viewers, int entityId, EntityType type, Location at) {
        PacketWrapper<?> body = new WrapperPlayServerSpawnEntity(
                entityId, Optional.of(UUID.randomUUID()), type,
                new Vector3d(at.getX(), at.getY(), at.getZ()),
                at.getPitch(), at.getYaw(), at.getYaw(), 0, Optional.empty());
        Broadcast.send(viewers, body);
    }

    /**
     * Puts one where it is now.
     *
     * <p>Outright rather than a step, and that is the right call here: an arrow
     * moves further in one tick than a relative move can carry, and a client
     * that smoothed a pearl's flight would draw it arriving somewhere it never
     * was.
     */
    static void teleport(List<Player> viewers, int entityId, Location to) {
        PacketWrapper<?> jump = new WrapperPlayServerEntityTeleport(entityId,
                new Vector3d(to.getX(), to.getY(), to.getZ()), to.getYaw(), to.getPitch(), false);
        Broadcast.send(viewers, jump);
    }

    /** Takes one off the client. */
    static void destroy(List<Player> viewers, int entityId) {
        Broadcast.send(viewers, new WrapperPlayServerDestroyEntities(entityId));
    }
}
