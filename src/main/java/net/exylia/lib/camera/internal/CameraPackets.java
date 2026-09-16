package net.exylia.lib.camera.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.packet.internal.Broadcast;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes a camera to a client.
 *
 * <p>The only class in the module that names PacketEvents, so a server without
 * it never loads this one.
 *
 * <h2>Why the camera is a display</h2>
 * A client renders from whichever entity it was told to, at that entity's eye.
 * An item display is the one entity with no eye height at all, so the camera is
 * exactly where the module put it rather than a version-dependent fraction of a
 * hitbox above it — and it is drawn as nothing, because a display with no item
 * has nothing to draw.
 *
 * <p>It also interpolates. A display told how long it has to reach a position
 * draws its own frames getting there, the same field every other display in the
 * library animates with, which is what keeps a camera from moving at the
 * server's tick rate while somebody is looking straight down it.
 *
 * <p>ponytail: if a client ever refuses to render through a display, this is a
 * one-line change to a marker armor stand — with a hitbox, a real eye height to
 * subtract, and the tick-rate motion back.
 */
final class CameraPackets implements CameraSink {

    static final CameraPackets INSTANCE = new CameraPackets();

    /** Display metadata, from the vanilla protocol; see the display module. */
    private static final int INTERPOLATION_DELAY = 8;
    private static final int POS_ROT_DURATION = 10;

    private CameraPackets() {
    }

    /** Whether PacketEvents is loaded and ready to send. */
    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    @Override
    public void spawn(List<Player> viewers, int entityId, CameraPath.Frame at, int holdTicks) {
        send(viewers, new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(UUID.randomUUID()),
                EntityTypes.ITEM_DISPLAY,
                new Vector3d(at.x(), at.y(), at.z()),
                at.pitch(), at.yaw(), at.yaw(), 0, Optional.empty()));
        send(viewers, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(POS_ROT_DURATION, EntityDataTypes.INT, holdTicks))));
    }

    @Override
    public void move(List<Player> viewers, int entityId, CameraPath.Frame to) {
        send(viewers, new WrapperPlayServerEntityTeleport(entityId,
                new Vector3d(to.x(), to.y(), to.z()), to.yaw(), to.pitch(), false));
    }

    @Override
    public void look(List<Player> viewers, int entityId) {
        send(viewers, new WrapperPlayServerCamera(entityId));
    }

    @Override
    public void destroy(List<Player> viewers, int entityId) {
        send(viewers, new WrapperPlayServerDestroyEntities(entityId));
    }

    /** Sends one packet to everyone watching, written once; see {@link Broadcast}. */
    private static void send(List<Player> viewers, PacketWrapper<?> packet) {
        Broadcast.send(viewers, packet);
    }
}
