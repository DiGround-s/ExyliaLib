package net.exylia.lib.screen.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every packet a screen sends or reads. The only class of the module that names PacketEvents, so a server
 * without it never loads this one.
 *
 * <h2>How a screen is drawn</h2>
 * Every display stands at the viewer's eyes with billboard centre: the client turns it with the view every
 * frame, so its translation is simply x right, y up, z towards the eye, and the screen holds still on screen
 * while the player keeps their own camera. That matters: a client looking through any other entity stops
 * sending its rotation, and the rotation is the cursor.
 *
 * <p>A rect is a text display whose glyphs, a 16 by 16 grid of {@code M}s, are painted in its own background
 * colour: the client pads a background by about a pixel, which is a fifth of a single space but under a
 * percent of the grid.
 *
 * <p>Internal: free to change without notice.
 */
final class ScreenPackets extends PacketListenerAbstract {

    private static final int INTERPOLATION_DELAY = 8;
    private static final int TRANSFORM_DURATION = 9;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int LEFT_ROTATION = 13;
    private static final int RIGHT_ROTATION = 14;
    private static final int BILLBOARD = 15;
    private static final int BRIGHTNESS = 16;
    private static final int TEXT = 23;
    private static final int LINE_WIDTH = 24;
    private static final int BACKGROUND = 25;
    private static final int TEXT_FLAGS = 27;
    private static final int ITEM = 23;
    private static final int ITEM_CONTEXT = 24;
    private static final byte CONTEXT_GUI = 6;
    /** How deep an item is drawn, as a share of its width. */
    private static final double FLAT = 0.02;
    private static final int INTERACTION_WIDTH = 8;
    private static final int INTERACTION_HEIGHT = 9;
    private static final byte BILLBOARD_CENTER = 3;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
    private static final double FONT_PIXEL = 0.025;
    private static final int GRID = 16;
    private static final String GRID_TEXT;
    private static final String CLEAR_TEXT;
    private static final double GRID_WIDE = GRID * 6 + 1;
    /** A space advances four pixels. */
    private static final double CLEAR_WIDE = GRID * 4 + 1;
    /** Sixteen lines of ten pixels: unlike the width, the client's background adds nothing to the height. */
    private static final double GRID_TALL = GRID * 10;

    static {
        StringBuilder grid = new StringBuilder();
        for (int line = 0; line < GRID; line++) {
            if (line > 0) grid.append('\n');
            grid.append("M".repeat(GRID));
        }
        GRID_TEXT = grid.toString();
        CLEAR_TEXT = GRID_TEXT.replace('M', ' ');
    }

    private static ScreenPackets installed;

    private ScreenPackets() {
        super(PacketListenerPriority.LOWEST);
    }

    /** Starts reading input, once. */
    static synchronized void install() {
        if (installed != null) return;
        installed = new ScreenPackets();
        PacketEvents.getAPI().getEventManager().registerListener(installed);
    }

    static int newId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    /** The most packets one bundle may hold: the client refuses a bundle of 4096 or more. */
    static final int BUNDLE = 4000;
    /** Indices that move a display: a change to any of them restarts its glide, so delay and duration go along. */
    private static final int[] TRANSFORM = {TRANSLATION, SCALE, LEFT_ROTATION, 14};

    /**
     * One viewer's wire: remembers the last value sent for each field of each entity and sends only the fields
     * that changed. A spawn starts an entity afresh, a destroy forgets it. Every frame goes out as one bundle,
     * written and flushed in one go on the channel's own thread, so the client never draws half of it.
     */
    static class Wire {

        private final java.util.function.Consumer<List<PacketWrapper<?>>> out;
        private final java.util.Map<Integer, Object[]> sent = new java.util.HashMap<>();

        Wire(java.util.function.Consumer<List<PacketWrapper<?>>> out) {
            this.out = out;
        }

        static Wire to(Player player) {
            return new Wire(frame -> write(player, frame));
        }

        /** Sends what in {@code packets} the client does not have yet, as one frame. Safe from any thread. */
        synchronized void send(List<? extends PacketWrapper<?>> packets) {
            List<PacketWrapper<?>> frame = new java.util.ArrayList<>(packets.size());
            for (PacketWrapper<?> packet : packets) {
                if (packet instanceof WrapperPlayServerSpawnEntity spawn) {
                    sent.remove(spawn.getEntityId());
                } else if (packet instanceof WrapperPlayServerDestroyEntities destroy) {
                    for (int id : destroy.getEntityIds()) sent.remove(id);
                } else if (packet instanceof WrapperPlayServerEntityMetadata data) {
                    packet = changed(data);
                    if (packet == null) continue;
                }
                frame.add(packet);
            }
            // The lock is held until the frame is queued, so frames reach the channel in the order they were diffed.
            if (!frame.isEmpty()) out.accept(frame);
        }

        private PacketWrapper<?> changed(WrapperPlayServerEntityMetadata data) {
            Object[] last = sent.computeIfAbsent(data.getEntityId(), id -> new Object[32]);
            List<EntityData<?>> fields = data.getEntityMetadata();
            boolean[] keep = new boolean[fields.size()];
            boolean moved = false;
            boolean any = false;
            for (int i = 0; i < fields.size(); i++) {
                EntityData<?> field = fields.get(i);
                int index = field.getIndex();
                if (index >= last.length || !java.util.Objects.equals(last[index], field.getValue())) {
                    keep[i] = true;
                    any = true;
                    for (int t : TRANSFORM) moved |= index == t;
                }
            }
            if (!any) return null;
            List<EntityData<?>> diff = new java.util.ArrayList<>(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                EntityData<?> field = fields.get(i);
                int index = field.getIndex();
                // The client times a glide from the packet that carries its delay: a move without it would jump.
                if (keep[i] || moved && (index == INTERPOLATION_DELAY || index == TRANSFORM_DURATION)) {
                    diff.add(field);
                    if (index < last.length) last[index] = field.getValue();
                }
            }
            return diff.size() == fields.size() ? data : new WrapperPlayServerEntityMetadata(data.getEntityId(), diff);
        }
    }

    /** Writes a frame as bundles on the channel's thread, then flushes once. Encoded here, off that thread. */
    static void write(Player player, List<PacketWrapper<?>> frame) {
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(player);
        if (channel == null) return;
        com.github.retrooper.packetevents.manager.protocol.ProtocolManager protocol =
                PacketEvents.getAPI().getProtocolManager();
        List<Object> buffers = new java.util.ArrayList<>(frame.size() + 2);
        for (int from = 0; from < frame.size(); from += BUNDLE) {
            List<PacketWrapper<?>> part = frame.subList(from, Math.min(frame.size(), from + BUNDLE));
            boolean bundled = part.size() > 1;
            if (bundled) buffers.addAll(List.of(protocol.transformWrappers(new WrapperPlayServerBundle(), channel, true)));
            for (PacketWrapper<?> packet : part) buffers.addAll(List.of(protocol.transformWrappers(packet, channel, true)));
            if (bundled) buffers.addAll(List.of(protocol.transformWrappers(new WrapperPlayServerBundle(), channel, true)));
        }
        com.github.retrooper.packetevents.netty.channel.ChannelHelper.runInEventLoop(channel, () -> {
            for (Object buffer : buffers) protocol.writePacket(channel, buffer);
            com.github.retrooper.packetevents.netty.channel.ChannelHelper.flush(channel);
        });
    }

    // ------------------------------------------------------------------
    // Out
    // ------------------------------------------------------------------

    static PacketWrapper<?> spawnDisplay(int id, Vector3d at) {
        return new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.TEXT_DISPLAY, at,
                0f, 0f, 0f, 0, Optional.empty());
    }

    static PacketWrapper<?> spawnItem(int id, Vector3d at) {
        return new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.ITEM_DISPLAY, at,
                0f, 0f, 0f, 0, Optional.empty());
    }

    /**
     * An item drawn as in an inventory slot, centred on ({@code x}, {@code y}), {@code size} blocks across.
     * Flattened along the view so a block's cube never reaches through the layers in front of or behind it.
     */
    static PacketWrapper<?> item(int id, double x, double y, double z, double size, ItemStack item, int glide) {
        List<EntityData<?>> data = new ArrayList<>(itemPose(x, y, z, size, glide));
        data.add(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, converted(item)));
        data.add(new EntityData<>(ITEM_CONTEXT, EntityDataTypes.BYTE, CONTEXT_GUI));
        return new WrapperPlayServerEntityMetadata(id, data);
    }

    /**
     * A half turn about the upright. The client turns every item display half a turn before drawing it, so without
     * this the viewer sees an item's back: a shield's bare wood instead of its banner, a head's hair, a block's
     * underside, a banner's pattern mirrored.
     */
    static final com.github.retrooper.packetevents.util.Quaternion4f FACING_VIEWER =
            new com.github.retrooper.packetevents.util.Quaternion4f(0f, 1f, 0f, 0f);

    /** Where and how big {@link #item} draws, without the item itself. */
    static List<EntityData<?>> itemPose(double x, double y, double z, double size, int glide) {
        return List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORM_DURATION, EntityDataTypes.INT, glide),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f((float) x, (float) y, (float) z)),
                new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION, FACING_VIEWER),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f((float) size, (float) size, (float) (size * FLAT))),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT));
    }

    /** How far a 3D item leans towards the viewer, degrees, so a block shows its top as it turns. */
    private static final double ITEM3D_TILT = 25;
    private static final byte CONTEXT_FIXED = 8;
    /**
     * How much of its depth a turning model keeps along the view, after it has turned. In the round it would reach
     * its own radius behind the box holding it and be cut there, and it would have to stand that much nearer the eye,
     * swelling as it swings; squeezed, it turns as a model in an inventory does, without perspective.
     */
    static final double TURN_DEPTH = 0.2;

    /**
     * A 3D item: its model turned {@code angle} degrees about its upright and, when {@code lean}, leaned towards the
     * viewer. Shaded by the world's lights, so the screen keeps the camera still around it.
     */
    static PacketWrapper<?> item3d(int id, double x, double y, double z, double size, ItemStack item, int glide,
                                   double angle, boolean lean) {
        double a = Math.toRadians(angle) / 2;
        double t = lean ? Math.toRadians(ITEM3D_TILT) / 2 : 0;
        double sa = Math.sin(a), ca = Math.cos(a), st = Math.sin(t), ct = Math.cos(t);
        // No size-up for blocks: the item-frame view already draws a block cube at half the box, so turned and leaned
        // its corners reach the box's edge, as a flat item's do. Cake, saplings and doors are flat items, not cubes.
        return new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORM_DURATION, EntityDataTypes.INT, glide),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f((float) x, (float) y, (float) z)),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f((float) size, (float) size, (float) (size * TURN_DEPTH))),
                // The lean after the turn, both before the squeeze: the depth is squeezed along the view.
                new EntityData<>(RIGHT_ROTATION, EntityDataTypes.QUATERNION,
                        new com.github.retrooper.packetevents.util.Quaternion4f((float) (st * ca), (float) (ct * sa),
                                (float) (st * sa), (float) (ct * ca))),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, converted(item)),
                new EntityData<>(ITEM_CONTEXT, EntityDataTypes.BYTE, CONTEXT_FIXED)));
    }

    /** Items as PacketEvents takes them, one per equal stack, so the wire sees an unchanged item as unchanged. */
    private static final com.github.benmanes.caffeine.cache.Cache<ItemStack, com.github.retrooper.packetevents.protocol.item.ItemStack>
            ITEMS = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(512).build();

    private static com.github.retrooper.packetevents.protocol.item.ItemStack converted(ItemStack item) {
        com.github.retrooper.packetevents.protocol.item.ItemStack known = ITEMS.getIfPresent(item);
        if (known != null) return known;
        ItemStack key = item.clone();
        known = SpigotConversionUtil.fromBukkitItemStack(key);
        ITEMS.put(key, known);
        return known;
    }

    /** The click catcher around the eyes: a click that lands on an entity has no half-second miss cooldown. */
    static List<PacketWrapper<?>> spawnHitbox(int id, Vector3d eyes) {
        return List.of(
                new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.INTERACTION,
                        new Vector3d(eyes.getX(), eyes.getY() - 1.5, eyes.getZ()), 0f, 0f, 0f, 0, Optional.empty()),
                new WrapperPlayServerEntityMetadata(id, List.of(
                        new EntityData<>(INTERACTION_WIDTH, EntityDataTypes.FLOAT, 3f),
                        new EntityData<>(INTERACTION_HEIGHT, EntityDataTypes.FLOAT, 3f))));
    }

    static PacketWrapper<?> destroy(int[] ids) {
        return new WrapperPlayServerDestroyEntities(ids);
    }

    static PacketWrapper<?> heldSlot(int slot) {
        return new WrapperPlayServerHeldItemChange(slot);
    }

    /** Turns the client's pitch back by {@code pitch} without moving it; invisible behind the screen. */
    static PacketWrapper<?> recentre(int teleportId, float pitch) {
        return recentre(teleportId, 0f, pitch);
    }

    /** Turns the camera back by {@code yaw} and {@code pitch} degrees, relative to wherever it faces now. */
    static PacketWrapper<?> recentre(int teleportId, float yaw, float pitch) {
        byte relative = RelativeFlag.X.or(RelativeFlag.Y).or(RelativeFlag.Z)
                .or(RelativeFlag.YAW).or(RelativeFlag.PITCH).getMask();
        return new WrapperPlayServerPlayerPositionAndLook(0, 0, 0, -yaw, -pitch, relative, teleportId, false);
    }

    /**
     * A solid rect centred on ({@code x}, {@code y}) in blocks of the view plane, {@code z} towards the eye.
     *
     * @param glide ticks the client takes to slide to it, 0 to jump
     */
    static PacketWrapper<?> rect(int id, double x, double y, double z, double w, double h, int argb, int glide) {
        // An opaque fill hides its glyphs in its own colour; a see-through one would show them, so it draws
        // spaces instead. ponytail: spaces leave the 1 px background padding at 1.5 % rather than 1 %.
        boolean clear = (argb >>> 24) < 0xFF;
        double sx = w / ((clear ? CLEAR_WIDE : GRID_WIDE) * FONT_PIXEL);
        double sy = h / (GRID_TALL * FONT_PIXEL);
        // The client centres the glyphs half a pixel right of the origin and grows the box up from it.
        Vector3f at = new Vector3f((float) (x - 0.5 * FONT_PIXEL * sx), (float) (y - h / 2), (float) z);
        return new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORM_DURATION, EntityDataTypes.INT, glide),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, at),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f((float) sx, (float) sy, 1f)),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT,
                        Component.text(clear ? CLEAR_TEXT : GRID_TEXT, TextColor.color(argb & 0xFFFFFF))),
                new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, 1000),
                new EntityData<>(BACKGROUND, EntityDataTypes.INT, argb),
                new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, (byte) 0)));
    }

    /**
     * One line of text centred on ({@code x}, {@code y}) in blocks of the view plane.
     *
     * @param scale display scale: one font pixel is {@code 0.025 × scale} blocks
     */
    static PacketWrapper<?> text(int id, double x, double y, double z, double scale, Component text) {
        return text(id, x, y, z, scale, text, 0);
    }

    /** @param glide ticks the client takes to slide and scale to it, 0 to jump */
    static PacketWrapper<?> text(int id, double x, double y, double z, double scale, Component text, int glide) {
        return text(id, x, y, z, scale, text, glide, 1, (byte) 0);
    }

    /**
     * Text of {@code lines} lines (joined by newlines in {@code text}) centred on ({@code x}, {@code y}).
     *
     * @param flags the display's text flags: 1 shadow, 8 lines to the left, 16 to the right
     */
    static PacketWrapper<?> text(int id, double x, double y, double z, double scale, Component text, int glide,
                                 int lines, byte flags) {
        Vector3f at = new Vector3f((float) (x - 0.5 * FONT_PIXEL * scale),
                (float) (y - (Layout.LINE * Math.max(1, lines) + 1) * FONT_PIXEL * scale / 2), (float) z);
        return new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORM_DURATION, EntityDataTypes.INT, glide),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, at),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f((float) scale, (float) scale, 1f)),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text),
                new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, 1000),
                new EntityData<>(BACKGROUND, EntityDataTypes.INT, 0),
                new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, flags)));
    }

    /**
     * A picture drawn as lines of full-block glyphs, its centre at x, y.
     *
     * @param fx blocks per font pixel across, {@code fy} down
     * @param rows lines in {@code text}
     */
    static PacketWrapper<?> image(int id, double x, double y, double z, double fx, double fy, int rows,
                                  Component text, int glide) {
        // A text display hangs its lines up from its origin and centres them across, a glyph's advance leaving one
        // empty column on its right.
        Vector3f at = new Vector3f((float) (x - 0.5 * fx), (float) (y - rows * IMAGE_LINE * fy / 2), (float) z);
        return new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORM_DURATION, EntityDataTypes.INT, glide),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, at),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F,
                        new Vector3f((float) (fx / FONT_PIXEL), (float) (fy / FONT_PIXEL), 1f)),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text),
                new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, 4000),
                new EntityData<>(BACKGROUND, EntityDataTypes.INT, 0),
                new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, (byte) 0)));
    }

    /**
     * A picture standing in the round: as {@link #image}, turned {@code yaw} degrees about the vertical through
     * its centre. {@code nudge} moves it along its own width, in font pixels.
     */
    static PacketWrapper<?> image(int id, double x, double y, double z, double fx, double fy, int rows,
                                  Component text, int glide, double yaw, double nudge) {
        double a = Math.toRadians(yaw);
        double along = (nudge - 0.5) * fx;
        Vector3f at = new Vector3f((float) (x + Math.cos(a) * along), (float) (y - rows * IMAGE_LINE * fy / 2),
                (float) (z - Math.sin(a) * along * TURN_DEPTH));
        return new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0),
                new EntityData<>(TRANSFORM_DURATION, EntityDataTypes.INT, glide),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, at),
                // The turn before the squeeze, as item3d does.
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f((float) (fx / FONT_PIXEL),
                        (float) (fy / FONT_PIXEL), (float) (fx / FONT_PIXEL * TURN_DEPTH))),
                new EntityData<>(RIGHT_ROTATION, EntityDataTypes.QUATERNION,
                        new com.github.retrooper.packetevents.util.Quaternion4f(0f, (float) Math.sin(a / 2), 0f,
                                (float) Math.cos(a / 2))),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text),
                new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, 4000),
                new EntityData<>(BACKGROUND, EntityDataTypes.INT, 0),
                new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, (byte) 0)));
    }

    /** Font pixels from one glyph to the next across, and from one line to the next down. */
    static final int IMAGE_ADVANCE = 9;
    static final int IMAGE_LINE = 10;
    /** The full block glyph's own size in font pixels: the rest of each cell is a gap the copies fill. */
    static final int IMAGE_GLYPH = 8;

    /** Blocks per font pixel, for turning screen pixels into a text display's scale. */
    static double fontPixel() {
        return FONT_PIXEL;
    }

    // ------------------------------------------------------------------
    // In
    // ------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getUser() == null || event.getUser().getUUID() == null) return;
        LiveScreen screen = ScreenRuntime.live(event.getUser().getUUID());
        if (screen == null) return;
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.PLAYER_ROTATION || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
            if (packet.hasRotationChanged()) screen.turned(packet.getLocation().getYaw(), packet.getLocation().getPitch());
        } else if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            if (screen.confirmed(new WrapperPlayClientTeleportConfirm(event).getTeleportId())) event.setCancelled(true);
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            if (packet.getEntityId() != screen.hitboxId()) return;
            event.setCancelled(true);
            if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                screen.click(false);
            } else if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT
                    && packet.getHand() == InteractionHand.MAIN_HAND) {
                screen.click(true);
            }
        } else if (type == PacketType.Play.Client.ANIMATION) {
            // The swing that comes with every click: the click itself arrived as the interaction.
            event.setCancelled(true);
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            event.setCancelled(true);
            screen.wheel(new WrapperPlayClientHeldItemChange(event).getSlot());
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            WrapperPlayClientPlayerInput input = new WrapperPlayClientPlayerInput(event);
            screen.shift(input.isShift());
            screen.sprint(input.isSprint());
            screen.keys((input.isForward() ? 1 : 0) | (input.isLeft() ? 2 : 0) | (input.isBackward() ? 4 : 0)
                    | (input.isRight() ? 8 : 0) | (input.isJump() ? 16 : 0));
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction.Action action = new WrapperPlayClientEntityAction(event).getAction();
            if (action == WrapperPlayClientEntityAction.Action.START_SNEAKING) screen.shift(true);
            else if (action == WrapperPlayClientEntityAction.Action.STOP_SNEAKING) screen.shift(false);
            else if (action == WrapperPlayClientEntityAction.Action.START_SPRINTING) screen.sprint(true);
            else if (action == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) screen.sprint(false);
        }
    }
}
