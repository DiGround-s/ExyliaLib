package net.exylia.lib.stage;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.overlay.OverlayKeys;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Displays;
import net.exylia.lib.item.Item;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.Overlays;
import net.exylia.lib.text.PluginMessages;
import net.exylia.lib.text.Values;
import net.exylia.lib.ui.UiItem;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Setting a table down by clicking it: the admin's hotbar turns into the tools for a while, a
 * left-click on a chair (a stair, a slab, any block) makes it a seat or takes it back, a right-click
 * on a block puts the board there, and DONE sets the table down. Every seat faces the board: the
 * block right-clicked, or the middle of the seats when none was. A table of one seat with no board
 * clicked faces the way the admin looks when they finish.
 *
 * <p>What was clicked is marked for the admin alone as they go. Nothing in their inventory moves: the
 * tools are drawn over it, and every click is the setup's while it lasts. Started with
 * {@link PlacedTables#setup}.
 *
 * @since 1.371.0
 */
final class TableSetup implements Listener {

    private static final int DONE_SLOT = 7;
    private static final int CANCEL_SLOT = 8;
    /** How long a marker may stand, at most: a setup is minutes, never this long. */
    private static final long MARKER_LIFE = 60 * 60 * 1000L;
    /** A setup's own tools, drawn in the admin's hotbar. */
    private static final String OVERLAY = "table-setup";

    private final Plugin plugin;
    private final PlacedTables tables;
    private final PluginMessages messages;
    private final PluginActions actions;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    private static final class Draft {
        final String id;
        final String game;
        final PlacedTables.SetupText text;
        final Consumer<PlacedTables.Placed> done;
        final Map<Block, DisplayHandle> seats = new LinkedHashMap<>();
        Block board;
        DisplayHandle boardMarker;

        Draft(String id, String game, PlacedTables.SetupText text, Consumer<PlacedTables.Placed> done) {
            this.id = id;
            this.game = game;
            this.text = text;
            this.done = done;
        }
    }

    TableSetup(Plugin plugin, PlacedTables tables) {
        this.plugin = plugin;
        this.tables = tables;
        this.messages = PluginMessages.of(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // The overlay takes every click made with a tool it draws and runs that tool's actions: the
        // clicks never reach an interact event, so the tools answer through actions of their own.
        this.actions = Actions.of(plugin, plugin.getName().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "") + "_tablesetup");
        actions.registerSync("seat", (context, args) -> press(context, true));
        actions.registerSync("board", (context, args) -> press(context, false));
        actions.registerSync("done", (context, args) -> {
            Draft draft = drafts.get(context.player().getUniqueId());
            if (draft != null) finish(context.player(), draft);
            return ActionResult.success();
        });
        actions.registerSync("cancel", (context, args) -> {
            Draft draft = drafts.get(context.player().getUniqueId());
            if (draft != null) {
                end(context.player());
                messages.send(context.player(), draft.text.cancelled(), null);
            }
            return ActionResult.success();
        });
    }

    private ClickBindings bind(String left, String right) {
        String namespace = actions.namespace();
        return new ClickBindings.Builder()
                .add("left,shift_left: " + namespace + ":" + left, actions::template)
                .add("right,shift_right: " + namespace + ":" + right, actions::template)
                .build();
    }

    void start(Player admin, String id, String game, PlacedTables.SetupText text, Consumer<PlacedTables.Placed> done) {
        end(admin);
        drafts.put(admin.getUniqueId(), new Draft(id, game, text, done));
        UiItem seat = UiItem.of(Item.of("OAK_STAIRS").name(text.seatTool()).lore(text.seatLore()).build())
                .bindings(bind("seat", "board")).build();
        OverlayDefinition.Builder tools = OverlayDefinition.of(OVERLAY).hideRest().pickup(false);
        for (int slot = 0; slot < DONE_SLOT; slot++) tools.slot(slot, seat);
        tools.slot(DONE_SLOT, UiItem.of(Item.of("LIME_DYE").name(text.doneTool()).lore(text.doneLore()).build())
                .bindings(bind("done", "done")).build());
        tools.slot(CANCEL_SLOT, UiItem.of(Item.of("BARRIER").name(text.cancelTool()).lore(text.cancelLore()).build())
                .bindings(bind("cancel", "cancel")).build());
        Overlays.of(plugin).show(admin, tools.build());
        messages.send(admin, text.started(), Values.of().putText("table", id));
    }

    boolean isSetting(UUID admin) {
        return drafts.containsKey(admin);
    }

    /** A tool pressed on a block: a seat marked or taken back, or the board put there. */
    private ActionResult press(ActionContext context, boolean seat) {
        Player admin = context.player();
        Draft draft = drafts.get(admin.getUniqueId());
        Block block = context.get(OverlayKeys.BLOCK).orElse(null);
        if (draft == null || block == null) return ActionResult.success();
        if (!seat) {
            draft.board = block;
            if (draft.boardMarker != null) draft.boardMarker.remove();
            draft.boardMarker = marker(admin, top(block).add(0, 0.7, 0), draft.text.boardMarker(), Values.of());
            messages.send(admin, draft.text.boardSet(), null);
            return ActionResult.success();
        }
        DisplayHandle marker = draft.seats.remove(block);
        if (marker != null) {
            marker.remove();
            renumber(admin, draft);
            messages.send(admin, draft.text.seatRemoved(), Values.of("seats", draft.seats.size()));
            return ActionResult.success();
        }
        draft.seats.put(block, null);
        renumber(admin, draft);
        messages.send(admin, draft.text.seatAdded(), Values.of("seat", draft.seats.size()).put("seats", draft.seats.size()));
        return ActionResult.success();
    }

    /** A click with a slot the overlay does not draw (or a client the overlay missed): never a real use while setting up. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (drafts.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (drafts.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer());
    }

    /** Sets the table down from what was clicked, or says what is missing. */
    private void finish(Player admin, Draft draft) {
        if (draft.seats.isEmpty()) {
            messages.send(admin, draft.text.noSeats(), null);
            return;
        }
        List<Location> seats = new ArrayList<>();
        for (Block block : draft.seats.keySet()) seats.add(top(block));
        Location board;
        if (draft.board != null) {
            board = top(draft.board);
        } else if (seats.size() == 1) {
            Location look = admin.getLocation();
            board = seats.getFirst().clone().add(look.getDirection().setY(0).normalize().multiply(1.5));
        } else {
            board = seats.getFirst().clone();
            board.setX(seats.stream().mapToDouble(Location::getX).average().orElse(board.getX()));
            board.setY(seats.stream().mapToDouble(Location::getY).average().orElse(board.getY()));
            board.setZ(seats.stream().mapToDouble(Location::getZ).average().orElse(board.getZ()));
        }
        for (Location seat : seats) seat.setYaw(yawTowards(seat, board));
        board.setYaw(seats.getFirst().getYaw());
        end(admin);
        draft.done.accept(tables.place(draft.id, draft.game, board, seats));
    }

    /** Takes a setup down without setting anything: the tools, the markers. */
    void end(Player admin) {
        Draft draft = drafts.remove(admin.getUniqueId());
        if (draft == null) return;
        draft.seats.values().forEach(marker -> {
            if (marker != null) marker.remove();
        });
        if (draft.boardMarker != null) draft.boardMarker.remove();
        Overlays.of(plugin).hide(admin);
    }

    /** Numbers every seat's marker in the order the seats will be given. */
    private void renumber(Player admin, Draft draft) {
        int number = 0;
        for (Map.Entry<Block, DisplayHandle> seat : draft.seats.entrySet()) {
            number++;
            if (seat.getValue() != null) seat.getValue().remove();
            seat.setValue(marker(admin, top(seat.getKey()).add(0, 0.9, 0), draft.text.seatMarker(), Values.of("seat", number)));
        }
    }

    private DisplayHandle marker(Player admin, Location at, String line, Values values) {
        return Displays.of(plugin).show(DisplayModel.text(StageHud.line(line, values)).billboard("CENTER").light(15),
                DisplayMotion.still(MARKER_LIFE), at, List.of(admin));
    }

    /** Where a player sits on a block: the middle of its top, half a block up on a stair or a slab. */
    static @NotNull Location top(Block block) {
        BoundingBox box = block.getBoundingBox();
        double y = box.getHeight() <= 0 ? block.getY() : box.getMaxY();
        return new Location(block.getWorld(), block.getX() + 0.5, y, block.getZ() + 0.5);
    }

    /** The yaw that looks from one place to another, as Minecraft counts it. */
    static float yawTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1e-6) return from.getYaw();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
