package net.exylia.lib.overlay.internal;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.exylia.lib.overlay.OverlayLock;
import net.exylia.lib.overlay.OverlaySlots;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.ClickKind;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one class in the module that names PacketEvents.
 *
 * <p>Loaded only after {@link OverlayRuntime} has confirmed the plugin is
 * present, so a server without it never resolves these imports.
 *
 * <p>Does two things, which are the same trick from two sides. Outbound, it
 * rewrites the slots an overlay owns in whatever the server was about to say
 * about the player's inventory, so the server can resync as often as it likes
 * and the client never learns the truth. Inbound, it refuses every message
 * that would move an item, because the client is looking at items the server
 * does not have and any move it asks for would be answered from the ones it
 * does.
 */
final class OverlayPackets extends PacketListenerAbstract implements OverlaySink {

    /**
     * The window id that writes straight into the client's own inventory.
     *
     * <p>Chosen over window zero because zero is only applied while no other
     * container is open: a player who opens a chest would watch their overlay
     * fall off. This one lands whatever is on screen, and the client ignores
     * the state id that comes with it, which is the other half of why it is
     * the right one — there is no container here whose state we could get
     * wrong.
     */
    private static final int PLAYER_INVENTORY = -2;

    /** How many of a container window's slots belong to the player below it. */
    private static final int PLAYER_REGION = 36;

    /** How far to look for the entity a click named. Wider than any reach. */
    private static final double REACH = 8;

    /**
     * The window each player has open, and how big its top half is.
     *
     * <p>A {@code SetSlot} for an open container carries no size, and the slot
     * cannot be placed without one. Learnt from the {@code WindowItems} the
     * server always sends when a window opens, which arrives before any
     * {@code SetSlot} for it.
     */
    private static final Map<UUID, int[]> WINDOWS = new ConcurrentHashMap<>();

    private OverlayPackets() {
        // Highest: outbound, the rewrite has to be the last word or another
        // plugin's edit would put the real item back. Inbound, everything else
        // has already seen the packet, so an anticheat still counts the click
        // it would have counted.
        super(PacketListenerPriority.HIGHEST);
    }

    /** Returns whether PacketEvents is loaded and ready. */
    private static boolean ready() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Starts listening, or returns {@code null} when PacketEvents is absent. */
    static @Nullable OverlaySink install() {
        if (!ready()) {
            return null;
        }
        OverlayPackets hooks = new OverlayPackets();
        PacketEvents.getAPI().getEventManager().registerListener(hooks);
        return hooks;
    }

    @Override
    public void close() {
        WINDOWS.clear();
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(this);
        } catch (Throwable ignored) {
            // Shutting down after PacketEvents already did is not a failure.
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    public void slot(Player viewer, int index, @Nullable org.bukkit.inventory.ItemStack item) {
        send(viewer, new WrapperPlayServerSetSlot(PLAYER_INVENTORY, 0, index, convert(item)));
    }

    @Override
    public void equipment(Player owner) {
        OverlayView view = OverlayRuntime.viewOf(owner.getUniqueId());
        PlayerInventory inventory = owner.getInventory();
        int held = inventory.getHeldItemSlot();
        List<Equipment> worn = List.of(
                new Equipment(EquipmentSlot.MAIN_HAND, shown(view, held, inventory.getItem(held))),
                new Equipment(EquipmentSlot.OFF_HAND, shown(view, OverlaySlots.OFFHAND, inventory.getItemInOffHand())),
                new Equipment(EquipmentSlot.HELMET, shown(view, OverlaySlots.HELMET, inventory.getHelmet())),
                new Equipment(EquipmentSlot.CHEST_PLATE, shown(view, OverlaySlots.CHESTPLATE, inventory.getChestplate())),
                new Equipment(EquipmentSlot.LEGGINGS, shown(view, OverlaySlots.LEGGINGS, inventory.getLeggings())),
                new Equipment(EquipmentSlot.BOOTS, shown(view, OverlaySlots.BOOTS, inventory.getBoots())));
        int entityId = owner.getEntityId();
        // A wrapper carries the buffer it was written into, so each viewer gets
        // their own rather than a second copy of somebody else's.
        for (Player viewer : owner.getTrackedBy()) {
            send(viewer, new WrapperPlayServerEntityEquipment(entityId, worn));
        }
    }

    /** What a slot should look like from outside: the overlay's item where it owns one. */
    private static com.github.retrooper.packetevents.protocol.item.ItemStack shown(
            @Nullable OverlayView view, int index, @Nullable org.bukkit.inventory.ItemStack real) {
        return convert(view != null && !view.isSuspended() && view.owns(index) ? view.itemAt(index) : real);
    }

    private static void send(Player viewer, PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private static com.github.retrooper.packetevents.protocol.item.ItemStack convert(
            @Nullable org.bukkit.inventory.ItemStack item) {
        return item == null
                ? com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                : SpigotConversionUtil.fromBukkitItemStack(item);
    }

    // ------------------------------------------------------------------
    // Outbound: keeping the server from painting over the overlay
    // ------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // Before anything else, and before any wrapper is built. Decoding a
        // window's contents to look at them costs the same as decoding them to
        // change them, and on a busy server almost every one of these packets
        // belongs to somebody wearing nothing.
        if (!OverlayRuntime.anyActive()) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.CLOSE_WINDOW) {
            forgetWindow(event.getUser());
            return;
        }
        if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            // This one is about somebody else: it names an entity, not the
            // player it is being sent to.
            rewriteEquipment(event);
            return;
        }
        boolean slot = type == PacketType.Play.Server.SET_SLOT;
        boolean contents = type == PacketType.Play.Server.WINDOW_ITEMS;
        if (!slot && !contents) {
            return;
        }
        UUID id = uuidOf(event.getUser());
        OverlayView view = OverlayRuntime.viewOf(id);
        if (view == null || view.isSuspended()) {
            return;
        }
        if (contents) {
            rewriteContents(event, id, view);
        } else {
            rewriteSlot(event, id, view);
        }
    }

    /** Substitutes the overlay's item into one slot the server is announcing. */
    private void rewriteSlot(PacketSendEvent event, UUID id, OverlayView view) {
        WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
        int window = packet.getWindowId();
        if (window == PLAYER_INVENTORY) {
            // One of ours, on its way out.
            return;
        }
        int index = indexOf(id, window, packet.getSlot());
        if (index < 0 || !view.owns(index)) {
            return;
        }
        packet.setItem(convert(view.itemAt(index)));
        event.markForReEncode(true);
    }

    /** Substitutes the overlay's items into a whole window the server is announcing. */
    private void rewriteContents(PacketSendEvent event, UUID id, OverlayView view) {
        WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
        int window = packet.getWindowId();
        List<com.github.retrooper.packetevents.protocol.item.ItemStack> contents =
                new ArrayList<>(packet.getItems());

        int topSize;
        if (window == 0) {
            if (contents.size() < OverlaySlots.PLAYER_MENU_SIZE) {
                return;
            }
            topSize = 0;
        } else {
            topSize = contents.size() - PLAYER_REGION;
            if (topSize < 0) {
                return;
            }
            if (id != null) {
                WINDOWS.put(id, new int[] {window, topSize});
            }
        }
        boolean changed = false;
        int from = window == 0 ? 0 : topSize;
        for (int slot = from; slot < contents.size(); slot++) {
            int index = window == 0
                    ? OverlaySlots.fromPlayerMenu(slot)
                    : OverlaySlots.fromContainer(slot, topSize);
            if (index < 0 || !view.owns(index)) {
                continue;
            }
            contents.set(slot, convert(view.itemAt(index)));
            changed = true;
        }
        if (changed) {
            packet.setItems(contents);
            event.markForReEncode(true);
        }
    }

    /**
     * Substitutes the overlay's items into what the server tells everyone this
     * player is holding and wearing.
     *
     * <p>The wearer's own screen is drawn slot by slot; this is the other
     * half — the hand the rest of the server watches them swing. Without it a
     * staff member holds a compass on their own screen and their real sword to
     * everybody else.
     */
    private void rewriteEquipment(PacketSendEvent event) {
        WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(event);
        OverlayView view = OverlayRuntime.viewOfEntity(packet.getEntityId());
        if (view == null || view.isSuspended()) {
            return;
        }
        List<Equipment> worn = new ArrayList<>(packet.getEquipment());
        boolean changed = false;
        for (int position = 0; position < worn.size(); position++) {
            Equipment piece = worn.get(position);
            int index = indexOf(view, piece.getSlot());
            if (index < 0 || !view.owns(index)) {
                continue;
            }
            worn.set(position, new Equipment(piece.getSlot(), convert(view.itemAt(index))));
            changed = true;
        }
        if (changed) {
            packet.setEquipment(worn);
            event.markForReEncode(true);
        }
    }

    /**
     * The inventory index an equipment slot stands for.
     *
     * <p>The main hand is whichever hotbar slot the player is on, which is why
     * this is asked of the player rather than answered from a table.
     */
    private static int indexOf(OverlayView view, EquipmentSlot slot) {
        return switch (slot) {
            case MAIN_HAND -> view.viewer().getInventory().getHeldItemSlot();
            case OFF_HAND -> OverlaySlots.OFFHAND;
            case HELMET -> OverlaySlots.HELMET;
            case CHEST_PLATE -> OverlaySlots.CHESTPLATE;
            case LEGGINGS -> OverlaySlots.LEGGINGS;
            case BOOTS -> OverlaySlots.BOOTS;
            default -> -1;
        };
    }

    // ------------------------------------------------------------------
    // Inbound: refusing every move
    // ------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!OverlayRuntime.anyActive()) {
            return;
        }
        OverlayView view = OverlayRuntime.viewOf(uuidOf(event.getUser()));
        if (view == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || view.isSuspended()) {
            // Another window has the screen: what the player sees is their own
            // inventory, and moving their own items is theirs to do.
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            click(event, view, player);
        } else if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            creative(event, view, player);
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            digging(event, view, player);
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            use(event, view, player,
                    new WrapperPlayClientUseItem(event).getHand(), null);
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement placement =
                    new WrapperPlayClientPlayerBlockPlacement(event);
            use(event, view, player, placement.getHand(), placement.getBlockPosition());
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            interact(event, view, player);
        } else if (type == PacketType.Play.Client.ANIMATION) {
            swing(event, view, player);
        } else if (type == PacketType.Play.Client.PICK_ITEM
                || type == PacketType.Play.Client.PICK_ITEM_FROM_BLOCK
                || type == PacketType.Play.Client.PICK_ITEM_FROM_ENTITY) {
            // Middle-click in creative writes a slot the client chose. There is
            // no reading which one from here on every version, and there is no
            // version where letting it through is right.
            event.setCancelled(true);
            resync(view, player);
        }
    }

    /** A click inside a window: refused, and turned into a press when it is ours. */
    private void click(PacketReceiveEvent event, OverlayView view, Player player) {
        WrapperPlayClientClickWindow packet = new WrapperPlayClientClickWindow(event);
        int window = packet.getWindowId();
        int index = indexOf(view.id(), window, packet.getSlot());
        WrapperPlayClientClickWindow.WindowClickType clickType = packet.getWindowClickType();
        boolean owned = index >= 0 && view.owns(index);

        if (!OverlayClicks.refuses(view.definition().lock(), scatters(clickType),
                window == 0, index >= 0, owned)) {
            return;
        }
        event.setCancelled(true);
        resync(view, player);

        if (!owned) {
            return;
        }
        ClickKind kind = kindOf(clickType, packet.getButton());
        if (kind != null) {
            atPlayer(view, () -> view.press(index, kind, null, null));
        }
    }

    /** Whether a click moves an item to a slot the server picks rather than the player. */
    private static boolean scatters(WrapperPlayClientClickWindow.WindowClickType clickType) {
        return clickType == WrapperPlayClientClickWindow.WindowClickType.QUICK_MOVE
                || clickType == WrapperPlayClientClickWindow.WindowClickType.SWAP
                || clickType == WrapperPlayClientClickWindow.WindowClickType.PICKUP_ALL
                || clickType == WrapperPlayClientClickWindow.WindowClickType.QUICK_CRAFT;
    }

    /** A creative-mode slot write, the one path that turns a drawn item into a real one. */
    private void creative(PacketReceiveEvent event, OverlayView view, Player player) {
        WrapperPlayClientCreativeInventoryAction packet =
                new WrapperPlayClientCreativeInventoryAction(event);
        int index = OverlaySlots.fromPlayerMenu(packet.getSlot());
        boolean owned = index >= 0 && view.owns(index);
        if (view.definition().lock() != OverlayLock.FULL && !owned) {
            return;
        }
        event.setCancelled(true);
        resync(view, player);
    }

    /** Dropping, swapping to the off-hand, and left-clicking a block. */
    private void digging(PacketReceiveEvent event, OverlayView view, Player player) {
        WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = packet.getAction();
        int held = player.getInventory().getHeldItemSlot();

        if (action == DiggingAction.DROP_ITEM || action == DiggingAction.DROP_ITEM_STACK
                || action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            boolean owned = view.owns(held)
                    || (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND
                        && view.owns(OverlaySlots.OFFHAND));
            if (view.definition().lock() != OverlayLock.FULL && !owned) {
                return;
            }
            event.setCancelled(true);
            resync(view, player);
            if (owned && view.pressedAt(held) != null) {
                ClickKind kind = switch (action) {
                    case DROP_ITEM -> ClickKind.DROP;
                    case DROP_ITEM_STACK -> ClickKind.CONTROL_DROP;
                    default -> ClickKind.SWAP;
                };
                atPlayer(view, () -> view.press(held, kind, null, null));
            }
            return;
        }

        // Left-clicking a block with a tool that is not really there. Refused
        // so the real item underneath does not break it, and bound so a staff
        // tool can answer a left click at all.
        if (action != DiggingAction.START_DIGGING) {
            return;
        }
        ClickKind kind = player.isSneaking() ? ClickKind.SHIFT_LEFT : ClickKind.LEFT;
        OverlayClicks.WorldPress press = worldPress(view, player, held, kind);
        if (press == OverlayClicks.WorldPress.PASS) {
            return;
        }
        event.setCancelled(true);
        if (press == OverlayClicks.WorldPress.PRESS) {
            Vector3i at = packet.getBlockPosition();
            view.markWorldPress(kind);
            atPlayer(view, () -> view.press(held, kind, null, blockAt(player, at)));
        }
    }

    /**
     * What a press in the world does, for the slot the player is holding it in.
     *
     * <p>The real item is read here rather than in {@link OverlayClicks} so
     * that the decision itself stays a function of three booleans and can be
     * tested without a server.
     */
    private static OverlayClicks.WorldPress worldPress(OverlayView view, Player player,
                                                       int slot, ClickKind kind) {
        return OverlayClicks.worldPress(
                view.pressedAt(slot) != null,
                view.owns(slot),
                isEmpty(player.getInventory().getItem(slot)),
                view.definition().emptyHand().bound(kind));
    }

    private static boolean isEmpty(@Nullable org.bukkit.inventory.ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    /** Right-clicking, in the air or on a block. */
    private void use(PacketReceiveEvent event, OverlayView view, Player player,
                     InteractionHand hand, @Nullable Vector3i at) {
        int slot = hand == InteractionHand.OFF_HAND
                ? OverlaySlots.OFFHAND
                : player.getInventory().getHeldItemSlot();
        ClickKind kind = player.isSneaking() ? ClickKind.SHIFT_RIGHT : ClickKind.RIGHT;
        OverlayClicks.WorldPress press = worldPress(view, player, slot, kind);
        if (press == OverlayClicks.WorldPress.PASS) {
            return;
        }
        event.setCancelled(true);
        if (press == OverlayClicks.WorldPress.REFUSE) {
            return;
        }
        if (at == null) {
            // The air press a client sends straight after a block press it
            // predicted did nothing. One press, so one action.
            if (view.repeatsWorldPress(kind)) {
                return;
            }
        }
        view.markWorldPress(kind);
        atPlayer(view, () -> view.press(slot, kind, null, at == null ? null : blockAt(player, at)));
    }

    /** Clicking an entity, which is how a staff tool is pointed at somebody. */
    private void interact(PacketReceiveEvent event, OverlayView view, Player player) {
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        // INTERACT_AT arrives beside INTERACT for the same press. It is still
        // refused — the server turns it into an interaction carrying the item
        // the player really holds — but only its twin is bound, or every
        // action would run twice.
        boolean twin = packet.getAction()
                == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT;
        int slot = packet.getHand() == InteractionHand.OFF_HAND
                ? OverlaySlots.OFFHAND
                : player.getInventory().getHeldItemSlot();
        boolean attack = packet.getAction()
                == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
        boolean sneaking = player.isSneaking();
        ClickKind kind = attack
                ? (sneaking ? ClickKind.SHIFT_LEFT : ClickKind.LEFT)
                : (sneaking ? ClickKind.SHIFT_RIGHT : ClickKind.RIGHT);
        OverlayClicks.WorldPress press = worldPress(view, player, slot, kind);
        if (press == OverlayClicks.WorldPress.PASS) {
            return;
        }
        event.setCancelled(true);
        if (twin || press == OverlayClicks.WorldPress.REFUSE) {
            return;
        }
        int entityId = packet.getEntityId();
        view.markWorldPress(kind);
        atPlayer(view, () -> view.press(slot, kind, nearby(player, entityId), null));
    }

    /**
     * Left-clicking the air, which is the only packet that says so.
     *
     * <p>A block press only reaches the server while the block is in reach, so
     * a tool aimed at the horizon — jump to where I am looking, the oldest
     * staff tool there is — sends nothing else at all. The swing is what the
     * client sends for every left click, in reach or not, and the click that
     * did land as a block or an entity press is swallowed by the mark that
     * press left behind.
     *
     * <p>Cancelled like every other press the overlay answers, and for a
     * reason the animation itself does not suggest: the server turns a swing
     * into a left click on the air, and that interaction carries the item the
     * player really holds. Left through, a lobby item lying under a staff tool
     * answers every left click its owner makes, in the plugin that put it
     * there, while the staff member is looking at something else entirely.
     * Everybody else seeing an arm that does not move is the smaller loss.
     *
     * <p>The cancel is decided before the repeat is: a swing that follows a
     * block or an entity press must not run the action twice, but it must
     * still not reach the world.
     */
    private void swing(PacketReceiveEvent event, OverlayView view, Player player) {
        if (new WrapperPlayClientAnimation(event).getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        int held = player.getInventory().getHeldItemSlot();
        ClickKind kind = player.isSneaking() ? ClickKind.SHIFT_LEFT : ClickKind.LEFT;
        OverlayClicks.WorldPress press = worldPress(view, player, held, kind);
        if (press == OverlayClicks.WorldPress.PASS) {
            return;
        }
        event.setCancelled(true);
        if (press != OverlayClicks.WorldPress.PRESS || view.repeatsWorldPress(kind)) {
            return;
        }
        view.markWorldPress(kind);
        atPlayer(view, () -> view.press(held, kind, null, null));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Puts a slot the client named into the player's inventory numbering.
     *
     * @return the inventory index, or {@code -1} when the slot is not one of
     *         the player's
     */
    private static int indexOf(UUID id, int window, int rawSlot) {
        if (window == 0) {
            return OverlaySlots.fromPlayerMenu(rawSlot);
        }
        int[] known = id == null ? null : WINDOWS.get(id);
        if (known == null || known[0] != window) {
            return -1;
        }
        return OverlaySlots.fromContainer(rawSlot, known[1]);
    }

    /**
     * Asks the server to say what it believes, so the client stops believing
     * what it predicted.
     *
     * <p>A refused click has already been drawn by the client, which moves the
     * item locally before the server answers. The answer never came, so this
     * asks for one; the outbound half of this class rewrites it on the way past,
     * and the overlay is intact again.
     */
    private static void resync(OverlayView view, Player player) {
        atPlayer(view, player::updateInventory);
    }

    private static void atPlayer(OverlayView view, Runnable work) {
        Tasks.of(view.plugin()).runAtEntity(view.viewer(), work);
    }

    /**
     * Finds the entity a click named, on the player's thread.
     *
     * <p>Searched near the player rather than looked up by id, because every
     * way of looking one up by id is either reflection into the server or a
     * scan of the whole world, and a click can only ever name something within
     * reach. The box is wider than reach so a moving target on a laggy
     * connection is still found.
     *
     * @return the entity, or {@code null} when it has already gone
     */
    private static @Nullable Entity nearby(Player player, int entityId) {
        for (Entity candidate : player.getNearbyEntities(REACH, REACH, REACH)) {
            if (candidate.getEntityId() == entityId) {
                return candidate;
            }
        }
        return null;
    }

    /** Resolves a block on the player's thread, where reading the world is safe. */
    private static @Nullable Block blockAt(Player player, Vector3i at) {
        return player.getWorld().getBlockAt(at.getX(), at.getY(), at.getZ());
    }

    private static @Nullable UUID uuidOf(@Nullable User user) {
        return user == null ? null : user.getUUID();
    }

    private static void forgetWindow(@Nullable User user) {
        UUID id = uuidOf(user);
        if (id != null) {
            WINDOWS.remove(id);
        }
    }

    @Override
    public void forget(UUID player) {
        WINDOWS.remove(player);
    }

    /**
     * Reads a click as the UI modules name it.
     *
     * <p>The same vocabulary a menu button answers to, so an overlay item and a
     * menu item are written the same way.
     *
     * @return the kind, or {@code null} for clicks nothing binds
     */
    static @Nullable ClickKind kindOf(WrapperPlayClientClickWindow.WindowClickType clickType,
                                      int button) {
        return switch (clickType) {
            case PICKUP -> button == 1 ? ClickKind.RIGHT : ClickKind.LEFT;
            case QUICK_MOVE -> button == 1 ? ClickKind.SHIFT_RIGHT : ClickKind.SHIFT_LEFT;
            case SWAP -> button == 40 ? ClickKind.SWAP : ClickKind.NUMBER_KEY;
            case CLONE -> ClickKind.MIDDLE;
            case THROW -> button == 1 ? ClickKind.CONTROL_DROP : ClickKind.DROP;
            case PICKUP_ALL -> ClickKind.DOUBLE;
            default -> null;
        };
    }
}
