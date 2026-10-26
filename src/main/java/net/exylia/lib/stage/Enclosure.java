package net.exylia.lib.stage;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.PluginDisplays;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.packet.FakeBlocks;
import net.exylia.lib.packet.Visibility;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A dark room drawn round a player, for them alone, so all they see is the screen in front of them
 * and a few faint stars: the world, other players and other plugins' holograms are shut out.
 *
 * <pre>{@code
 * PluginPackets packets = Packets.of(this);
 * Enclosure rooms = new Enclosure(Displays.of(this), packets.fakeBlocks(), packets.visibility());
 *
 * // on the player's thread, where they stand looking at their screen
 * rooms.open(player, player.getLocation(), config.get().room());
 * ...
 * rooms.close(player.getUniqueId());
 * }</pre>
 *
 * <p>Six thin slabs, one a side, each facing in: a block display seen from inside its own cube draws
 * nothing, since only a face's outside is drawn. A player in a room is also hidden from everyone, and
 * everyone from them, with the walls drawn or not.
 *
 * <p>The screen is used where the player stands, so every block inside the room is sent to them as
 * air ({@link FakeBlocks}): nothing of the world pokes through the walls or stands in front of the
 * screen. A block that changes nearby is sent again by the server and shows until the room is next
 * drawn.
 *
 * <h2>Visibility</h2>
 * The constructor installs the plugin's {@link Visibility#rule} (one per plugin). A plugin that has a
 * rule of its own composes the two after constructing:
 * {@code visibility.rule((viewer, target) -> rooms.allows(viewer, target) && mine(viewer, target))}.
 *
 * <h2>Threads and lifetime</h2>
 * {@link #open} reads the blocks round the player: call it on the player's thread (their region on
 * Folia). {@link #close} is safe from any thread. Opening again at the same spot keeps the room, so
 * two screens in a row never blink. Everything a plugin drew, faked or hid is undone when it is
 * disabled; {@link #closeAll} gives every player back first, for a shutdown or a reload.
 *
 * @since 1.356.0
 */
public final class Enclosure {

    /** As long as a session can last: the room is taken down by {@link #close}, never by its life. */
    private static final long LIFE = 6L * 60 * 60 * 1000;
    private static final double EYE_HEIGHT = 1.62;
    private static final float SLAB = 0.04f;
    private static final BlockData AIR = Material.AIR.createBlockData();

    private record Room(Location spot, List<DisplayHandle> pieces) {
    }

    private final PluginDisplays displays;
    private final FakeBlocks blocks;
    private final Visibility visibility;
    private final Map<UUID, Room> rooms = new ConcurrentHashMap<>();
    /** Players in a room right now, hidden from everyone and everyone from them. */
    private final Set<UUID> screened = ConcurrentHashMap.newKeySet();

    /**
     * @param displays   the plugin's displays, which draw the walls
     * @param blocks     the plugin's fake blocks, which clear the inside
     * @param visibility the plugin's visibility, whose rule this takes
     */
    public Enclosure(@NotNull PluginDisplays displays, @NotNull FakeBlocks blocks, @NotNull Visibility visibility) {
        this.displays = Objects.requireNonNull(displays, "displays");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        visibility.rule(this::allows);
    }

    /** Whether neither player is in a room: the rule this installs, for a plugin composing its own. */
    public boolean allows(@NotNull Player viewer, @NotNull Player target) {
        return !screened.contains(viewer.getUniqueId()) && !screened.contains(target.getUniqueId());
    }

    /** Whether a player is in a room. */
    public boolean isOpen(@NotNull UUID player) {
        return rooms.containsKey(player);
    }

    /**
     * Puts a player in their room at {@code spot}, where they stand looking at their screen; kept if
     * they are already in one there. On the player's thread.
     */
    public void open(@NotNull Player player, @NotNull Location spot, @NotNull EnclosureTheme theme) {
        Room current = rooms.get(player.getUniqueId());
        if (current != null && current.spot().getWorld() == spot.getWorld() && current.spot().distanceSquared(spot) < 0.01
                && current.spot().getYaw() == spot.getYaw()) return;
        if (current != null) take(current);
        Location at = spot.clone();
        at.setPitch(0);
        if (current != null) blocks.clear(player);
        blocks.show(player, emptied(at, theme));
        rooms.put(player.getUniqueId(), new Room(at, draw(player, at, theme)));
        screen(player.getUniqueId(), true);
    }

    /** Takes a player's room down, gives them the real blocks back and lets them see, and be seen by, everyone again. */
    public void close(@NotNull UUID player) {
        Room room = rooms.remove(player);
        if (room == null) return;
        take(room);
        Player online = Bukkit.getPlayer(player);
        if (online != null) blocks.clear(online);
        screen(player, false);
    }

    /** Every room down, for a shutdown. */
    public void closeAll() {
        List.copyOf(rooms.keySet()).forEach(this::close);
    }

    private void screen(UUID player, boolean on) {
        if (!(on ? screened.add(player) : screened.remove(player))) return;
        Player screenedPlayer = Bukkit.getPlayer(player);
        if (screenedPlayer != null) visibility.refresh(screenedPlayer);
        // Whom they see is everyone else's visibility, so everyone else is re-evaluated too.
        for (Player other : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!other.getUniqueId().equals(player)) visibility.refresh(other);
        }
    }

    /** Air for every block inside the room, from the one the feet stand in up to the ceiling: the ground under the player stays. */
    private static Map<Location, BlockData> emptied(Location feet, EnclosureTheme theme) {
        double reach = Math.max(2, theme.reach()) + 0.5;
        double top = feet.getY() + EYE_HEIGHT + Math.max(2.2, theme.ceiling());
        double yaw = Math.toRadians(feet.getYaw());
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        int span = (int) Math.ceil(reach * Math.sqrt(2));
        Map<Location, BlockData> air = new HashMap<>();
        for (int x = -span; x <= span; x++) {
            for (int z = -span; z <= span; z++) {
                double dx = feet.getBlockX() + x + 0.5 - feet.getX();
                double dz = feet.getBlockZ() + z + 0.5 - feet.getZ();
                // Inside the room's walls, which turn with the way the player faces.
                if (Math.abs(dx * cos + dz * sin) > reach || Math.abs(dz * cos - dx * sin) > reach) continue;
                for (int y = feet.getBlockY(); y <= (int) Math.floor(top); y++) {
                    Location at = new Location(feet.getWorld(), feet.getBlockX() + x, y, feet.getBlockZ() + z);
                    if (!at.getBlock().getType().isAir()) air.put(at, AIR);
                }
            }
        }
        return air;
    }

    private static void take(Room room) {
        room.pieces().forEach(DisplayHandle::remove);
    }

    private List<DisplayHandle> draw(Player player, Location spot, EnclosureTheme theme) {
        List<DisplayHandle> pieces = new ArrayList<>();
        if (!theme.enabled()) return pieces;
        // A frame centred on the eyes, facing the way the screen does.
        StagePlane frame = StagePlane.inFrontOf(spot, EYE_HEIGHT, 0, 0, 1, 1, 1, 1);
        Rotation facing = StageLook.facing(frame);
        List<Player> audience = List.of(player);
        float reach = (float) Math.max(2, theme.reach());
        float ceiling = (float) Math.max(2.2, theme.ceiling());
        // The floor a hair above the ground, so it never fights the block under it.
        float floor = (float) (-EYE_HEIGHT + 0.02);
        float tall = ceiling - floor;
        float wide = reach * 2;
        DisplayModel wall = DisplayModel.block(StageLook.block(theme.block(), Material.BLACK_CONCRETE)).light(0)
                .culling(wide * 1.5f, tall + 2);
        double middle = (ceiling + floor) / 2;
        // Front (behind the screen), back, left, right: each anchored at the floor so its culling box,
        // which rises from the anchor, covers all of it.
        slab(pieces, wall, frame.point(0, floor, -reach), facing, wide, tall, SLAB, middle - floor, audience);
        slab(pieces, wall, frame.point(0, floor, reach), facing, wide, tall, SLAB, middle - floor, audience);
        slab(pieces, wall, frame.point(-reach, floor, 0), facing, SLAB, tall, wide, middle - floor, audience);
        slab(pieces, wall, frame.point(reach, floor, 0), facing, SLAB, tall, wide, middle - floor, audience);
        slab(pieces, wall, frame.point(0, ceiling, 0), facing, wide, SLAB, wide, 0, audience);
        if (theme.floor()) slab(pieces, wall, frame.point(0, floor - SLAB / 2, 0), facing, wide, SLAB, wide, 0, audience);
        stars(pieces, theme, frame, facing, reach, ceiling, floor, audience);
        return pieces;
    }

    /** One side of the room, {@code x} across, {@code y} up and {@code z} deep, raised {@code rise} off its anchor. */
    private void slab(List<DisplayHandle> pieces, DisplayModel model, Location anchor, Rotation facing,
                      float x, float y, float z, double rise, List<Player> audience) {
        DisplayMotion still = DisplayMotion.of(List.of(new DisplayKeyframe(0L, 0f, (float) rise, 0f, facing, x, y, z)), LIFE);
        DisplayHandle handle = displays.show(model, still, anchor, audience);
        if (handle != null) pieces.add(handle);
    }

    /**
     * Faint stars scattered on the walls and the ceiling, each twinkling on its own slow beat; none on
     * the floor, and none low on the walls, where a seated player's eye never rests.
     */
    private void stars(List<DisplayHandle> pieces, EnclosureTheme theme, StagePlane frame, Rotation facing,
                       float reach, float ceiling, float floor, List<Player> audience) {
        if (theme.stars() <= 0 || theme.starColours().isEmpty()) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double inset = SLAB + 0.03;
        for (int i = 0; i < theme.stars(); i++) {
            String colour = theme.starColours().get(random.nextInt(theme.starColours().size()));
            DisplayModel star = DisplayModel.text(StageHud.line(colour + "✦", null)).billboard("FIXED").light(15)
                    .background(0).textShadow(false).seeThrough(false).opacity(random.nextInt(110, 200));
            int side = random.nextInt(5);
            double a = random.nextDouble(-reach + 0.3, reach - 0.3);
            double up = random.nextDouble(floor + 1.2, ceiling - 0.3);
            Location at;
            Rotation turn;
            switch (side) {
                case 0 -> { // front wall, facing the eyes
                    at = frame.point(a, up, -reach + inset);
                    turn = facing;
                }
                case 1 -> { // back wall
                    at = frame.point(a, up, reach - inset);
                    turn = Rotation.around(Rotation.Axis.Y, Math.PI).then(facing);
                }
                case 2 -> { // left wall
                    at = frame.point(-reach + inset, up, a);
                    turn = Rotation.around(Rotation.Axis.Y, Math.PI / 2).then(facing);
                }
                case 3 -> { // right wall
                    at = frame.point(reach - inset, up, a);
                    turn = Rotation.around(Rotation.Axis.Y, -Math.PI / 2).then(facing);
                }
                default -> { // ceiling, looking down
                    at = frame.point(a, ceiling - inset, random.nextDouble(-reach + 0.3, reach - 0.3));
                    turn = Rotation.around(Rotation.Axis.X, Math.PI / 2).then(facing);
                }
            }
            double scale = theme.starScale() * random.nextDouble(0.55, 1.15);
            DisplayKeyframe rest = new DisplayKeyframe(0L, 0f, 0f, 0f, turn, (float) scale, (float) scale, (float) scale);
            DisplayMotion twinkle = StageLook.pulse(rest, random.nextDouble(1.35, 1.8), random.nextLong(200, 2_500),
                    random.nextLong(1_800, 4_800), LIFE);
            DisplayHandle handle = displays.show(star, twinkle, at, audience);
            if (handle != null) pieces.add(handle);
        }
    }
}
