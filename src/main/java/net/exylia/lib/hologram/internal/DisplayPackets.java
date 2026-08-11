package net.exylia.lib.hologram.internal;

import com.github.retrooper.packetevents.PacketEvents;
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
    public void spawn(Player viewer, DisplayState display, Location at) {
        send(viewer, new WrapperPlayServerSpawnEntity(
                display.entityId(),
                Optional.of(UUID.randomUUID()),
                typeOf(display.kind()),
                new Vector3d(at.getX(), at.getY(), at.getZ()),
                0f, 0f, 0f, 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityMetadata(display.entityId(), fullState(display)));
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
    public void teleport(Player viewer, DisplayState display, Location to) {
        send(viewer, new WrapperPlayServerEntityTeleport(display.entityId(),
                new Vector3d(to.getX(), to.getY(), to.getZ()), 0f, 0f, false));
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
    private static List<EntityData<?>> fullState(DisplayState display) {
        HologramConfig.Properties properties = display.properties();
        List<EntityData<?>> data = new ArrayList<>(10);

        data.add(new EntityData<>(DisplayState.BILLBOARD, EntityDataTypes.BYTE, display.billboard()));
        data.add(new EntityData<>(DisplayState.SCALE, EntityDataTypes.VECTOR3F,
                new Vector3f((float) properties.scaleX(), (float) properties.scaleY(),
                        (float) properties.scaleZ())));

        int brightness = display.packedBrightness();
        if (brightness >= 0) {
            data.add(new EntityData<>(DisplayState.BRIGHTNESS, EntityDataTypes.INT, brightness));
        }

        switch (display.kind()) {
            case TEXT -> {
                data.add(new EntityData<>(DisplayState.TEXT, EntityDataTypes.ADV_COMPONENT, display.text()));
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
