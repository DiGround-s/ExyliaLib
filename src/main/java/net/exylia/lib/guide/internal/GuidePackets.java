package net.exylia.lib.guide.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.color.Color;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.waypoint.TrackedWaypoint;
import com.github.retrooper.packetevents.protocol.world.waypoint.Vec3iWaypointInfo;
import com.github.retrooper.packetevents.protocol.world.waypoint.WaypointIcon;
import com.github.retrooper.packetevents.util.Either;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.exylia.lib.guide.GuideTextStyle;
import net.exylia.lib.guide.ViewTextStyle;
import net.exylia.lib.packet.internal.Broadcast;
import net.exylia.lib.packet.internal.PacketPeek;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes guidance to a client.
 *
 * <p>The only class in the module that names PacketEvents, so a server without
 * it never loads this one. Everything here is a packet to one player: nothing
 * is an entity the server knows about, nothing is ticked, saved or in a chunk,
 * and two players standing together see their own guidance only.
 *
 * <p>It is also the listener that keeps a per-viewer glow lit. The server
 * re-sends an entity's flags whenever anything about it changes, and each of
 * those would put the outline out again, so the glowing bit is added to them
 * on their way to a viewer who asked for it &mdash; the same trick the nametag
 * module plays for players, for any entity id.
 */
final class GuidePackets extends PacketListenerAbstract {

    /** Shared entity metadata, from the vanilla protocol. */
    private static final int FLAGS = 0;
    static final byte GLOWING = 0x40;

    /** Display metadata, from the vanilla protocol; see the display module. */
    private static final int INTERPOLATION_DELAY = 8;
    private static final int TRANSFORMATION_DURATION = 9;
    private static final int POS_ROT_DURATION = 10;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int BILLBOARD = 15;
    private static final int BRIGHTNESS = 16;
    private static final int VIEW_RANGE = 17;
    private static final int GLOW_COLOR = 22;
    private static final int BLOCK_STATE = 23;
    private static final int TEXT = 23;
    private static final int LINE_WIDTH = 24;
    private static final int BACKGROUND = 25;
    private static final int TEXT_OPACITY = 26;
    private static final int TEXT_FLAGS = 27;

    static final byte BILLBOARD_FIXED = 0;
    static final byte BILLBOARD_CENTER = 3;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    /** viewer to entity id to how many pieces asked for it to glow. */
    private static final Map<UUID, Map<Integer, Integer>> GLOWING_FOR = new ConcurrentHashMap<>();

    /** viewer to the view texts seated on their own body, kept through every passenger list the server sends them. */
    private static final Map<UUID, Set<Integer>> RIDING_SELF = new ConcurrentHashMap<>();

    /** Teams already created on each viewer's client: creating one twice is an error there. */
    private static final Map<UUID, Set<String>> TEAMS = new ConcurrentHashMap<>();

    private GuidePackets() {
        super(PacketListenerPriority.NORMAL);
    }

    /** Whether PacketEvents is loaded and ready to send. */
    static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Starts the glow listener, or returns {@code null} when PacketEvents is absent. */
    static GuidePackets install() {
        if (!ready()) {
            return null;
        }
        GuidePackets packets = new GuidePackets();
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        return packets;
    }

    /** Stops listening. */
    void close() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
        GLOWING_FOR.clear();
        TEAMS.clear();
        RIDING_SELF.clear();
    }

    /** Reserves an entity id that cannot collide with a real entity. */
    static int newId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    // ------------------------------------------------------------- displays

    /**
     * Spawns a text display.
     *
     * @param moveTicks how long the client takes to glide to each later position
     */
    static void spawnText(Player viewer, int id, double x, double y, double z, Component text,
                          GuideTextStyle style, float scale, byte billboard, int moveTicks, float viewRange) {
        send(viewer, new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()),
                EntityTypes.TEXT_DISPLAY, new Vector3d(x, y, z), 0f, 0f, 0f, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>(14);
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, 4));
        data.add(new EntityData<>(POS_ROT_DURATION, EntityDataTypes.INT, moveTicks));
        data.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
        data.add(new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, billboard));
        // Lit by nothing in the world: guidance that goes dark at night or in
        // a cave is guidance players report as missing.
        data.add(new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT));
        data.add(new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, viewRange));
        data.add(new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text));
        data.add(new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, style.lineWidth()));
        data.add(new EntityData<>(BACKGROUND, EntityDataTypes.INT, style.background()));
        data.add(new EntityData<>(TEXT_OPACITY, EntityDataTypes.BYTE, (byte) style.opacity()));
        int flags = (style.shadow() ? 0x01 : 0) | (style.seeThrough() ? 0x02 : 0);
        data.add(new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, (byte) flags));
        send(viewer, new WrapperPlayServerEntityMetadata(id, data));
    }

    /**
     * Spawns a block display standing on {@code (x, y, z)}: centred over it
     * horizontally, growing upwards, turned by {@code yaw}.
     *
     * @param glow outline colour {@code 0xRRGGBB}, or {@code -1} for none
     */
    static void spawnBlock(Player viewer, int id, double x, double y, double z, float yaw, BlockData block,
                           float sx, float sy, float sz, int glow, float viewRange, int moveTicks) {
        send(viewer, new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()),
                EntityTypes.BLOCK_DISPLAY, new Vector3d(x, y, z), 0f, yaw, yaw, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>(12);
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, 4));
        data.add(new EntityData<>(POS_ROT_DURATION, EntityDataTypes.INT, moveTicks));
        addSize(data, sx, sy, sz);
        data.add(new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT));
        data.add(new EntityData<>(VIEW_RANGE, EntityDataTypes.FLOAT, viewRange));
        if (glow >= 0) {
            data.add(new EntityData<>(FLAGS, EntityDataTypes.BYTE, GLOWING));
            data.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, glow));
        }
        data.add(new EntityData<>(BLOCK_STATE, EntityDataTypes.BLOCK_STATE,
                SpigotConversionUtil.fromBukkitBlockData(block).getGlobalId()));
        send(viewer, new WrapperPlayServerEntityMetadata(id, data));
    }

    /**
     * A block display's size, kept centred over its position.
     *
     * <p>A block display is drawn from its north-west-bottom corner, so half
     * of its width is taken back off; the entity's own yaw turns it after
     * that, so it turns about its own centre.
     */
    private static void addSize(List<EntityData<?>> data, float sx, float sy, float sz) {
        data.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(-sx / 2f, 0f, -sz / 2f)));
        data.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(sx, sy, sz)));
    }

    /** Resizes a block display over {@code ticks}, keeping it centred. */
    static void resizeBlock(Player viewer, int id, float sx, float sy, float sz, int ticks) {
        List<EntityData<?>> data = new ArrayList<>(4);
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, ticks));
        addSize(data, sx, sy, sz);
        send(viewer, new WrapperPlayServerEntityMetadata(id, data));
    }

    /** Rescales a text display over {@code ticks}. */
    static void resizeText(Player viewer, int id, float scale, int ticks) {
        send(viewer, new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, ticks),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)))));
    }

    /** Moves a display; the client glides there over the time it was spawned with. */
    static void move(Player viewer, int id, double x, double y, double z, float yaw) {
        send(viewer, new WrapperPlayServerEntityTeleport(id, new Vector3d(x, y, z), yaw, 0f, false));
    }

    /** Replaces a text display's text. */
    static void text(Player viewer, int id, Component text) {
        send(viewer, new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text))));
    }

    /**
     * Spawns a view text at the player's seat and mounts it on them.
     *
     * <p>The passenger list sent is the one the server knows plus every view
     * text riding them, because a passenger packet replaces the whole list; the
     * listener keeps them in every list the server sends afterwards.
     */
    static void spawnViewText(Player viewer, int id, Component text, ViewTextStyle style, float[] translation) {
        org.bukkit.Location at = viewer.getLocation();
        send(viewer, new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()),
                EntityTypes.TEXT_DISPLAY, new Vector3d(at.getX(), at.getY() + viewer.getHeight(), at.getZ()),
                0f, 0f, 0f, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>(12);
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F,
                new Vector3f(translation[0], translation[1], translation[2])));
        float scale = style.scale() * VIEW_DISTANCE;
        data.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
        data.add(new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER));
        data.add(new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT));
        data.add(new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text));
        data.add(new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, style.lineWidth()));
        data.add(new EntityData<>(BACKGROUND, EntityDataTypes.INT, style.background()));
        data.add(new EntityData<>(TEXT_OPACITY, EntityDataTypes.BYTE, (byte) style.opacity()));
        int flags = (style.shadow() ? 0x01 : 0) | (style.seeThrough() ? 0x02 : 0);
        data.add(new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, (byte) flags));
        send(viewer, new WrapperPlayServerEntityMetadata(id, data));
        RIDING_SELF.computeIfAbsent(viewer.getUniqueId(), key -> ConcurrentHashMap.newKeySet()).add(id);
        List<Entity> real = viewer.getPassengers();
        Set<Integer> riding = RIDING_SELF.get(viewer.getUniqueId());
        int[] ids = new int[real.size() + riding.size()];
        int index = 0;
        for (Entity passenger : real) {
            ids[index++] = passenger.getEntityId();
        }
        for (int extra : riding) {
            if (index < ids.length) {
                ids[index++] = extra;
            }
        }
        send(viewer, new WrapperPlayServerSetPassengers(viewer.getEntityId(),
                index == ids.length ? ids : java.util.Arrays.copyOf(ids, index)));
    }

    /** How far ahead of the eyes a view text floats: the same depth as a cinematic subtitle. */
    static final float VIEW_DISTANCE = 0.95f;

    /** Moves a view text within the view, over {@code ticks}. */
    static void viewTranslate(Player viewer, int id, float[] translation, int ticks) {
        send(viewer, new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, ticks),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F,
                        new Vector3f(translation[0], translation[1], translation[2])))));
    }

    /** Destroys a view text and stops keeping it in its player's passenger list. */
    static void destroyViewText(Player viewer, int id) {
        forgetViewText(viewer.getUniqueId(), id);
        destroy(viewer, id);
    }

    /** Stops keeping a view text in its player's passenger list, without a packet: the client dropped it. */
    static void forgetViewText(UUID viewer, int id) {
        Set<Integer> riding = RIDING_SELF.get(viewer);
        if (riding != null) {
            riding.remove(id);
            if (riding.isEmpty()) {
                RIDING_SELF.remove(viewer, riding);
            }
        }
    }

    /** Takes entities off one client. */
    static void destroy(Player viewer, int... ids) {
        if (ids.length > 0) {
            send(viewer, new WrapperPlayServerDestroyEntities(ids));
        }
    }

    // ----------------------------------------------------------------- glow

    /** Starts or stops adding the glowing bit to one entity's flags for one viewer. */
    static void glow(UUID viewer, int entityId, boolean on) {
        if (on) {
            GLOWING_FOR.computeIfAbsent(viewer, id -> new ConcurrentHashMap<>()).merge(entityId, 1, Integer::sum);
            return;
        }
        Map<Integer, Integer> ids = GLOWING_FOR.get(viewer);
        if (ids != null) {
            ids.computeIfPresent(entityId, (id, count) -> count <= 1 ? null : count - 1);
            if (ids.isEmpty()) {
                GLOWING_FOR.remove(viewer, ids);
            }
        }
    }

    /** Whether some piece still wants this entity to glow for this viewer. */
    static boolean glowing(UUID viewer, int entityId) {
        Map<Integer, Integer> ids = GLOWING_FOR.get(viewer);
        return ids != null && ids.containsKey(entityId);
    }

    /** Sends an entity's flags, which the listener below lights on the way out. */
    static void flags(Player viewer, int entityId, byte flags) {
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(FLAGS, EntityDataTypes.BYTE, flags))));
    }

    /**
     * Puts an entity in a coloured team on one client, which is where a glow
     * outline takes its colour from.
     *
     * @param entry the entity's UUID as a string, which is how a team lists a non-player
     */
    static void colour(Player viewer, NamedTextColor colour, String entry, boolean add) {
        User user = userOf(viewer);
        if (user == null) {
            return;
        }
        String name = "exg_" + colour.toString();
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        if (!add) {
            user.sendPacket(new WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
                    (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of(entry)));
            return;
        }
        Set<String> made = TEAMS.computeIfAbsent(viewer.getUniqueId(), id -> ConcurrentHashMap.newKeySet());
        if (made.add(name)) {
            user.sendPacket(new WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.CREATE,
                    new WrapperPlayServerTeams.ScoreBoardTeamInfo(Component.empty(), Component.empty(),
                            Component.empty(), WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                            WrapperPlayServerTeams.CollisionRule.ALWAYS, colour,
                            WrapperPlayServerTeams.OptionData.fromValue((byte) 0x01)),
                    List.of(entry)));
        } else {
            user.sendPacket(new WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                    (WrapperPlayServerTeams.ScoreBoardTeamInfo) null, List.of(entry)));
        }
    }

    /** Forgets a viewer who left: their client and its teams are gone. */
    static void forget(UUID viewer) {
        GLOWING_FOR.remove(viewer);
        TEAMS.remove(viewer);
        RIDING_SELF.remove(viewer);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.SET_PASSENGERS) {
            keepRiding(event);
            return;
        }
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA
                || !(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        Map<Integer, Integer> ids = GLOWING_FOR.get(viewer.getUniqueId());
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Peeked: a viewer with one glowing target is sent every entity's
        // metadata, and only that target's is worth decoding.
        int entityId = PacketPeek.entityId(event, e -> new WrapperPlayServerEntityMetadata(e).getEntityId());
        if (!ids.containsKey(entityId)) {
            return;
        }
        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        List<EntityData<?>> metadata = packet.getEntityMetadata();
        for (int index = 0; index < metadata.size(); index++) {
            EntityData<?> data = metadata.get(index);
            if (data.getIndex() != FLAGS || !(data.getValue() instanceof Byte flags)) {
                continue;
            }
            if ((flags & GLOWING) == 0) {
                metadata.set(index, new EntityData<>(FLAGS, EntityDataTypes.BYTE, (byte) (flags | GLOWING)));
                event.markForReEncode(true);
            }
            return;
        }
    }

    /**
     * A passenger list for a player who has view texts riding them, sent to
     * that player: the server knows nothing of the texts and would unseat them,
     * so they are added back on the way out. Somebody mounting or leaving the
     * player keeps the panel where it is.
     */
    private static void keepRiding(PacketSendEvent event) {
        if (RIDING_SELF.isEmpty() || !(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        Set<Integer> riding = RIDING_SELF.get(viewer.getUniqueId());
        if (riding == null || riding.isEmpty()) {
            return;
        }
        WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
        if (packet.getEntityId() != viewer.getEntityId()) {
            return;
        }
        int[] passengers = packet.getPassengers();
        Set<Integer> missing = new java.util.LinkedHashSet<>(riding);
        for (int passenger : passengers) {
            missing.remove(passenger);
        }
        if (missing.isEmpty()) {
            return;
        }
        int[] all = java.util.Arrays.copyOf(passengers, passengers.length + missing.size());
        int index = passengers.length;
        for (int id : missing) {
            all[index++] = id;
        }
        packet.setPassengers(all);
        event.markForReEncode(true);
    }

    // ------------------------------------------------------------ waypoints

    /**
     * Whether this player's locator bar can be sent a custom waypoint.
     *
     * <p>Three things must all be true: the server speaks 1.21.6 or newer (the
     * packet does not exist before it, and PacketEvents encodes in the
     * server's protocol), the client does too (an older client behind
     * ViaVersion has no locator bar), and the PacketEvents installed at
     * runtime knows the packet. Any doubt answers {@code false}.
     */
    static boolean waypointsSupported(Player viewer) {
        try {
            if (!PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_6)) {
                return false;
            }
            User user = userOf(viewer);
            return user != null && user.getClientVersion() != null
                    && user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6);
        } catch (Throwable unknown) {
            return false;
        }
    }

    /** Tracks, updates or untracks one waypoint on a locator bar. */
    static void waypoint(Player viewer, UUID id, String operation, int x, int y, int z, int rgb) {
        try {
            WrapperPlayServerWaypoint.Operation op = WrapperPlayServerWaypoint.Operation.valueOf(operation);
            TrackedWaypoint waypoint = new TrackedWaypoint(Either.createLeft(id),
                    new WaypointIcon(WaypointIcon.ICON_STYLE_DEFAULT,
                            new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)),
                    new Vec3iWaypointInfo(new Vector3i(x, y, z)));
            send(viewer, new WrapperPlayServerWaypoint(op, waypoint));
        } catch (Throwable unknown) {
            // A PacketEvents too old for the packet; isDrawn already said no.
        }
    }

    private static User userOf(Player player) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getUser(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        Broadcast.send(viewer, packet);
    }
}
