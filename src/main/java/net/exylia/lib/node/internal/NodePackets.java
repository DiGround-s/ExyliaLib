package net.exylia.lib.node.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.packet.internal.EntityClicks;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Node packets through PacketEvents. Loaded only when PacketEvents is installed. */
final class NodePackets implements NodeSink {

    static final NodePackets INSTANCE = new NodePackets();

    // Entity metadata indices (1.21.4; unchanged since 1.20.2).
    private static final int ENTITY_FLAGS = 0;
    private static final byte FLAG_GLOWING = 0x40;
    private static final int INTERPOLATION_DELAY = 8;
    private static final int TRANSFORMATION_DURATION = 9;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int GLOW_COLOR = 22;
    private static final int LOOK = 23;
    private static final int INTERACTION_WIDTH = 8;
    private static final int INTERACTION_HEIGHT = 9;
    private static final int INTERACTION_RESPONSIVE = 10;


    private NodePackets() {
    }

    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded()
                    && net.exylia.lib.internal.PacketEventsVersion.agrees();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** An entity id that cannot collide with a real entity. */
    static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    @Override
    public void spawn(NodeImpl node) {
        boolean block = node.isBlock();
        send(node.viewer, new WrapperPlayServerSpawnEntity(node.displayId, Optional.of(UUID.randomUUID()),
                block ? EntityTypes.BLOCK_DISPLAY : EntityTypes.ITEM_DISPLAY,
                new Vector3d(node.at.getX(), node.at.getY(), node.at.getZ()),
                0f, 0f, 0f, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>();
        data.add(lookData(node));
        Integer glow = node.glowColor();
        if (glow != null) {
            data.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_GLOWING));
            data.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, glow));
        }
        float size = node.size();
        if (size != 1f) {
            data.addAll(transform(node, size));
        }
        send(node.viewer, new WrapperPlayServerEntityMetadata(node.displayId, data));

        send(node.viewer, new WrapperPlayServerSpawnEntity(node.hitboxId, Optional.of(UUID.randomUUID()),
                EntityTypes.INTERACTION, boxAt(node), 0f, 0f, 0f, 0, Optional.empty()));
        sendBox(node);
    }

    /** Where the click box stands: on the block's floor, or under the item's centre. */
    private static Vector3d boxAt(NodeImpl node) {
        return node.isBlock()
                ? new Vector3d(node.at.getX() + 0.5, node.at.getY(), node.at.getZ() + 0.5)
                : new Vector3d(node.at.getX(), node.at.getY() - NodeBox.of(node)[1] / 2, node.at.getZ());
    }

    /** The click box fitted to how the node looks and is scaled now. */
    private void sendBox(NodeImpl node) {
        float[] box = NodeBox.of(node);
        send(node.viewer, new WrapperPlayServerEntityMetadata(node.hitboxId, List.of(
                new EntityData<>(INTERACTION_WIDTH, EntityDataTypes.FLOAT, box[0]),
                new EntityData<>(INTERACTION_HEIGHT, EntityDataTypes.FLOAT, box[1]),
                new EntityData<>(INTERACTION_RESPONSIVE, EntityDataTypes.BOOLEAN, true))));
    }

    @Override
    public void despawn(NodeImpl node) {
        send(node.viewer, new WrapperPlayServerDestroyEntities(node.displayId, node.hitboxId));
    }

    @Override
    public void look(NodeImpl node) {
        send(node.viewer, new WrapperPlayServerEntityMetadata(node.displayId, List.of(lookData(node))));
        sendBox(node);
    }

    @Override
    public void glow(NodeImpl node) {
        Integer glow = node.glowColor();
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(ENTITY_FLAGS, EntityDataTypes.BYTE, glow != null ? FLAG_GLOWING : (byte) 0));
        if (glow != null) {
            data.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, glow));
        }
        send(node.viewer, new WrapperPlayServerEntityMetadata(node.displayId, data));
    }

    @Override
    public void scale(NodeImpl node, float size, int ticks) {
        List<EntityData<?>> data = new ArrayList<>(transform(node, size));
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, ticks));
        send(node.viewer, new WrapperPlayServerEntityMetadata(node.displayId, data));
        // A pop is a moment's squash; the box follows only the size the node settles at.
        if (size == node.size()) sendBox(node);
    }

    /**
     * The scale, and for a block the shift that keeps it standing on its base
     * and centred: a block display grows from its corner.
     */
    private static List<EntityData<?>> transform(NodeImpl node, float size) {
        float shift = node.isBlock() ? (1f - size) / 2f : 0f;
        return List.of(
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(shift, 0f, shift)),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(size, size, size)));
    }

    private static EntityData<?> lookData(NodeImpl node) {
        Object look = node.look();
        if (look instanceof BlockData block) {
            return new EntityData<>(LOOK, EntityDataTypes.BLOCK_STATE,
                    SpigotConversionUtil.fromBukkitBlockData(block).getGlobalId());
        }
        return new EntityData<>(LOOK, EntityDataTypes.ITEMSTACK,
                SpigotConversionUtil.fromBukkitItemStack((ItemStack) look));
    }

    /**
     * Hears clicks on node entities, which are kept from the server (it has
     * never heard of those ids), and respawns, after which the client has
     * dropped every node.
     */
    static Object listen() {
        PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                EntityClicks.Press press = EntityClicks.read(event);
                if (press == null || !NodeRuntime.owns(press.entityId())
                        || !(event.getPlayer() instanceof Player player)) {
                    return;
                }
                event.setCancelled(true);
                NodeRuntime.pressed(player);
                if (press.counts()) {
                    boolean sneaking = press.sneaking() != null ? press.sneaking() : player.isSneaking();
                    NodeRuntime.clicked(player, press.entityId(), Click.of(press.attack(), sneaking));
                }
            }

            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.RESPAWN
                        && event.getPlayer() instanceof Player player) {
                    event.getPostTasks().add(() -> NodeRuntime.respawned(player));
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    static void stopListening(Object listener) {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener((PacketListenerAbstract) listener);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }
}
