package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.packet.Volume;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sealed boxes: every entity a viewer was sent, wherever it came from (the server, another plugin's
 * packet NPCs and holograms, this library), followed by its position, so a screen standing in the
 * world can keep everything that is not its own out of sight for one viewer and give it all back after.
 *
 * <p>An entity in a sealed box is <em>parked</em>: moved, for that viewer alone, straight up far beyond
 * what any client draws, and moved back to where it really is once the box is lifted or it walks out. Nothing is
 * despawned, so nothing has to be spawned again: a packet-only NPC or hologram another plugin drew
 * keeps its skin, metadata, equipment, passengers and team on the client exactly as that plugin sent
 * them, and the plugin is never asked for anything. Packets about it keep arriving as usual; the ones
 * that would put it back (an absolute move) are dropped while it is parked, its real position kept.
 *
 * <p>The scene's own pieces are what was spawned to the viewer after the box went up, with an id newer
 * than any entity that existed then (every entity id comes from the server's one counter): another
 * plugin's entity already there, or spawned again under its old id, is parked; so is a real player.
 *
 * <p>Runs on the viewer's netty thread for packets, and from any thread for {@link #seal}.
 */
final class Scenes {

    /** How far up an entity is parked: past every client's entity and display view range. */
    static final double PARK = 100_000;
    /** An entity's box when it is looked for in a box: a player's, which covers NPCs and holograms. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    /** One entity the viewer was sent. */
    static final class Seen {
        volatile double x;
        volatile double y;
        volatile double z;
        volatile float yaw;
        volatile float pitch;
        /** Moved away from this viewer, its real position kept above. */
        volatile boolean parked;
        /** One of the scene's own pieces: never parked. */
        volatile boolean own;
        /** Carries the viewer (a seat, a mount): never parked, the viewer would go with it. */
        volatile boolean carrier;

        Seen(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /** What one viewer was sent, and the boxes sealed for them, by owner. */
    static final class View {
        final Map<Integer, Seen> seen = new ConcurrentHashMap<>();
        final Map<String, Volume> boxes = new ConcurrentHashMap<>();
        /** The newest entity id there was when the first box went up. */
        volatile int watermark = Integer.MAX_VALUE;
    }

    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();

    private Scenes() {
    }

    static void forget(UUID viewer) {
        VIEWS.remove(viewer);
    }

    /** Lifts every box a disabled plugin sealed, putting back what they parked. */
    static void release(String owner) {
        for (Map.Entry<UUID, View> entry : VIEWS.entrySet()) {
            if (!entry.getValue().boxes.containsKey(owner)) continue;
            Player viewer = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (viewer != null) seal(viewer, owner, null);
            else entry.getValue().boxes.remove(owner);
        }
    }

    static void clear() {
        VIEWS.clear();
    }

    /**
     * Seals {@code box} for a viewer under {@code owner}, or lifts that owner's box with {@code null}.
     * Everything inside any of the viewer's boxes that is not the scene's is parked at once; what is
     * no longer inside one is put back.
     */
    static void seal(Player viewer, String owner, @Nullable Volume box) {
        View view = VIEWS.computeIfAbsent(viewer.getUniqueId(), ignored -> new View());
        if (box == null) {
            if (view.boxes.remove(owner) == null) return;
        } else {
            if (view.boxes.isEmpty()) {
                // Everything spawned from now on with a newer id is the scene's; nothing before it is.
                view.watermark = SpigotReflectionUtil.generateEntityId();
                view.seen.values().forEach(seen -> seen.own = false);
            }
            view.boxes.put(owner, box);
        }
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user == null) return;
        view.seen.forEach((id, seen) -> {
            boolean park = shouldPark(view, seen);
            if (park == seen.parked) return;
            seen.parked = park;
            try {
                user.sendPacketSilently(teleport(id, seen, park));
            } catch (RuntimeException ignored) {
                // The connection is closing: the client forgets every entity anyway.
            }
        });
    }

    /** Whether a viewer has an entity parked right now: for tests and checks. */
    static boolean parked(UUID viewer, int entityId) {
        View view = VIEWS.get(viewer);
        Seen seen = view == null ? null : view.seen.get(entityId);
        return seen != null && seen.parked;
    }

    /** Follows one outbound packet; parks, or puts back, what it moves. */
    static void onSend(PacketSendEvent event, PacketTypeCommon type, UUID viewer) {
        if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            spawned(event, viewer);
        } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            movedBy(event, type, viewer);
        } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            View view = VIEWS.get(viewer);
            if (view == null) return;
            WrapperPlayServerEntityTeleport move = new WrapperPlayServerEntityTeleport(event);
            RelativeFlag relative = move.getRelativeFlags();
            if (relative != null && (relative.has(RelativeFlag.X) || relative.has(RelativeFlag.Y) || relative.has(RelativeFlag.Z))) {
                return;
            }
            movedTo(event, view, move.getEntityId(), move.getPosition(), move.getYaw(), move.getPitch());
        } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            View view = VIEWS.get(viewer);
            if (view == null) return;
            WrapperPlayServerEntityPositionSync sync = new WrapperPlayServerEntityPositionSync(event);
            EntityPositionData values = sync.getValues();
            movedTo(event, view, sync.getId(), values.getPosition(), values.getYaw(), values.getPitch());
        } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            View view = VIEWS.get(viewer);
            if (view == null) return;
            for (int id : new WrapperPlayServerDestroyEntities(event).getEntityIds()) view.seen.remove(id);
        } else if (type == PacketType.Play.Server.SET_PASSENGERS) {
            View view = VIEWS.get(viewer);
            if (view == null || event.getUser() == null) return;
            WrapperPlayServerSetPassengers passengers = new WrapperPlayServerSetPassengers(event);
            Seen seen = view.seen.get(passengers.getEntityId());
            if (seen == null) return;
            boolean carries = false;
            for (int passenger : passengers.getPassengers()) carries |= passenger == event.getUser().getEntityId();
            seen.carrier = carries;
            if (carries && seen.parked) {
                seen.parked = false;
                after(event, teleport(passengers.getEntityId(), seen, false));
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME || type == PacketType.Play.Server.RESPAWN) {
            // A new level on the client: every entity it had is gone.
            View view = VIEWS.get(viewer);
            if (view != null) view.seen.clear();
        }
    }

    private static void spawned(PacketSendEvent event, UUID viewer) {
        View view = VIEWS.computeIfAbsent(viewer, ignored -> new View());
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
        int id = spawn.getEntityId();
        Vector3d at = spawn.getPosition();
        Seen seen = new Seen(at.getX(), at.getY(), at.getZ(), spawn.getYaw(), spawn.getPitch());
        seen.own = !view.boxes.isEmpty() && id > view.watermark && !PacketRuntime.isPlayerEntity(id);
        view.seen.put(id, seen);
        if (shouldPark(view, seen)) {
            seen.parked = true;
            spawn.setPosition(away(seen));
            event.markForReEncode(true);
        }
    }

    private static void movedBy(PacketSendEvent event, PacketTypeCommon type, UUID viewer) {
        View view = VIEWS.get(viewer);
        if (view == null) return;
        int id;
        double dx;
        double dy;
        double dz;
        if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            WrapperPlayServerEntityRelativeMove move = new WrapperPlayServerEntityRelativeMove(event);
            id = move.getEntityId();
            dx = move.getDeltaX();
            dy = move.getDeltaY();
            dz = move.getDeltaZ();
        } else {
            WrapperPlayServerEntityRelativeMoveAndRotation move = new WrapperPlayServerEntityRelativeMoveAndRotation(event);
            id = move.getEntityId();
            dx = move.getDeltaX();
            dy = move.getDeltaY();
            dz = move.getDeltaZ();
        }
        Seen seen = view.seen.get(id);
        if (seen == null) return;
        seen.x += dx;
        seen.y += dy;
        seen.z += dz;
        if (view.boxes.isEmpty() && !seen.parked) return;
        boolean park = shouldPark(view, seen);
        if (park == seen.parked) return;
        seen.parked = park;
        // The move itself is let through: the client applies it where the entity is now, then this.
        after(event, teleport(id, seen, park));
    }

    private static void movedTo(PacketSendEvent event, View view, int id, Vector3d at, float yaw, float pitch) {
        Seen seen = view.seen.get(id);
        if (seen == null) return;
        seen.x = at.getX();
        seen.y = at.getY();
        seen.z = at.getZ();
        seen.yaw = yaw;
        seen.pitch = pitch;
        if (view.boxes.isEmpty() && !seen.parked) return;
        boolean park = shouldPark(view, seen);
        if (park) {
            // Parked, or parked now: the client is never told where it really is.
            event.setCancelled(true);
            if (!seen.parked) {
                seen.parked = true;
                after(event, teleport(id, seen, true));
            }
            return;
        }
        // Out of every box: this very packet puts it back where it is.
        seen.parked = false;
    }

    private static boolean shouldPark(View view, Seen seen) {
        if (seen.own || seen.carrier || view.boxes.isEmpty()) return false;
        for (Volume box : view.boxes.values()) {
            if (box.touches(seen.x - HALF_WIDTH, seen.y, seen.z - HALF_WIDTH, seen.x + HALF_WIDTH, seen.y + HEIGHT,
                    seen.z + HALF_WIDTH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where a parked entity is sent: straight up, so it stays in its chunk column. A client only ticks
     * entities in chunks it has loaded, and a teleport back is applied by ticking (interpolation): one
     * parked sideways in an unloaded chunk would never come back.
     */
    private static Vector3d away(Seen seen) {
        return new Vector3d(seen.x, seen.y + PARK, seen.z);
    }

    private static WrapperPlayServerEntityTeleport teleport(int id, Seen seen, boolean park) {
        Vector3d to = park ? away(seen) : new Vector3d(seen.x, seen.y, seen.z);
        return new WrapperPlayServerEntityTeleport(id, to, seen.yaw, seen.pitch, false);
    }

    private static void after(PacketSendEvent event, PacketWrapper<?> packet) {
        User user = event.getUser();
        if (user != null) event.getTasksAfterSend().add(() -> user.sendPacketSilently(packet));
    }

    /** For tests. */
    static @Nullable View viewOf(UUID viewer) {
        return VIEWS.get(viewer);
    }
}
