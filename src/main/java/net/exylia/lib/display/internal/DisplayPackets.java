package net.exylia.lib.display.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.Rotation;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes display entities to a client.
 *
 * <p>The only class in the module that names PacketEvents, so a server without
 * it never loads this one. A display sent this way is not an entity the server
 * knows about: it is not ticked, not saved, not in any chunk, and two players
 * standing together can be shown different effects at the same coordinates.
 *
 * <h2>The interpolation fields are the point</h2>
 * Indexes 8, 9 and 10 tell the client to draw its own frames between the pose
 * it holds and the one being sent. Without them a display moves in server
 * ticks, which is the twenty-frames-a-second look this module exists to
 * replace; with them a two-second animation is about six packets and runs at
 * the viewer's frame rate.
 */
final class DisplayPackets implements DisplaySink {

    static final DisplayPackets INSTANCE = new DisplayPackets();

    /** Shared entity metadata, from the vanilla protocol. */
    private static final int ENTITY_FLAGS = 0;
    private static final byte FLAG_GLOWING = 0x40;

    /** Display metadata, from the vanilla protocol. */
    private static final int INTERPOLATION_DELAY = 8;
    private static final int TRANSFORMATION_DURATION = 9;
    private static final int POS_ROT_DURATION = 10;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int LEFT_ROTATION = 13;
    private static final int BILLBOARD = 15;
    private static final int BRIGHTNESS = 16;
    private static final int GLOW_COLOR = 22;

    /** Kind-specific metadata, all sharing index 23. */
    private static final int ITEM = 23;
    private static final int ITEM_TRANSFORM = 24;
    private static final int BLOCK_STATE = 23;
    private static final int TEXT = 23;
    private static final int TEXT_FLAGS = 27;

    /** See-through and no background: text in an effect is a label, not a sign. */
    private static final byte TEXT_SEE_THROUGH_NO_BACKGROUND = 0x02 | 0x04;

    private DisplayPackets() {
    }

    /** Whether PacketEvents is loaded and ready to send. */
    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reserves an entity id that cannot collide with a real entity. */
    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    @Override
    public void spawn(List<Player> viewers, int entityId, DisplayModel model,
                      Location at, DisplayKeyframe pose) {
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(UUID.randomUUID()),
                typeOf(model.kind()),
                new Vector3d(at.getX(), at.getY(), at.getZ()),
                0f, 0f, 0f, 0, Optional.empty());
        WrapperPlayServerEntityMetadata state =
                new WrapperPlayServerEntityMetadata(entityId, fullState(model, pose));
        for (Player viewer : viewers) {
            send(viewer, spawn);
            send(viewer, state);
        }
    }

    @Override
    public void pose(List<Player> viewers, int entityId, DisplayKeyframe pose, int overTicks) {
        List<EntityData<?>> data = new ArrayList<>(6);
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, overTicks));
        data.add(new EntityData<>(POS_ROT_DURATION, EntityDataTypes.INT, overTicks));
        addTransform(data, pose);
        WrapperPlayServerEntityMetadata packet =
                new WrapperPlayServerEntityMetadata(entityId, data);
        for (Player viewer : viewers) {
            send(viewer, packet);
        }
    }

    @Override
    public void destroy(List<Player> viewers, int entityId) {
        WrapperPlayServerDestroyEntities packet =
                new WrapperPlayServerDestroyEntities(entityId);
        for (Player viewer : viewers) {
            send(viewer, packet);
        }
    }

    /**
     * Everything a display needs to be drawn, in one packet.
     *
     * <p>The first pose is sent with no interpolation: there is nothing to
     * interpolate from, and a duration here would make every effect fade in
     * from the middle of the world.
     */
    private static List<EntityData<?>> fullState(DisplayModel model, DisplayKeyframe pose) {
        List<EntityData<?>> data = new ArrayList<>(10);

        if (model.glowArgb() >= 0) {
            // The colour override is ignored unless the entity is glowing, so
            // the flag and the colour always travel together.
            data.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_GLOWING));
            data.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, model.glowArgb()));
        }
        data.add(new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, model.billboard()));
        if (model.brightness() >= 0) {
            int level = Math.clamp(model.brightness(), 0, 15);
            // Block light in the low bits, sky light in the high ones.
            data.add(new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, (level << 4) | (level << 20)));
        }
        addTransform(data, pose);

        switch (model.kind()) {
            case ITEM -> {
                data.add(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK,
                        SpigotConversionUtil.fromBukkitItemStack(model.item())));
                data.add(new EntityData<>(ITEM_TRANSFORM, EntityDataTypes.BYTE,
                        model.itemTransform()));
            }
            case BLOCK -> data.add(new EntityData<>(BLOCK_STATE, EntityDataTypes.BLOCK_STATE,
                    SpigotConversionUtil.fromBukkitBlockData(model.block()).getGlobalId()));
            case TEXT -> {
                data.add(new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, model.text()));
                data.add(new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE,
                        TEXT_SEE_THROUGH_NO_BACKGROUND));
            }
        }
        return data;
    }

    private static void addTransform(List<EntityData<?>> data, DisplayKeyframe pose) {
        data.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F,
                new Vector3f(pose.x(), pose.y(), pose.z())));
        data.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F,
                new Vector3f(pose.scaleX(), pose.scaleY(), pose.scaleZ())));
        Rotation rotation = pose.rotation();
        data.add(new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION,
                new Quaternion4f(rotation.x(), rotation.y(), rotation.z(), rotation.w())));
    }

    private static EntityType typeOf(DisplayModel.Kind kind) {
        return switch (kind) {
            case BLOCK -> EntityTypes.BLOCK_DISPLAY;
            case TEXT -> EntityTypes.TEXT_DISPLAY;
            default -> EntityTypes.ITEM_DISPLAY;
        };
    }

    /**
     * Sends one packet, unless the player has already gone.
     *
     * <p>An effect outlives the moment it was triggered by design, and a player
     * can quit inside that window. Their connection is gone but this module's
     * list of viewers is not, and writing to it is an exception nobody caused.
     */
    private static void send(Player viewer, PacketWrapper<?> packet) {
        if (!viewer.isOnline()) {
            return;
        }
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
        } catch (Throwable gone) {
            // Disconnected between the check and the write. Nothing to do and
            // nothing worth logging: the client that would have drawn it is not
            // there any more.
        }
    }
}
