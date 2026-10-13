package net.exylia.lib.hologram.internal;

import net.exylia.lib.packet.internal.EntityClicks;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.hologram.HologramConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes display entities to a client.
 *
 * <p>The only class in the module that names PacketEvents types, so a server
 * without it never loads this one. A hologram sent this way is not an entity
 * the server knows about: it is not ticked, not saved, not in any chunk, and
 * two players can be shown different text at the same coordinates.
 *
 * <p>Entity ids come from {@link SpigotReflectionUtil#generateEntityId()},
 * which draws from the same counter the server uses, so a hologram can never
 * collide with a real entity.
 */
final class DisplayPackets implements DisplaySink {

    static final DisplayPackets INSTANCE = new DisplayPackets();

    private DisplayPackets() {
    }

    /** Returns whether PacketEvents is loaded and ready to send. */
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

    /**
     * Spawns a display and sends its full state.
     *
     * <p>Spawn and metadata go together because a display with no metadata is a
     * zero-sized invisible nothing: sending them apart is a visible flicker.
     */
    @Override
    public void spawn(Player viewer, DisplayState display, Location at, Component text,
                      DisplayState.Transform transform) {
        send(viewer, new WrapperPlayServerSpawnEntity(
                display.entityId(),
                Optional.of(UUID.randomUUID()),
                typeOf(display.kind()),
                new Vector3d(at.getX(), at.getY(), at.getZ()),
                // Which way it faces: what a FIXED hologram is read from, and what the other
                // billboards keep for the axes they do not turn.
                at.getPitch(), at.getYaw(), at.getYaw(), 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(),
                fullState(display, text, transform)));
    }

    @Override
    public void transform(Player viewer, DisplayState display, DisplayState.Transform transform,
                          int ticks) {
        // A delay of zero is what restarts the interpolation: without it the
        // client snaps to the new frame instead of gliding there.
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(), List.of(
                new EntityData<>(DisplayState.INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(DisplayState.TRANSFORMATION_INTERPOLATION_DURATION,
                        EntityDataTypes.INT, ticks),
                new EntityData<>(DisplayState.TRANSLATION, EntityDataTypes.VECTOR3F,
                        new Vector3f(transform.tx(), transform.ty(), transform.tz())),
                new EntityData<>(DisplayState.SCALE, EntityDataTypes.VECTOR3F,
                        new Vector3f(transform.sx(), transform.sy(), transform.sz())),
                new EntityData<>(DisplayState.ROTATION_LEFT, EntityDataTypes.QUATERNION,
                        new Quaternion4f(0, transform.qy(), 0, transform.qw())))));
    }

    @Override
    public void hitbox(Player viewer, int entityId, Location at, float width, float height) {
        send(viewer, new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()),
                EntityTypes.INTERACTION, new Vector3d(at.getX(), at.getY(), at.getZ()),
                0f, 0f, 0f, 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(DisplayState.INTERACTION_WIDTH, EntityDataTypes.FLOAT, width),
                new EntityData<>(DisplayState.INTERACTION_HEIGHT, EntityDataTypes.FLOAT, height),
                new EntityData<>(DisplayState.INTERACTION_RESPONSIVE, EntityDataTypes.BOOLEAN, true))));
    }

    /**
     * Starts telling {@code clicked} about clicks on a hologram's hitbox.
     *
     * <p>Every packet aimed at a hitbox is kept from the server, which has never
     * heard of that id.
     *
     * @return what to hand to {@link #stopListening}
     */
    static Object listen(HitboxClicks clicked) {
        PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                EntityClicks.Press press = EntityClicks.read(event);
                if (press == null || !(event.getPlayer() instanceof Player player)
                        || !HologramRuntime.isHitbox(press.entityId())) {
                    return;
                }
                event.setCancelled(true);
                if (press.counts()) {
                    boolean sneaking = press.sneaking() != null ? press.sneaking() : player.isSneaking();
                    clicked.clicked(player, press.entityId(),
                            net.exylia.lib.interaction.Click.of(press.attack(), sneaking));
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    /** Told about a click on a hologram's hitbox. */
    @FunctionalInterface
    interface HitboxClicks {
        void clicked(Player player, int entityId, net.exylia.lib.interaction.Click click);
    }

    static void stopListening(Object listener) {
        try {
            PacketEvents.getAPI().getEventManager()
                    .unregisterListener((PacketListenerAbstract) listener);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    /** Sends only the text, for a line whose value changed. */
    @Override
    public void text(Player viewer, DisplayState display, Component text) {
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(),
                List.of(new EntityData<>(DisplayState.TEXT,
                        EntityDataTypes.ADV_COMPONENT, text))));
    }

    /** Moves a display without respawning it. */
    @Override
    public void teleport(Player viewer, int entityId, Location to) {
        send(viewer, new WrapperPlayServerEntityTeleport(entityId,
                new Vector3d(to.getX(), to.getY(), to.getZ()), to.getYaw(), to.getPitch(), false));
    }

    /** Makes displays ride an entity so the client moves them along with it. */
    @Override
    public void mount(Player viewer, int vehicleId, int[] passengers) {
        send(viewer, new WrapperPlayServerSetPassengers(vehicleId, passengers));
    }

    /** Removes displays from a client. */
    @Override
    public void destroy(Player viewer, int[] entityIds) {
        send(viewer, new WrapperPlayServerDestroyEntities(entityIds));
    }

    /**
     * Every metadata entry a display needs, in one packet.
     *
     * <p>Only what differs from the client's own default is written: the fewer
     * entries, the smaller the packet, and this one is sent once per viewer per
     * hologram.
     */
    private static List<EntityData<?>> fullState(DisplayState display, Component text,
                                                DisplayState.Transform transform) {
        HologramConfig.Properties properties = display.properties();
        List<EntityData<?>> data = new ArrayList<>(12);

        data.add(new EntityData<>(DisplayState.BILLBOARD, EntityDataTypes.BYTE, display.billboard()));
        data.add(new EntityData<>(DisplayState.SCALE, EntityDataTypes.VECTOR3F,
                new Vector3f(transform.sx(), transform.sy(), transform.sz())));
        if (transform.hasTranslation()) {
            data.add(new EntityData<>(DisplayState.TRANSLATION, EntityDataTypes.VECTOR3F,
                    new Vector3f(transform.tx(), transform.ty(), transform.tz())));
        }
        if (transform.hasRotation()) {
            data.add(new EntityData<>(DisplayState.ROTATION_LEFT, EntityDataTypes.QUATERNION,
                    new Quaternion4f(0, transform.qy(), 0, transform.qw())));
        }
        Integer glow = display.glowRgb();
        if (glow != null) {
            data.add(new EntityData<>(DisplayState.ENTITY_FLAGS, EntityDataTypes.BYTE,
                    DisplayState.FLAG_GLOWING));
            data.add(new EntityData<>(DisplayState.GLOW_COLOR, EntityDataTypes.INT, glow));
        }

        int brightness = display.packedBrightness();
        if (brightness >= 0) {
            data.add(new EntityData<>(DisplayState.BRIGHTNESS, EntityDataTypes.INT, brightness));
        }

        switch (display.kind()) {
            case TEXT -> {
                data.add(new EntityData<>(DisplayState.TEXT, EntityDataTypes.ADV_COMPONENT, text));
                data.add(new EntityData<>(DisplayState.LINE_WIDTH, EntityDataTypes.INT, properties.lineWidth()));
                data.add(new EntityData<>(DisplayState.BACKGROUND_COLOR, EntityDataTypes.INT,
                        display.backgroundArgb()));
                data.add(new EntityData<>(DisplayState.TEXT_OPACITY, EntityDataTypes.BYTE,
                        (byte) properties.textOpacity()));
                data.add(new EntityData<>(DisplayState.TEXT_FLAGS, EntityDataTypes.BYTE, display.textFlags()));
            }
            case ITEM -> data.add(new EntityData<>(DisplayState.ITEM, EntityDataTypes.ITEMSTACK,
                    SpigotConversionUtil.fromBukkitItemStack(
                            new org.bukkit.inventory.ItemStack(display.material()))));
            case BLOCK -> data.add(new EntityData<>(DisplayState.BLOCK_STATE, EntityDataTypes.BLOCK_STATE,
                    SpigotConversionUtil.fromBukkitBlockData(
                            display.material().createBlockData()).getGlobalId()));
        }
        return data;
    }

    private static EntityType typeOf(HologramConfig.Kind kind) {
        return switch (kind) {
            case ITEM -> EntityTypes.ITEM_DISPLAY;
            case BLOCK -> EntityTypes.BLOCK_DISPLAY;
            default -> EntityTypes.TEXT_DISPLAY;
        };
    }

    private static void send(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }
}
