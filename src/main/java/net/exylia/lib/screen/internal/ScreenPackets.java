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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

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
    private static final int BILLBOARD = 15;
    private static final int BRIGHTNESS = 16;
    private static final int TEXT = 23;
    private static final int LINE_WIDTH = 24;
    private static final int BACKGROUND = 25;
    private static final int TEXT_FLAGS = 27;
    private static final int INTERACTION_WIDTH = 8;
    private static final int INTERACTION_HEIGHT = 9;
    private static final byte BILLBOARD_CENTER = 3;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
    private static final double FONT_PIXEL = 0.025;
    private static final int GRID = 16;
    private static final String GRID_TEXT;
    private static final double GRID_WIDE = GRID * 6 + 1;
    private static final double GRID_TALL = GRID * 10 + 1;

    static {
        StringBuilder grid = new StringBuilder();
        for (int line = 0; line < GRID; line++) {
            if (line > 0) grid.append('\n');
            grid.append("M".repeat(GRID));
        }
        GRID_TEXT = grid.toString();
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

    static void send(Player player, List<? extends PacketWrapper<?>> packets) {
        for (PacketWrapper<?> packet : packets) PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    // ------------------------------------------------------------------
    // Out
    // ------------------------------------------------------------------

    static PacketWrapper<?> spawnDisplay(int id, Vector3d at) {
        return new WrapperPlayServerSpawnEntity(id, Optional.of(UUID.randomUUID()), EntityTypes.TEXT_DISPLAY, at,
                0f, 0f, 0f, 0, Optional.empty());
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
        byte relative = RelativeFlag.X.or(RelativeFlag.Y).or(RelativeFlag.Z)
                .or(RelativeFlag.YAW).or(RelativeFlag.PITCH).getMask();
        return new WrapperPlayServerPlayerPositionAndLook(0, 0, 0, 0f, -pitch, relative, teleportId, false);
    }

    /**
     * A solid rect centred on ({@code x}, {@code y}) in blocks of the view plane, {@code z} towards the eye.
     *
     * @param glide ticks the client takes to slide to it, 0 to jump
     */
    static PacketWrapper<?> rect(int id, double x, double y, double z, double w, double h, int argb, int glide) {
        double sx = w / (GRID_WIDE * FONT_PIXEL);
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
                // ponytail: glyphs are opaque, so a see-through rect shows its grid faintly; Phase 3's dimmer
                // needs a glyph-free fill (spaces, or text opacity) once one is measured in a real client.
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT,
                        Component.text(GRID_TEXT, TextColor.color(argb & 0xFFFFFF))),
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
        Vector3f at = new Vector3f((float) (x - 0.5 * FONT_PIXEL * scale),
                (float) (y - (Layout.LINE + 1) * FONT_PIXEL * scale / 2), (float) z);
        return new WrapperPlayServerEntityMetadata(id, List.of(
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, at),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new Vector3f((float) scale, (float) scale, 1f)),
                new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
                new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
                new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text),
                new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, 1000),
                new EntityData<>(BACKGROUND, EntityDataTypes.INT, 0),
                new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, (byte) 0)));
    }

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
            screen.shift(new WrapperPlayClientPlayerInput(event).isShift());
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction.Action action = new WrapperPlayClientEntityAction(event).getAction();
            if (action == WrapperPlayClientEntityAction.Action.START_SNEAKING) screen.shift(true);
            else if (action == WrapperPlayClientEntityAction.Action.STOP_SNEAKING) screen.shift(false);
        }
    }
}
