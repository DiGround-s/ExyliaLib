package net.exylia.lib.stage;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.PluginDisplays;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.PluginOverlays;
import net.exylia.lib.packet.FakeBlocks;
import net.exylia.lib.packet.Visibility;
import net.exylia.lib.packet.Volume;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

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
 * <p>The screen is used where the player stands, so every block that reaches into the room is sent to
 * them as air and kept so ({@link FakeBlocks#keep}): nothing of the world pokes through the walls or
 * stands in front of the screen, and a block anyone places, breaks or updates there later, or a chunk
 * the server sends again, stays air to them. Entities in the room (players, mobs, items, frames,
 * paintings, armour stands, projectiles, displays, other plugins' holograms and NPCs) are hidden from
 * them while inside and shown again when they leave or the room closes; this plugin's own screens stay.
 * With the walls drawn, every other player is hidden, as their names would show through the walls.
 *
 * <h2>The floor</h2>
 * The player keeps standing on the real blocks under their feet: a block that holds them up (a slab, a
 * carpet, a bed, farmland, soul sand) is the floor and is never cleared. Wherever the floor layer has
 * nothing to stand on (over the void, off a one-block platform), it is filled with barriers only this
 * player has, so they walk on and the drawn floor never gives way. Under it the world stays as it is.
 * The server's guarantee is the plugin's {@link net.exylia.lib.packet.Movement#confine}: it puts the
 * player back on the centre if they ever fall under the floor or rise over the room, and a held player
 * takes no fall damage and is never kicked for floating there.
 *
 * <h2>Inventory</h2>
 * With {@link #overlay} set, a player in a room wears that overlay until it closes: their own items
 * out of sight and only what the plugin draws there, such as a button to leave.
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



    /** How often the entities in a room are looked for, in ticks. */
    private static final long WATCH_TICKS = 4L;

    /** What one block of a room is sent as. */
    enum Fill { REAL, AIR, BARRIER }

    private record Room(Location spot, List<DisplayHandle> pieces, Volume volume, boolean walls,
                        Set<java.util.UUID> inside, TaskHandle[] watch) {
    }

    private final PluginDisplays displays;
    private final FakeBlocks blocks;
    private final Visibility visibility;
    private final Map<UUID, Room> rooms = new ConcurrentHashMap<>();
    /** Players in a room right now, hidden from everyone and everyone from them. */
    private final Set<UUID> screened = ConcurrentHashMap.newKeySet();
    private volatile PluginOverlays overlays;
    private volatile Supplier<OverlayDefinition> overlay;

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

    /**
     * What a player wears while in a room, from {@link #open} until {@link #close}: an overlay that
     * hides the rest of the inventory ({@link OverlayDefinition.Builder#hideRest()}) leaves only the
     * screen and what the overlay draws. Asked again each time a room opens, so a reloaded language
     * shows on the next one. {@code null} for none, the default.
     *
     * @since 1.358.0
     */
    public @NotNull Enclosure overlay(@Nullable PluginOverlays overlays, @Nullable Supplier<OverlayDefinition> overlay) {
        this.overlays = overlays;
        this.overlay = overlays == null ? null : overlay;
        return this;
    }

    /**
     * The rule this installs, for a plugin composing its own: a player in a room is seen by nobody, and
     * sees nobody while its walls stand; with no walls drawn they see everyone but those inside it.
     */
    public boolean allows(@NotNull Player viewer, @NotNull Player target) {
        if (screened.contains(target.getUniqueId())) return false;
        if (!screened.contains(viewer.getUniqueId())) return true;
        Room room = rooms.get(viewer.getUniqueId());
        return room != null && !room.walls() && !room.inside().contains(target.getUniqueId());
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
        Volume volume = volume(at, theme);
        blocks.keep(player, emptied(at, volume));
        visibility.seal(player, volume);
        Room room = new Room(at, draw(player, at, theme), volume, theme.enabled(), ConcurrentHashMap.newKeySet(),
                new TaskHandle[1]);
        if (current != null) unhide(player, current);
        rooms.put(player.getUniqueId(), room);
        watch(player, room);
        screen(player.getUniqueId(), true);
        Supplier<OverlayDefinition> wear = overlay;
        if (current == null && wear != null) overlays.show(player, wear.get());
    }

    /** Takes a player's room down, gives them the real blocks back and lets them see, and be seen by, everyone again. */
    public void close(@NotNull UUID player) {
        Room room = rooms.remove(player);
        if (room == null) return;
        take(room);
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            blocks.clear(online);
            visibility.seal(online, null);
            unhide(online, room);
        } else if (room.watch()[0] != null) {
            room.watch()[0].cancel();
        }
        if (online != null && overlay != null) overlays.hide(online);
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

    /** The room's inside: to its walls round the feet, from the floor layer up to the ceiling. */
    static Volume volume(Location feet, EnclosureTheme theme) {
        double reach = Math.max(2, theme.reach());
        double top = EYE_HEIGHT + Math.max(2.2, theme.ceiling());
        return Volume.around(feet, reach, feet.getY() - floorLayer(feet.getY()), top);
    }

    /**
     * The layer the floor is in: the block under the feet, read half a block down so a player on a slab,
     * a carpet, farmland or soul sand counts the block holding them up as their floor.
     */
    static int floorLayer(double feetY) {
        return (int) Math.floor(feetY - 0.5);
    }

    /**
     * What one block of the room is sent as.
     *
     * @param y             its height
     * @param floorLayer    the floor layer, see {@link #floorLayer}
     * @param feetY         the height the player stands at
     * @param solid         whether it has anything to stand on (a collision shape)
     * @param collisionTop  how high that shape reaches over the block's bottom
     */
    static Fill fill(int y, int floorLayer, double feetY, boolean solid, double collisionTop) {
        if (y < floorLayer) return Fill.REAL;
        // Holding the feet up, or lower: the floor itself.
        if (solid && y + collisionTop <= feetY + 0.01) return Fill.REAL;
        // The floor layer with nothing to stand on, or poking up past the feet: an invisible floor instead.
        if (y == floorLayer) return Fill.BARRIER;
        return Fill.AIR;
    }

    /**
     * Every block that reaches into the room as it is to be kept: air over the floor, barriers where the
     * floor has nothing, the floor itself left out so it stays real.
     */
    private static Map<Location, BlockData> emptied(Location feet, Volume volume) {
        int layer = floorLayer(feet.getY());
        BlockData air = Material.AIR.createBlockData();
        BlockData barrier = Material.BARRIER.createBlockData();
        int span = (int) Math.ceil(volume.reach() * Math.sqrt(2)) + 1;
        Map<Location, BlockData> kept = new HashMap<>();
        for (int x = -span; x <= span; x++) {
            for (int z = -span; z <= span; z++) {
                int bx = feet.getBlockX() + x;
                int bz = feet.getBlockZ() + z;
                for (int y = layer; y <= (int) Math.floor(volume.top()); y++) {
                    if (!volume.touches(bx, y, bz, bx + 1, y + 1, bz + 1)) continue;
                    Block block = feet.getWorld().getBlockAt(bx, y, bz);
                    double top = 0;
                    boolean solid = false;
                    for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                        solid = true;
                        top = Math.max(top, box.getMaxY());
                    }
                    Fill fill = fill(y, layer, feet.getY(), solid, top);
                    // Barriers only under the walkable square, not under the walls.
                    if (fill == Fill.BARRIER && !volume.holdsColumn(bx + 0.5, bz + 0.5)) continue;
                    if (fill != Fill.REAL) kept.put(new Location(feet.getWorld(), bx, y, bz), fill == Fill.AIR ? air : barrier);
                }
            }
        }
        return kept;
    }

    /** The library plugin, in whose name entities are hidden and the rooms watched. */
    private static Plugin lib() {
        return JavaPlugin.getProvidingPlugin(Enclosure.class);
    }

    /**
     * Looks for entities in the room every few ticks, on the player's thread: those that came in are
     * hidden from the player, those that left (or the room's own player's screens aside) shown again.
     */
    private void watch(Player player, Room room) {
        Plugin lib = lib();
        room.watch()[0] = Tasks.of(lib).runAtEntityTimer(player, 1L, WATCH_TICKS, task -> {
            if (rooms.get(player.getUniqueId()) != room || !player.isOnline()) {
                task.cancel();
                return;
            }
            if (!player.getWorld().equals(room.spot().getWorld())) return;
            Volume v = room.volume();
            double r = v.reach() * Math.sqrt(2) + 1;
            BoundingBox area = new BoundingBox(v.x() - r, v.bottom() - 1, v.z() - r, v.x() + r, v.top() + 1, v.z() + r);
            Set<UUID> now = new java.util.HashSet<>();
            for (Entity entity : player.getWorld().getNearbyEntities(area)) {
                if (entity.equals(player)) continue;
                BoundingBox box = entity.getBoundingBox();
                // Paintings and frames are flat: their box still has some depth to touch the room with.
                if (!v.touches(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), Math.max(box.getMaxY(), box.getMinY() + 0.1),
                        box.getMaxZ())) continue;
                now.add(entity.getUniqueId());
                if (room.inside().add(entity.getUniqueId())) {
                    if (entity instanceof Player other) visibility.refresh(other);
                    else player.hideEntity(lib, entity);
                }
            }
            for (UUID gone : List.copyOf(room.inside())) {
                if (now.contains(gone)) continue;
                room.inside().remove(gone);
                Entity entity = Bukkit.getEntity(gone);
                if (entity instanceof Player other) visibility.refresh(other);
                else if (entity != null) player.showEntity(lib, entity);
            }
        });
    }

    /** Shows the player every entity their room hid, and stops looking. */
    private void unhide(Player player, Room room) {
        if (room.watch()[0] != null) room.watch()[0].cancel();
        Plugin lib = lib();
        List<UUID> hidden = List.copyOf(room.inside());
        room.inside().clear();
        if (hidden.isEmpty()) return;
        // Close is safe from any thread; showing is the player's.
        Tasks.of(lib).runAtEntity(player, () -> {
            for (UUID id : hidden) {
                Entity entity = Bukkit.getEntity(id);
                if (entity instanceof Player other) visibility.refresh(other);
                else if (entity != null) player.showEntity(lib, entity);
            }
        });
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
