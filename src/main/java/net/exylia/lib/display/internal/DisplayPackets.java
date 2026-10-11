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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.packet.internal.Broadcast;
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
    private static final int VIEW_RANGE = 17;
    private static final int SHADOW_RADIUS = 18;
    private static final int SHADOW_STRENGTH = 19;
    private static final int CULL_WIDTH = 20;
    private static final int CULL_HEIGHT = 21;
    private static final int GLOW_COLOR = 22;

    /** Kind-specific metadata, all sharing index 23. */
    private static final int ITEM = 23;
    private static final int ITEM_TRANSFORM = 24;
    private static final int BLOCK_STATE = 23;
    private static final int TEXT = 23;
    private static final int LINE_WIDTH = 24;
    private static final int BACKGROUND = 25;
    private static final int TEXT_OPACITY = 26;
    private static final int TEXT_FLAGS = 27;

    /** A firework rocket's item, which carries its explosions. */
    private static final int FIREWORK_ITEM = 8;

    /** The entity event that makes a rocket burst on the client. */
    private static final byte FIREWORK_EXPLODES = 17;

    private static final byte FLAG_SHADOW = 0x01;
    private static final byte FLAG_SEE_THROUGH = 0x02;
    private static final byte FLAG_DEFAULT_BACKGROUND = 0x04;
    private static final byte ALIGN_LEFT = 0x08;
    private static final byte ALIGN_RIGHT = 0x10;

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
        send(viewers, spawn);
        if (!model.isPerViewer()) {
            send(viewers, new WrapperPlayServerEntityMetadata(entityId, fullState(model, pose, null)));
            return;
        }
        for (Player viewer : viewers) {
            Broadcast.send(viewer, new WrapperPlayServerEntityMetadata(entityId,
                    fullState(model, pose, viewer)));
        }
    }

    @Override
    public void firework(List<Player> viewers, int entityId, Location at,
                         List<org.bukkit.FireworkEffect> effects) {
        org.bukkit.inventory.ItemStack rocket =
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.FIREWORK_ROCKET);
        if (rocket.getItemMeta() instanceof org.bukkit.inventory.meta.FireworkMeta meta) {
            meta.addEffects(effects);
            rocket.setItemMeta(meta);
        }
        send(viewers, new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()),
                EntityTypes.FIREWORK_ROCKET, new Vector3d(at.getX(), at.getY(), at.getZ()),
                0f, 0f, 0f, 0, Optional.empty()));
        send(viewers, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(FIREWORK_ITEM, EntityDataTypes.ITEMSTACK,
                        SpigotConversionUtil.fromBukkitItemStack(rocket)))));
        send(viewers, new WrapperPlayServerEntityStatus(entityId, FIREWORK_EXPLODES));
        send(viewers, new WrapperPlayServerDestroyEntities(entityId));
    }

    @Override
    public void restyle(List<Player> viewers, int entityId, DisplayModel model) {
        if (!model.isPerViewer()) {
            send(viewers, new WrapperPlayServerEntityMetadata(entityId, style(model, null, true)));
            return;
        }
        for (Player viewer : viewers) {
            Broadcast.send(viewer, new WrapperPlayServerEntityMetadata(entityId,
                    style(model, viewer, true)));
        }
    }

    @Override
    public void pose(List<Player> viewers, int entityId, DisplayModel model,
                     DisplayKeyframe pose, int overTicks) {
        List<EntityData<?>> data = new ArrayList<>(6);
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, overTicks));
        data.add(new EntityData<>(POS_ROT_DURATION, EntityDataTypes.INT, overTicks));
        addTransform(data, pose, model.kind());
        WrapperPlayServerEntityMetadata packet =
                new WrapperPlayServerEntityMetadata(entityId, data);
        send(viewers, packet);
    }

    @Override
    public void mount(List<Player> viewers, int vehicleId, int[] passengers) {
        send(viewers, new WrapperPlayServerSetPassengers(vehicleId, passengers));
    }

    @Override
    public void destroy(List<Player> viewers, int entityId) {
        WrapperPlayServerDestroyEntities packet =
                new WrapperPlayServerDestroyEntities(entityId);
        send(viewers, packet);
    }

    /**
     * Everything a display needs to be drawn, in one packet.
     *
     * <p>The first pose is sent with no interpolation: there is nothing to
     * interpolate from, and a duration here would make every effect fade in
     * from the middle of the world.
     */
    private static List<EntityData<?>> fullState(DisplayModel model, DisplayKeyframe pose,
                                                 Player viewer) {
        List<EntityData<?>> data = style(model, viewer, false);
        data.add(new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, model.billboard()));
        addTransform(data, pose, model.kind());
        return data;
    }

    /**
     * What a display looks like, apart from where it is.
     *
     * <p>{@code explicit} is a restyle: a field the model no longer sets has to
     * be sent back to its default, because the client still holds the old
     * value. A spawn leaves defaults out, since the client starts on them.
     */
    private static List<EntityData<?>> style(DisplayModel model, Player viewer, boolean explicit) {
        List<EntityData<?>> data = new ArrayList<>(12);
        if (model.glowArgb() >= 0) {
            // The colour override is ignored unless the entity is glowing, so
            // the flag and the colour always travel together.
            data.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_GLOWING));
            data.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, model.glowArgb()));
        } else if (explicit) {
            data.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, (byte) 0));
            data.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, -1));
        }
        if (model.brightness() >= 0) {
            int level = Math.clamp(model.brightness(), 0, 15);
            // Block light in the low bits, sky light in the high ones.
            data.add(new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, (level << 4) | (level << 20)));
        } else if (explicit) {
            data.add(new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, -1));
        }
        addFloat(data, VIEW_RANGE, model.viewRange(), 1f, explicit);
        addFloat(data, SHADOW_RADIUS, model.shadowRadius(), 0f, explicit);
        addFloat(data, SHADOW_STRENGTH, model.shadowStrength(), 1f, explicit);
        addFloat(data, CULL_WIDTH, model.cullWidth(), 0f, explicit);
        addFloat(data, CULL_HEIGHT, model.cullHeight(), 0f, explicit);

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
                data.add(new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, model.textFor(viewer)));
                if (model.lineWidth() >= 0 || explicit) {
                    data.add(new EntityData<>(LINE_WIDTH, EntityDataTypes.INT,
                            model.lineWidth() >= 0 ? model.lineWidth() : 200));
                }
                Integer background = model.background();
                if (background != null || explicit) {
                    data.add(new EntityData<>(BACKGROUND, EntityDataTypes.INT,
                            background != null ? background : 0x40000000));
                }
                if (model.opacity() >= 0 || explicit) {
                    data.add(new EntityData<>(TEXT_OPACITY, EntityDataTypes.BYTE,
                            (byte) (model.opacity() >= 0 ? model.opacity() : -1)));
                }
                data.add(new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, textFlags(model)));
            }
        }
        return data;
    }

    /** The text flags byte; the defaults are see-through on the client's own panel. */
    static byte textFlags(DisplayModel model) {
        int flags = 0;
        if (model.textShadow()) {
            flags |= FLAG_SHADOW;
        }
        if (model.seeThrough()) {
            flags |= FLAG_SEE_THROUGH;
        }
        if (model.background() == null) {
            flags |= FLAG_DEFAULT_BACKGROUND;
        }
        flags |= switch (model.align()) {
            case LEFT -> ALIGN_LEFT;
            case RIGHT -> ALIGN_RIGHT;
            case CENTER -> 0;
        };
        return (byte) flags;
    }

    private static void addFloat(List<EntityData<?>> data, int index, float value, float fallback,
                                 boolean explicit) {
        if (value >= 0f) {
            data.add(new EntityData<>(index, EntityDataTypes.FLOAT, value));
        } else if (explicit) {
            data.add(new EntityData<>(index, EntityDataTypes.FLOAT, fallback));
        }
    }

    /**
     * The pose, as the three transform fields the protocol carries.
     *
     * <p>A block display is drawn with its north-west-bottom corner at the
     * entity, and every other display is drawn from its centre. Left alone that
     * makes a ring of blocks sit half a block off in three directions, and a
     * block that grows appear to slide as it does. Half its size is therefore
     * taken back off &mdash; turned first, so a spinning block turns on itself
     * rather than orbiting the point it was put at.
     */
    private static void addTransform(List<EntityData<?>> data, DisplayKeyframe pose,
                                     DisplayModel.Kind kind) {
        float x = pose.x();
        float y = pose.y();
        float z = pose.z();
        if (kind == DisplayModel.Kind.BLOCK) {
            float[] centre = pose.rotation().apply(new float[]{
                    -pose.scaleX() / 2f, -pose.scaleY() / 2f, -pose.scaleZ() / 2f});
            x += centre[0];
            y += centre[1];
            z += centre[2];
        }
        data.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F,
                new Vector3f(x, y, z)));
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

    /** Sends one packet to everyone watching, written once; see {@link Broadcast}. */
    private static void send(List<Player> viewers, PacketWrapper<?> packet) {
        Broadcast.send(viewers, packet);
    }
}
