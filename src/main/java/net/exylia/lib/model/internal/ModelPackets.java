package net.exylia.lib.model.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.exylia.lib.packet.internal.Broadcast;
import net.exylia.lib.packet.internal.EntityClicks;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * The model module's packets: the only class in it that names PacketEvents.
 *
 * <p>Three things the display module does not do. A carrier's armour and name
 * are kept from every client, because the model is what it looks like now. A
 * hit on one of a model's own boxes is handed to the carrier. And the root
 * every part rides is moved with a teleport the client glides along, so a
 * walking model costs one packet a tick, not one per cube.
 */
@ApiStatus.Internal
final class ModelPackets {

    /** Shared entity metadata: whether the name shows. */
    private static final int NAME_VISIBLE = 3;
    /** Display metadata: how many ticks a teleport is glided over. */
    private static final int MOVE_DURATION = 10;
    /** Interaction metadata. */
    private static final int WIDTH = 8;
    private static final int HEIGHT = 9;
    private static final int RESPONSIVE = 10;

    private ModelPackets() {
    }

    /** Where a hit on a model's own box goes. */
    @FunctionalInterface
    interface Clicks {
        void clicked(Player player, int entityId, boolean attack);
    }

    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Starts hiding carriers and hearing hits on boxes.
     *
     * @param carriers whether an entity id is a carrier
     * @param boxes    whether an entity id is a model's box
     * @param clicks   where a hit on one goes
     * @return what to hand to {@link #stop}
     */
    static Object listen(IntPredicate carriers, IntPredicate boxes, Clicks clicks) {
        PacketListenerAbstract listener = new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                    WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(event);
                    if (!carriers.test(packet.getEntityId())) {
                        return;
                    }
                    List<Equipment> bare = new ArrayList<>();
                    for (Equipment worn : packet.getEquipment()) {
                        bare.add(new Equipment(worn.getSlot(), ItemStack.EMPTY));
                    }
                    packet.setEquipment(bare);
                    event.markForReEncode(true);
                } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                    WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
                    if (!carriers.test(packet.getEntityId())) {
                        return;
                    }
                    boolean changed = false;
                    List<EntityData<?>> data = new ArrayList<>(packet.getEntityMetadata());
                    for (int index = 0; index < data.size(); index++) {
                        if (data.get(index).getIndex() == NAME_VISIBLE) {
                            data.set(index, new EntityData<>(NAME_VISIBLE, EntityDataTypes.BOOLEAN, false));
                            changed = true;
                        }
                    }
                    if (changed) {
                        packet.setEntityMetadata(data);
                        event.markForReEncode(true);
                    }
                }
            }

            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                EntityClicks.Press press = EntityClicks.read(event);
                if (press == null || !(event.getPlayer() instanceof Player player) || !boxes.test(press.entityId())) {
                    return;
                }
                // The server has never heard of the id; it would only be confused.
                event.setCancelled(true);
                if (press.counts()) {
                    clicks.clicked(player, press.entityId(), press.attack());
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    static void stop(Object listener) {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener((PacketListenerAbstract) listener);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    /** Tells a display to glide over this many ticks whenever it is teleported. */
    static void glide(List<Player> viewers, int entityId, int ticks) {
        Broadcast.send(viewers, new WrapperPlayServerEntityMetadata(entityId,
                List.of(new EntityData<>(MOVE_DURATION, EntityDataTypes.INT, ticks))));
    }

    static void teleport(List<Player> viewers, int entityId, double x, double y, double z) {
        Broadcast.send(viewers, new WrapperPlayServerEntityTeleport(entityId, new Vector3d(x, y, z), 0f, 0f,
                false));
    }

    /** A box that can be hit, of the size given, standing at its bottom centre. */
    static void box(List<Player> viewers, int entityId, double x, double y, double z, float width, float height) {
        Broadcast.send(viewers, new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()),
                EntityTypes.INTERACTION, new Vector3d(x, y, z), 0f, 0f, 0f, 0, Optional.empty()));
        Broadcast.send(viewers, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(WIDTH, EntityDataTypes.FLOAT, width),
                new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, height),
                new EntityData<>(RESPONSIVE, EntityDataTypes.BOOLEAN, true))));
    }

    /** A box's new size. */
    static void boxSize(List<Player> viewers, int entityId, float width, float height) {
        Broadcast.send(viewers, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(WIDTH, EntityDataTypes.FLOAT, width),
                new EntityData<>(HEIGHT, EntityDataTypes.FLOAT, height))));
    }

    /** Takes many entities off clients in one packet. */
    static void destroy(List<Player> viewers, int[] entityIds) {
        if (entityIds.length > 0) {
            Broadcast.send(viewers, new WrapperPlayServerDestroyEntities(entityIds));
        }
    }
}
