package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one class in the module that names PacketEvents.
 *
 * <p>Loaded only after {@link PacketRuntime} has confirmed the plugin is
 * present, so a server without it never resolves these imports. Filters what
 * goes out to a viewer who may not see a player, and drops what comes in from
 * a player who may not move.
 */
final class PacketHooks extends PacketListenerAbstract implements PacketSink {

    private PacketHooks() {
        // High: decide after most plugins have rewritten the packet, so what is
        // dropped is what would have reached the client.
        super(PacketListenerPriority.HIGH);
    }

    /** Returns whether PacketEvents is loaded and ready. */
    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Starts listening, or returns {@code null} when PacketEvents is absent. */
    static PacketSink install() {
        if (!ready()) {
            return null;
        }
        PacketHooks hooks = new PacketHooks();
        PacketEvents.getAPI().getEventManager().registerListener(hooks);
        return hooks;
    }

    @Override
    public void close() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    // ------------------------------------------------------------------
    // Outbound: visibility
    // ------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null || !PacketRuntime.hidesAnything(user.getUUID())) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
            stripTabEntries(event, user.getUUID());
            return;
        }
        int entityId = subjectOf(event, type);
        if (entityId >= 0 && PacketRuntime.hidesEntity(user.getUUID(), entityId)) {
            event.setCancelled(true);
        }
    }

    /** The entity a packet is about, or {@code -1} when it has none. */
    private static int subjectOf(PacketSendEvent event, PacketTypeCommon type) {
        if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            return new WrapperPlayServerSpawnEntity(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            return new WrapperPlayServerEntityMetadata(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            return new WrapperPlayServerEntityRelativeMove(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            return new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_ROTATION) {
            return new WrapperPlayServerEntityRotation(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
            return new WrapperPlayServerEntityHeadLook(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            return new WrapperPlayServerEntityTeleport(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            return new WrapperPlayServerEntityPositionSync(event).getId();
        }
        if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            return new WrapperPlayServerEntityVelocity(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_ANIMATION) {
            return new WrapperPlayServerEntityAnimation(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            return new WrapperPlayServerEntityEquipment(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_STATUS) {
            return new WrapperPlayServerEntityStatus(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_EFFECT) {
            return new WrapperPlayServerEntityEffect(event).getEntityId();
        }
        if (type == PacketType.Play.Server.ENTITY_SOUND_EFFECT) {
            return new WrapperPlayServerEntitySoundEffect(event).getEntityId();
        }
        if (type == PacketType.Play.Server.DAMAGE_EVENT) {
            return new WrapperPlayServerDamageEvent(event).getEntityId();
        }
        if (type == PacketType.Play.Server.HURT_ANIMATION) {
            return new WrapperPlayServerHurtAnimation(event).getEntityId();
        }
        // SOUND_EFFECT and PARTICLE carry a position, not an entity: nothing
        // to attribute them to, so they pass.
        return -1;
    }

    /** Drops the hidden players' rows from a tab-list update, keeping the rest. */
    private static void stripTabEntries(PacketSendEvent event, UUID viewer) {
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> kept = new ArrayList<>();
        boolean changed = false;
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : packet.getEntries()) {
            if (PacketRuntime.hidesProfile(viewer, entry.getProfileId())) {
                changed = true;
            } else {
                kept.add(entry);
            }
        }
        if (!changed) {
            return;
        }
        if (kept.isEmpty()) {
            event.setCancelled(true);
            return;
        }
        packet.setEntries(kept);
        event.markForReEncode(true);
    }

    // ------------------------------------------------------------------
    // Inbound: freeze
    // ------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null) {
            return;
        }
        Location anchor = PacketRuntime.anchorOf(user.getUUID());
        if (anchor == null) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
            var at = packet.getLocation();
            if (!moved(anchor, at.getX(), at.getY(), at.getZ())) {
                return;
            }
            event.setCancelled(true);
            float yaw = packet.hasRotationChanged() ? at.getYaw() : anchor.getYaw();
            float pitch = packet.hasRotationChanged() ? at.getPitch() : anchor.getPitch();
            snapBack(user, anchor, yaw, pitch);
        } else if (type == PacketType.Play.Client.VEHICLE_MOVE) {
            WrapperPlayClientVehicleMove packet = new WrapperPlayClientVehicleMove(event);
            var at = packet.getPosition();
            if (!moved(anchor, at.getX(), at.getY(), at.getZ())) {
                return;
            }
            event.setCancelled(true);
            snapBack(user, anchor, packet.getYaw(), packet.getPitch());
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            // WASD held while frozen: swallowed so a vehicle does not creep.
            event.setCancelled(true);
        }
    }

    private static boolean moved(Location anchor, double x, double y, double z) {
        double dx = x - anchor.getX();
        double dy = y - anchor.getY();
        double dz = z - anchor.getZ();
        return dx * dx + dy * dy + dz * dz > 1e-6;
    }

    private static void snapBack(User user, Location anchor, float yaw, float pitch) {
        user.sendPacket(new WrapperPlayServerPlayerPositionAndLook(
                anchor.getX(), anchor.getY(), anchor.getZ(), yaw, pitch,
                (byte) 0, ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), false));
    }

    // ------------------------------------------------------------------
    // Sink
    // ------------------------------------------------------------------

    @Override
    public void despawn(Player viewer, int entityId, UUID profile) {
        send(viewer, new WrapperPlayServerDestroyEntities(entityId));
        send(viewer, new WrapperPlayServerPlayerInfoRemove(profile));
    }

    @Override
    public void blocks(Player viewer, SectionGroups.Section section, List<Location> positions,
                       Map<Location, BlockData> data) {
        if (positions.size() == 1) {
            Location at = positions.get(0);
            send(viewer, new WrapperPlayServerBlockChange(
                    new Vector3i(at.getBlockX(), at.getBlockY(), at.getBlockZ()),
                    SpigotConversionUtil.fromBukkitBlockData(data.get(at))));
            return;
        }
        var encoded = new WrapperPlayServerMultiBlockChange.EncodedBlock[positions.size()];
        for (int i = 0; i < encoded.length; i++) {
            Location at = positions.get(i);
            encoded[i] = new WrapperPlayServerMultiBlockChange.EncodedBlock(
                    SpigotConversionUtil.fromBukkitBlockData(data.get(at)),
                    at.getBlockX() & 15, at.getBlockY() & 15, at.getBlockZ() & 15);
        }
        send(viewer, new WrapperPlayServerMultiBlockChange(
                new Vector3i(section.x(), section.y(), section.z()), true, encoded));
    }

    @Override
    public void gameMode(Player viewer, int mode) {
        send(viewer, new WrapperPlayServerChangeGameState(
                WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, mode));
    }

    @Override
    public void abilities(Player viewer, boolean invulnerable, boolean flying,
                          boolean allowFlight, float flySpeed) {
        send(viewer, new WrapperPlayServerPlayerAbilities(
                invulnerable, flying, allowFlight, false, flySpeed, 0.1f));
    }
}
