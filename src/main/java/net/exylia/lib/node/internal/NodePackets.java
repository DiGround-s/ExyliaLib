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
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.interaction.Click;
import net.exylia.lib.packet.internal.EntityClicks;
import net.exylia.lib.client.Clients;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node packets through PacketEvents. Loaded only when PacketEvents is installed.
 *
 * <p>Bedrock (Geyser) draws no display entities, so a Bedrock viewer gets a
 * client-side block for a block node and a floating item for an item node; the
 * click box is the same. Glow and smooth scaling have no Bedrock form: a node
 * there is either drawn or, at size 0, not.
 */
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
    private static final int NO_GRAVITY = 5;
    private static final int ITEM_STACK = 8;

    /** Bedrock nodes at size 0, which are not drawn. */
    private static final Set<Integer> HIDDEN = ConcurrentHashMap.newKeySet();


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
        if (bedrock(node)) {
            if (node.size() <= 0f) {
                HIDDEN.add(node.displayId);
            } else {
                HIDDEN.remove(node.displayId);
                drawBedrock(node);
            }
            spawnBox(node);
            return;
        }
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
        spawnBox(node);
    }

    private void spawnBox(NodeImpl node) {
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
        if (bedrock(node)) {
            HIDDEN.remove(node.displayId);
            if (node.isBlock()) restoreBlock(node);
        }
        send(node.viewer, new WrapperPlayServerDestroyEntities(node.displayId, node.hitboxId));
    }

    @Override
    public void look(NodeImpl node) {
        if (bedrock(node)) {
            if (!HIDDEN.contains(node.displayId)) {
                if (node.isBlock()) drawBedrock(node);
                else send(node.viewer, new WrapperPlayServerEntityMetadata(node.displayId, List.of(lookData(node))));
            }
            sendBox(node);
            return;
        }
        send(node.viewer, new WrapperPlayServerEntityMetadata(node.displayId, List.of(lookData(node))));
        sendBox(node);
    }

    @Override
    public void glow(NodeImpl node) {
        if (bedrock(node)) return;
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
        if (bedrock(node)) {
            boolean hide = size <= 0f;
            if (hide == HIDDEN.contains(node.displayId)) return;
            if (hide) {
                HIDDEN.add(node.displayId);
                if (node.isBlock()) restoreBlock(node);
                else send(node.viewer, new WrapperPlayServerDestroyEntities(node.displayId));
            } else {
                HIDDEN.remove(node.displayId);
                drawBedrock(node);
            }
            return;
        }
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

    private static boolean bedrock(NodeImpl node) {
        return Clients.isBedrock(node.viewer);
    }

    /** A Bedrock node as the client can draw it: the block itself, or a dropped item that floats. */
    private static void drawBedrock(NodeImpl node) {
        Object look = node.look();
        if (look instanceof BlockData block) {
            sendBlock(node, SpigotConversionUtil.fromBukkitBlockData(block).getGlobalId());
            return;
        }
        send(node.viewer, new WrapperPlayServerSpawnEntity(node.displayId, Optional.of(UUID.randomUUID()),
                EntityTypes.ITEM, new Vector3d(node.at.getX(), node.at.getY() - 0.25, node.at.getZ()),
                0f, 0f, 0f, 0, Optional.of(new Vector3d(0, 0, 0))));
        send(node.viewer, new WrapperPlayServerEntityMetadata(node.displayId, List.of(
                new EntityData<>(NO_GRAVITY, EntityDataTypes.BOOLEAN, true),
                new EntityData<>(ITEM_STACK, EntityDataTypes.ITEMSTACK,
                        SpigotConversionUtil.fromBukkitItemStack((ItemStack) look)))));
    }

    /** Puts back what the server really has where a Bedrock block node stood. */
    private static void restoreBlock(NodeImpl node) {
        World world = node.at.getWorld();
        int x = node.at.getBlockX(), z = node.at.getBlockZ();
        // ponytail: read off the region thread; a loaded chunk's block read is safe enough on Paper/Leaf.
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) return;
        BlockData real = world.getBlockData(x, node.at.getBlockY(), z);
        sendBlock(node, SpigotConversionUtil.fromBukkitBlockData(real).getGlobalId());
    }

    private static void sendBlock(NodeImpl node, int state) {
        send(node.viewer, new WrapperPlayServerBlockChange(
                new Vector3i(node.at.getBlockX(), node.at.getBlockY(), node.at.getBlockZ()), state));
    }

    /** A Bedrock block node redrawn: after a chunk or a click may have overwritten it. */
    @Override
    public void redraw(NodeImpl node) {
        if (!bedrock(node)) return;
        if (!HIDDEN.contains(node.displayId)) drawBedrock(node);
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
                if (blockPress(event)) {
                    return;
                }
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
                } else if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA
                        && event.getPlayer() instanceof Player player && Clients.isBedrock(player)) {
                    // A chunk overwrites the client blocks Bedrock nodes are drawn as.
                    NodeRuntime.chunkSent(player);
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        return listener;
    }

    /**
     * A Bedrock player hitting or using the client block a node is drawn as:
     * the same click as on its box. The block comes back at once, since the
     * client may already have broken it.
     */
    private static boolean blockPress(PacketReceiveEvent event) {
        boolean attack;
        Vector3i pos;
        int sequence;
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging dig = new WrapperPlayClientPlayerDigging(event);
            if (dig.getAction() != DiggingAction.START_DIGGING && dig.getAction() != DiggingAction.FINISHED_DIGGING
                    && dig.getAction() != DiggingAction.CANCELLED_DIGGING) {
                return false;
            }
            attack = dig.getAction() == DiggingAction.START_DIGGING;
            pos = dig.getBlockPosition();
            sequence = dig.getSequence();
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement use = new WrapperPlayClientPlayerBlockPlacement(event);
            if (use.getHand() != com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND) {
                return false;
            }
            attack = false;
            pos = use.getBlockPosition();
            sequence = use.getSequence();
        } else {
            return false;
        }
        if (!(event.getPlayer() instanceof Player player) || !Clients.isBedrock(player)) {
            return false;
        }
        NodeImpl node = NodeRuntime.blockNodeAt(player, pos.getX(), pos.getY(), pos.getZ());
        if (node == null) {
            return false;
        }
        event.setCancelled(true);
        send(player, new WrapperPlayServerAcknowledgeBlockChanges(sequence));
        INSTANCE.redraw(node);
        // Only the press counts; the finish or cancel of the same swing is just refused.
        if (attack || event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            NodeRuntime.pressed(player);
            NodeRuntime.clicked(player, node.hitboxId, Click.of(attack, player.isSneaking()));
        }
        return true;
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
