package net.exylia.lib.packet.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.exylia.lib.packet.ItemPlace;
import net.exylia.lib.packet.RevealStyle;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
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
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerInitializeWorldBorder;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Loads PacketEvents' block-state table before anything asks for it in
     * anger.
     *
     * <p>The first block state anybody looks up by name makes PacketEvents
     * inflate a gzipped NBT asset of every block state the game has and build
     * its maps. That is around a tenth of a second, it happens inside whatever
     * called for it, and the first caller on this server is a block outline —
     * so a staff member switching on x-ray vision froze the server for a tick
     * over a hundred milliseconds. It is the same work wherever it runs; here
     * it runs once, at startup, on a thread nobody is waiting for.
     *
     * <p>{@code loadMappings0} is synchronised inside PacketEvents, so a lookup
     * that arrives while this is still running waits for it rather than racing
     * it, and one that arrives afterwards finds the table built.
     *
     * @param plugin the library, whose scheduler the load runs on
     */
    static void warmBlockStates(org.bukkit.plugin.Plugin plugin) {
        net.exylia.lib.task.Tasks.of(plugin).runAsync(() -> {
            try {
                // Any real block name: the table is loaded whole, for the
                // server's own version, whichever name asks for it.
                com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
                        .getByString("stone");
            } catch (Throwable ignored) {
                // A version PacketEvents has no mappings for is not a reason to
                // fail startup; the lookup that needs them will say so.
            }
        });
    }

    @Override
    public void close() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    // Display entity metadata, as the protocol numbers it since 1.19.4.
    private static final int FLAGS = 0;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int GLOW_COLOR = 22;
    private static final int BLOCK_STATE = 23;
    /** Entity flag 0x40: glowing. */
    private static final byte GLOWING = 0x40;
    private static final Vector3f OUTSET = new Vector3f(-0.005f, -0.005f, -0.005f);
    private static final Vector3f OVERSIZE = new Vector3f(1.01f, 1.01f, 1.01f);

    /** Display slot 1: the sidebar. */
    private static final int SIDEBAR_SLOT = 1;

    private static void send(Player player, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    // ------------------------------------------------------------------
    // Outbound: visibility
    // ------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (PacketRuntime.filtersMessages()
                && event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE
                && !readable(event)) {
            event.setCancelled(true);
            return;
        }
        User user = event.getUser();
        // No UUID before login completes: nothing is hidden from a player who
        // does not exist yet.
        UUID viewer = user == null ? null : user.getUUID();
        if (viewer == null) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (PacketRuntime.overlaysAnything()) {
            PacketRuntime.Overlay overlay = PacketRuntime.overlayOf(viewer);
            if (overlay != null) {
                drawOverlay(event, type, overlay);
            }
        }
        if (type == PacketType.Play.Server.OPEN_WINDOW) {
            ItemDecor.opened(viewer, new WrapperPlayServerOpenWindow(event).getContainerId());
        } else if (ItemDecor.decoratesAnything() && carriesItems(type)) {
            decorate(event, type, viewer);
        }
        if (Borders.drawsAny() && isWorldBorder(type) && Borders.replaces(event.getPlayer())) {
            // The world's own border, on its way to somebody who sees one of
            // ours: it would overwrite it. Ours goes out past this listener.
            event.setCancelled(true);
            return;
        }
        if (PacketRuntime.hidesAnything(viewer)) {
            if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
                stripTabEntries(event, viewer);
                return;
            }
            int entityId = subjectOf(event, type);
            if (entityId >= 0 && PacketRuntime.hidesEntity(viewer, entityId)) {
                event.setCancelled(true);
                return;
            }
        }
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            RevealStyle style = PacketRuntime.revealStyle(viewer);
            if (style != null) {
                draw(event, style);
            }
        }
    }

    /** The player's own inventory window. */
    private static final int PLAYER_WINDOW = 0;

    // ------------------------------------------------------------------
    // Outbound: item lines
    // ------------------------------------------------------------------

    private static boolean carriesItems(PacketTypeCommon type) {
        return type == PacketType.Play.Server.SET_SLOT
                || type == PacketType.Play.Server.WINDOW_ITEMS
                || type == PacketType.Play.Server.SET_CURSOR_ITEM
                || type == PacketType.Play.Server.SET_PLAYER_INVENTORY;
    }

    /**
     * Writes every plugin's lines under the items of one inventory packet.
     *
     * <p>Four packets carry them: one slot of a window, a whole window with
     * the cursor, and — split out of the first since 1.21.2 — the cursor alone
     * and one slot of the player's own inventory. Missing either of the last
     * two draws a bare item the moment anything is picked up.
     */
    private static void decorate(PacketSendEvent event, PacketTypeCommon type, UUID viewerId) {
        if (!(event.getPlayer() instanceof Player viewer) || viewer.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (type == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            ItemStack drawn = decorated(viewer, packet.getItem(),
                    ItemDecor.place(viewerId, packet.getWindowId(), packet.getSlot()));
            if (drawn != null) {
                packet.setItem(drawn);
                event.markForReEncode(true);
            }
        } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            List<ItemStack> items = new ArrayList<>(packet.getItems());
            boolean changed = false;
            for (int slot = 0; slot < items.size(); slot++) {
                ItemStack drawn = decorated(viewer, items.get(slot),
                        ItemDecor.place(viewerId, packet.getWindowId(), slot));
                if (drawn != null) {
                    items.set(slot, drawn);
                    changed = true;
                }
            }
            ItemStack carried = packet.getCarriedItem().orElse(null);
            ItemStack drawnCarried = carried == null ? null : decorated(viewer, carried, ItemPlace.OWN);
            if (drawnCarried != null) {
                packet.setCarriedItem(drawnCarried);
                changed = true;
            }
            if (changed) {
                packet.setItems(items);
                event.markForReEncode(true);
            }
        } else if (type == PacketType.Play.Server.SET_CURSOR_ITEM) {
            WrapperPlayServerSetCursorItem packet = new WrapperPlayServerSetCursorItem(event);
            ItemStack drawn = decorated(viewer, packet.getStack(), ItemPlace.OWN);
            if (drawn != null) {
                packet.setStack(drawn);
                event.markForReEncode(true);
            }
        } else {
            WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
            ItemStack drawn = decorated(viewer, packet.getStack(), ItemPlace.OWN);
            if (drawn != null) {
                packet.setStack(drawn);
                event.markForReEncode(true);
            }
        }
    }

    /** A copy of the item with every plugin's lines under its lore, or {@code null} when none has any. */
    private static ItemStack decorated(Player viewer, ItemStack item, ItemPlace place) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        // A fresh stack nobody else holds: handed over, so a lone provider gets it uncopied.
        List<Component> lines = ItemDecor.lines(viewer, SpigotConversionUtil.toBukkitItemStack(item), place);
        if (lines.isEmpty()) {
            return null;
        }
        ItemStack copy = item.copy();
        ItemLore own = copy.getComponentOr(ComponentTypes.LORE, null);
        List<Component> all = new ArrayList<>(own == null ? List.of() : own.getLines());
        // The client disconnects on a lore past 256 lines, and its own lore
        // may already be near that: the decoration is what gives way.
        all.addAll(lines.subList(0, Math.max(0, Math.min(lines.size(), MAX_LORE - all.size()))));
        copy.setComponent(ComponentTypes.LORE, new ItemLore(all));
        return copy;
    }

    /** The most lore lines a client accepts before it disconnects. */
    private static final int MAX_LORE = 256;

    /** Where hotbar slot 0 sits in the player's own inventory window. */
    private static final int HOTBAR_IN_WINDOW = 36;

    /**
     * Draws an overlaid item over the one slot it covers, in whichever
     * inventory packet is carrying that slot.
     *
     * <p>Only the player's own window: a container's window carries the
     * inventory too, at an offset that depends on the container, and the slot
     * is drawn again when it closes. The state id is left as the server sent
     * it, so the client's next click still matches.
     */
    private static void drawOverlay(PacketSendEvent event, PacketTypeCommon type, PacketRuntime.Overlay overlay) {
        if (type == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            if (packet.getWindowId() == PLAYER_WINDOW && packet.getSlot() == HOTBAR_IN_WINDOW + overlay.slot()) {
                packet.setItem(overlayItem(overlay));
                event.markForReEncode(true);
            }
        } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            int index = HOTBAR_IN_WINDOW + overlay.slot();
            if (packet.getWindowId() == PLAYER_WINDOW && index < packet.getItems().size()) {
                List<ItemStack> drawn = new ArrayList<>(packet.getItems());
                drawn.set(index, overlayItem(overlay));
                packet.setItems(drawn);
                event.markForReEncode(true);
            }
        } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
            if (packet.getSlot() == overlay.slot()) {
                packet.setStack(overlayItem(overlay));
                event.markForReEncode(true);
            }
        }
    }

    /**
     * The overlaid item as a packet carries it, converted once per overlay.
     *
     * <p>Handed out as a copy: a listener after this one may edit the stack in
     * the packet in place, and that must not reach the next packet.
     */
    private static ItemStack overlayItem(PacketRuntime.Overlay overlay) {
        if (overlay.encoded instanceof ItemStack encoded) {
            return encoded.copy();
        }
        ItemStack encoded = SpigotConversionUtil.fromBukkitItemStack(overlay.item());
        overlay.encoded = encoded;
        return encoded.copy();
    }

    /**
     * Takes the invisibility off a player on their way to one viewer.
     *
     * <p>The byte itself is {@link PacketRuntime#drawn computed there}, where
     * it can be tested without a server. The list is copied rather than edited
     * in place; what is decoded here belongs to the packet, not to us.
     */
    private static void draw(PacketSendEvent event, RevealStyle style) {
        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
        if (!PacketRuntime.isPlayerEntity(packet.getEntityId())) {
            return;
        }
        List<EntityData<?>> data = packet.getEntityMetadata();
        List<EntityData<?>> redrawn = null;
        for (int i = 0; i < data.size(); i++) {
            EntityData<?> entry = data.get(i);
            if (entry.getIndex() != FLAGS || !(entry.getValue() instanceof Byte flags)) {
                continue;
            }
            byte shown = PacketRuntime.drawn(flags, style);
            if (shown == flags) {
                continue;
            }
            if (redrawn == null) {
                redrawn = new ArrayList<>(data);
            }
            redrawn.set(i, new EntityData<>(FLAGS, EntityDataTypes.BYTE, shown));
        }
        if (redrawn != null) {
            packet.setEntityMetadata(redrawn);
            event.markForReEncode(true);
        }
    }

    /**
     * Whether every message rule lets this line through.
     *
     * <p>The line is flattened to plain text first: a rule reads what the
     * player reads, not the colour codes and hover events around it. A line
     * that cannot be read at all — an unusual encoding, a component the
     * serializer refuses — passes, because dropping messages nobody can
     * inspect is how a server goes quiet for no reason anyone can find.
     */
    private static boolean readable(PacketSendEvent event) {
        Player receiver = event.getPlayer();
        if (receiver == null) {
            return true;
        }
        String text;
        try {
            text = flatten(new WrapperPlayServerSystemChatMessage(event).getMessage());
        } catch (Throwable unreadable) {
            return true;
        }
        return PacketRuntime.canRead(receiver, text);
    }

    /** One line and what it reads as; replaced whole, so a reader never sees half of one. */
    private record Flattened(Component message, String text) {
    }

    private static volatile Flattened lastFlattened;

    /**
     * A line as plain text, remembering the last one.
     *
     * <p>A broadcast goes out once per receiver, each decoded on its own, so
     * fifty players meant fifty serializations of equal components. Comparing
     * against the last one is far cheaper than walking it again; a line that
     * differs simply replaces it.
     */
    static String flatten(Component message) {
        Flattened last = lastFlattened;
        if (last != null && last.message().equals(message)) {
            return last.text();
        }
        String text = PlainTextComponentSerializer.plainText().serialize(message);
        lastFlattened = new Flattened(message, text);
        return text;
    }

    private static boolean isWorldBorder(PacketTypeCommon type) {
        return type == PacketType.Play.Server.INITIALIZE_WORLD_BORDER
                || type == PacketType.Play.Server.WORLD_BORDER_LERP_SIZE
                || type == PacketType.Play.Server.WORLD_BORDER_SIZE
                || type == PacketType.Play.Server.WORLD_BORDER_CENTER
                || type == PacketType.Play.Server.WORLD_BORDER_WARNING_DELAY
                || type == PacketType.Play.Server.WORLD_BORDER_WARNING_REACH
                || type == PacketType.Play.Server.WORLD_BORDER;
    }

    /**
     * The entity a packet is about, or {@code -1} when it has none.
     *
     * <p>With one player vanished this runs for every entity packet to every
     * viewer, on the Netty threads. A wrapper decodes the whole packet — every
     * metadata entry, every equipment item — to hand back the first field, so
     * the packets that lead with the entity id as a VarInt have it peeked
     * straight off the buffer instead.
     */
    private static int subjectOf(PacketSendEvent event, PacketTypeCommon type) {
        // A wrapper an earlier listener decoded is what a new wrapper copies,
        // edits included, and its edits are not in the buffer until the end:
        // then only the wrapper gives the same answer.
        if (leadsWithEntityId(type) && event.getLastUsedWrapper() == null
                && event.getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_8)) {
            return peekVarInt(event.getByteBuf());
        }
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

    /**
     * Whether a packet's first field is the entity id as a VarInt, from 1.8 on.
     *
     * <p>Checked against each wrapper's {@code read()} in PacketEvents 2.13.
     * Left out on purpose: the entity status, which leads with a plain int,
     * the entity sound, which leads with the sound, and the spawn, which is
     * rare enough not to be worth the risk.
     */
    static boolean leadsWithEntityId(PacketTypeCommon type) {
        return type == PacketType.Play.Server.ENTITY_METADATA
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE
                || type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION
                || type == PacketType.Play.Server.ENTITY_ROTATION
                || type == PacketType.Play.Server.ENTITY_HEAD_LOOK
                || type == PacketType.Play.Server.ENTITY_TELEPORT
                || type == PacketType.Play.Server.ENTITY_POSITION_SYNC
                || type == PacketType.Play.Server.ENTITY_VELOCITY
                || type == PacketType.Play.Server.ENTITY_ANIMATION
                || type == PacketType.Play.Server.ENTITY_EQUIPMENT
                || type == PacketType.Play.Server.ENTITY_EFFECT
                || type == PacketType.Play.Server.DAMAGE_EVENT
                || type == PacketType.Play.Server.HURT_ANIMATION;
    }

    /**
     * Reads the VarInt at the buffer's reader index and leaves the index where
     * it was, so every listener after this one reads the packet untouched.
     */
    static int peekVarInt(Object buffer) {
        int at = ByteBufHelper.readerIndex(buffer);
        try {
            return ByteBufHelper.readVarInt(buffer);
        } finally {
            ByteBufHelper.readerIndex(buffer, at);
        }
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
    // Inbound: inventory reconciliation and freeze
    // ------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        UUID player = user == null ? null : user.getUUID();
        if (player == null) {
            return;
        }
        reconcileItemClick(event);
        if (PacketRuntime.overlaysAnything() && event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING
                && keepsOverlay(event, player)) {
            return;
        }
        Location anchor = PacketRuntime.anchorOf(player);
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
            snapBack(user, anchor);
        } else if (type == PacketType.Play.Client.VEHICLE_MOVE) {
            WrapperPlayClientVehicleMove packet = new WrapperPlayClientVehicleMove(event);
            var at = packet.getPosition();
            if (!moved(anchor, at.getX(), at.getY(), at.getZ())) {
                return;
            }
            event.setCancelled(true);
            snapBack(user, anchor);
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            // WASD held while frozen: swallowed so a vehicle does not creep.
            event.setCancelled(true);
        }
    }

    /**
     * Lets the server execute the real click, then send its entire result.
     *
     * <p>Packet lore can change the client's merge prediction. Modern servers
     * execute a click with a stale state ID normally, but finish by sending
     * the full container and cursor instead of trusting the predicted slots.
     * Native IDs are masked to 0..32767, so -1 always requests that path.
     * Only the revision changes: action, slot, button and predicted item/hash
     * payloads stay intact, including the hashed protocol since 1.21.5.
     */
    private static void reconcileItemClick(PacketReceiveEvent event) {
        if (event.isCancelled() || event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW
                || !ItemDecor.decoratesAnything()
                || event.getServerVersion().isOlderThan(ServerVersion.V_1_17_1)
                || !(event.getPlayer() instanceof Player viewer)
                || viewer.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        // Once per tick per player: -1 makes the server send the whole
        // container back, and a client spamming clicks would get one per
        // packet. A click inside the same tick is still corrected by the
        // coalesced resend PacketRuntime.resyncAfterClick schedules.
        long now = System.nanoTime();
        Long last = PacketRuntime.RECONCILED.get(viewer.getUniqueId());
        if (last != null && now - last < ONE_TICK_NANOS) {
            return;
        }
        PacketRuntime.RECONCILED.put(viewer.getUniqueId(), now);
        WrapperPlayClientClickWindow packet = new WrapperPlayClientClickWindow(event);
        packet.setStateID(Optional.of(-1));
        event.markForReEncode(true);
    }

    private static final long ONE_TICK_NANOS = 50_000_000L;

    /**
     * Swallows a drop or a hand swap of an item only the client has.
     *
     * <p>With nothing real in the hand the server has no event to cancel and
     * nothing to send back, while the client has already taken the item out of
     * its hand. The packet is dropped and the inventory sent again, which the
     * overlay draws over — unless whoever drew it reads the drop as the end of
     * what it was drawn for, and takes the overlay down itself.
     */
    private static boolean keepsOverlay(PacketReceiveEvent event, UUID playerId) {
        PacketRuntime.Overlay overlay = PacketRuntime.overlayOf(playerId);
        Object sender = event.getPlayer();
        if (overlay == null || !(sender instanceof Player player)
                || player.getInventory().getHeldItemSlot() != overlay.slot()) {
            return false;
        }
        DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
        boolean drop = action == DiggingAction.DROP_ITEM || action == DiggingAction.DROP_ITEM_STACK;
        if (!drop && action != DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            return false;
        }
        event.setCancelled(true);
        // A hand swap is only refused: the overlay stays, and so does whatever
        // it was drawn for. Throwing it away is the player putting it down.
        PacketRuntime.dropped(player, drop ? overlay.onDrop() : null);
        return true;
    }

    private static boolean moved(Location anchor, double x, double y, double z) {
        double dx = x - anchor.getX();
        double dy = y - anchor.getY();
        double dz = z - anchor.getZ();
        return dx * dx + dy * dy + dz * dz > 1e-6;
    }

    /**
     * Puts the player back on the anchor without touching where they look.
     *
     * <p>The rotation is sent relative, as zero: an absolute one could only be
     * the rotation of the packet being answered, which is a round trip old by
     * the time it lands, and would turn the camera back by whatever the mouse
     * did in between — a stutter on every step for somebody aiming while they
     * hold a movement key.
     */
    private static void snapBack(User user, Location anchor) {
        user.sendPacket(new WrapperPlayServerPlayerPositionAndLook(
                anchor.getX(), anchor.getY(), anchor.getZ(), 0f, 0f,
                RelativeFlag.YAW.or(RelativeFlag.PITCH).getMask(),
                ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), false));
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

    /**
     * A block display the size of the block, invisible behind the world but
     * glowing through it.
     *
     * <p>Spawn and metadata go out together: a display with no metadata is a
     * zero-sized nothing, and the pair is what the client needs to draw the
     * outline. The scale is a hair over one so the outline sits outside the
     * real block's faces rather than fighting them.
     */
    @Override
    public void glowingBlock(Player viewer, int entityId, Location at, BlockData data, int argb) {
        send(viewer, new WrapperPlayServerSpawnEntity(entityId, Optional.of(UUID.randomUUID()),
                EntityTypes.BLOCK_DISPLAY,
                new Vector3d(at.getBlockX(), at.getBlockY(), at.getBlockZ()),
                0f, 0f, 0f, 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(
                new EntityData<>(FLAGS, EntityDataTypes.BYTE, GLOWING),
                new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, OUTSET),
                new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, OVERSIZE),
                new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, argb),
                new EntityData<>(BLOCK_STATE, EntityDataTypes.BLOCK_STATE, globalId(data)))));
    }

    /**
     * The protocol id of a block state, remembered per block data.
     *
     * <p>Converting one costs building the block's full string form and parsing
     * it back into a state, and an outline draws thousands of blocks that are
     * nearly always a handful of distinct kinds: the ores a staff member is
     * looking through the walls for, or the one block a region is drawn in.
     *
     * <p>Bounded because block data with properties on it — a stair's facing, a
     * log's axis — has more shapes than a fixed table would hold.
     */
    private static final Map<BlockData, Integer> BLOCK_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int MAX_BLOCK_IDS = 4096;

    private static int globalId(BlockData data) {
        Integer known = BLOCK_IDS.get(data);
        if (known != null) {
            return known;
        }
        int id = SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
        if (BLOCK_IDS.size() < MAX_BLOCK_IDS) {
            BLOCK_IDS.put(data, id);
        }
        return id;
    }

    @Override
    public void destroyEntities(Player viewer, int[] entityIds) {
        send(viewer, new WrapperPlayServerDestroyEntities(entityIds));
    }

    @Override
    public int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    @Override
    public void gameMode(Player viewer, int mode) {
        send(viewer, new WrapperPlayServerChangeGameState(
                WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, mode));
    }

    @Override
    public void sidebarSlot(Player viewer, String objectiveName) {
        send(viewer, new WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, objectiveName));
    }

    @Override
    public void abilities(Player viewer, boolean invulnerable, boolean flying,
                          boolean allowFlight, float flySpeed) {
        send(viewer, new WrapperPlayServerPlayerAbilities(
                invulnerable, flying, allowFlight, false, flySpeed, 0.1f));
    }

    /** How far a portal may place a player, which every border packet repeats. Vanilla's value. */
    private static final int PORTAL_LIMIT = 29_999_984;

    /**
     * One packet for the whole border, whatever changed.
     *
     * <p>Sent silently: the listener above drops border packets on their way to
     * a viewer who sees one of ours, and this is that one.
     *
     * <p>From 1.21.11 the client moves a border by game ticks, and the packet
     * carries ticks: milliseconds there make every resize twenty times slower.
     * The format follows the server; Via converts it for older clients.
     */
    @Override
    public void border(Player viewer, double x, double z, double from, double to, long millis,
                       int warningBlocks, int warningSeconds) {
        long duration = PacketEvents.getAPI().getServerManager().getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_21_11) ? millis / 50 : millis;
        WrapperPlayServerInitializeWorldBorder packet =
                new WrapperPlayServerInitializeWorldBorder(x, z, from, to, duration, PORTAL_LIMIT, 0, 0);
        packet.setWarningBlocks(warningBlocks);
        packet.setWarningTime(warningSeconds);
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);
    }
}
