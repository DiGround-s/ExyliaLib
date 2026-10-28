package net.exylia.lib.camera.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.packet.internal.Broadcast;
import net.kyori.adventure.text.Component;
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
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int LEFT_ROTATION = 13;
    private static final int BILLBOARD = 15;
    private static final int BRIGHTNESS = 16;
    private static final int TEXT = 23;
    private static final int LINE_WIDTH = 24;
    private static final int BACKGROUND = 25;
    private static final int TEXT_FLAGS = 27;
    private static final byte BILLBOARD_CENTER = 3;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

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

    @Override
    public void spawnPanel(List<Player> viewers, int entityId, CameraPath.Frame at, CameraOverlay.Panel panel) {
        send(viewers, new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()),
                EntityTypes.TEXT_DISPLAY, new Vector3d(at.x(), at.y(), at.z()),
                0f, 0f, 0f, 0, Optional.empty()));
        float distance = panel.distance();
        // Half a turn about the view axis: the panel then grows downwards from
        // its origin, so a bottom bar's edge is exactly where it was put.
        Quaternion4f turn = panel.flipped() ? new Quaternion4f(0f, 0f, 1f, 0f) : new Quaternion4f(0f, 0f, 0f, 1f);
        send(viewers, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F,
                        new Vector3f(0f, panel.y() * distance, -distance)),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F,
                        new Vector3f(panel.scaleX() * distance, panel.scaleY() * distance, 1f)),
                new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION, turn),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                // Lit by nothing in the world: a subtitle filmed at night is
                // still a subtitle.
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, panel.text()),
                new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, panel.lineWidth()),
                new EntityData<>(BACKGROUND, EntityDataTypes.INT, panel.background()),
                new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, (byte) (panel.shadow() ? 0x01 : 0)))));
    }

    @Override
    public void mount(List<Player> viewers, int vehicleId, int[] passengers) {
        send(viewers, new WrapperPlayServerSetPassengers(vehicleId, passengers));
    }

    @Override
    public void text(List<Player> viewers, int entityId, Component text) {
        send(viewers, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text))));
    }

    @Override
    public void background(List<Player> viewers, int entityId, int argb) {
        send(viewers, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(BACKGROUND, EntityDataTypes.INT, argb))));
    }

    /** Sends one packet to everyone watching, written once; see {@link Broadcast}. */
    private static void send(List<Player> viewers, PacketWrapper<?> packet) {
        Broadcast.send(viewers, packet);
    }
}
