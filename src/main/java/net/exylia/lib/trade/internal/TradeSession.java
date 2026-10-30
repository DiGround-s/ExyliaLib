package net.exylia.lib.trade.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.escrow.Escrow;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.trade.TradeOptions;
import net.exylia.lib.trade.TradeOutcome;
import net.exylia.lib.trade.TradeResult;
import net.exylia.lib.ui.ClickGuard;
import net.exylia.lib.ui.UiSounds;
import net.exylia.lib.util.editor.internal.Icons;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/**
 * One trade between two players: two windows, one {@link TradeState}.
 *
 * <p>Each window is touched only on its own player's thread. The other side's
 * offer reaches it as a cloned snapshot, published through an atomic slot and
 * drawn by a task scheduled on the viewer. The countdown and the timeout are
 * checked on the global thread, which only reads and moves the state.
 */
public final class TradeSession {

    private static final int SIZE = 54;
    private static final int[] OFFER = new int[20];
    private static final int[] MIRROR = new int[20];
    private static final int CONFIRM = 47;
    private static final int INFO = 49;
    private static final int STATUS = 51;
    private static final long TIMER_TICKS = 5L;
    private static final String SUCCESS_SOUND = "ENTITY_EXPERIENCE_ORB_PICKUP|0.7|1";

    /** What a click on one's own half may do; everything else in the window is refused. */
    private static final Set<InventoryAction> EDITS = EnumSet.of(
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME,
            InventoryAction.PLACE_ONE, InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP,
            InventoryAction.MOVE_TO_OTHER_INVENTORY);

    static {
        for (int row = 0, i = 0; row < 5; row++) {
            for (int column = 0; column < 4; column++, i++) {
                OFFER[i] = row * 9 + column;
                MIRROR[i] = row * 9 + 5 + column;
            }
        }
    }

    /** A side's offer as it was at one revision; the items are clones nobody writes to. */
    private record Snapshot(int revision, ItemStack[] items) {
    }

    /** One player's window, and how a click finds its trade. */
    public static final class Window implements InventoryHolder {

        private final TradeSession session;
        private final int side;
        private volatile Inventory inventory;

        private Window(TradeSession session, int side) {
            this.session = session;
            this.side = side;
        }

        public @NotNull TradeSession session() {
            return session;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Escrow escrow;
    private final TradeOptions options;
    private final Consumer<TradeSession> onEnd;
    private final Player[] players;
    private final UUID[] ids;
    private final Window[] windows = {new Window(this, 0), new Window(this, 1)};
    private final TradeState state;
    private final AtomicReferenceArray<Snapshot> snapshots = new AtomicReferenceArray<>(2);
    private final AtomicBoolean[] snapPending = {new AtomicBoolean(), new AtomicBoolean()};
    @SuppressWarnings("unchecked")
    private final List<ItemStack>[] taken = new List[]{List.of(), List.of()};
    private final CompletableFuture<TradeResult> future = new CompletableFuture<>();
    private final long openedAt = System.currentTimeMillis();
    private volatile TradeOutcome outcome = TradeOutcome.CONFIRMED;
    private volatile UUID cancelledBy;
    private volatile long shownSecond = -1;
    private volatile TaskHandle timer;

    public TradeSession(@NotNull Plugin plugin, @NotNull TaskScheduler tasks, @NotNull Escrow escrow,
                        @NotNull Player first, @NotNull Player second, @NotNull TradeOptions options,
                        @NotNull Consumer<TradeSession> onEnd) {
        this.plugin = plugin;
        this.tasks = tasks;
        this.escrow = escrow;
        this.options = options;
        this.onEnd = onEnd;
        this.players = new Player[]{first, second};
        this.ids = new UUID[]{first.getUniqueId(), second.getUniqueId()};
        this.state = new TradeState(options.countdown().toMillis(), options.allowEmpty());
    }

    public @NotNull Plugin plugin() {
        return plugin;
    }

    public @NotNull CompletableFuture<TradeResult> future() {
        return future;
    }

    // ---------------------------------------------------------------- opening

    public void start() {
        timer = tasks.runTimer(TIMER_TICKS, TIMER_TICKS, this::tick);
        for (int side = 0; side < 2; side++) {
            int s = side;
            tasks.runAtEntity(players[side], () -> openWindow(s), () -> cancel(TradeOutcome.LEFT, ids[s]));
        }
    }

    private void openWindow(int side) {
        if (!state.editable()) {
            return;
        }
        Window window = windows[side];
        String title = options.title() != null ? options.title()
                : Phrases.tr("{primary}&lTRADE {letters_black}» {highlight}%player%");
        Inventory inventory = Bukkit.createInventory(window, SIZE,
                Text.of(title).with("%player%", players[1 - side].getName()).build());
        ItemStack filler = filler();
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot % 9 == 4 || slot >= 45) {
                inventory.setItem(slot, filler);
            }
        }
        window.inventory = inventory;
        render(side);
        if (players[side].openInventory(inventory) == null) {
            // Another plugin refused the window: nothing is in it yet.
            window.inventory = null;
            cancel(TradeOutcome.UNAVAILABLE, ids[side]);
        }
    }

    // ----------------------------------------------------------------- clicks

    /** A click in a player's view while their trade window is on top, on their thread. */
    public void click(@NotNull Window window, @NotNull InventoryClickEvent event) {
        int side = window.side;
        InventoryAction action = event.getAction();
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < SIZE;
        // Collecting to the cursor sweeps the whole view, the copies included.
        if (!state.editable() || action == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (!top) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                // Vanilla would fill the first free slot of the window, a copy's included.
                event.setCancelled(true);
                shiftIn(window, event);
            }
            return;
        }
        if (isOffer(raw) && EDITS.contains(action)) {
            if (state.change(side)) {
                changed(side);
            } else {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (raw == CONFIRM && ClickGuard.tryClick(event)) {
            toggle(side);
        }
    }

    /** A drag in a player's view while their trade window is on top, on their thread. */
    public void drag(@NotNull Window window, @NotNull InventoryDragEvent event) {
        boolean touchesOffer = false;
        for (int raw : event.getRawSlots()) {
            if (raw < SIZE) {
                if (!isOffer(raw)) {
                    event.setCancelled(true);
                    return;
                }
                touchesOffer = true;
            }
        }
        if (!touchesOffer) {
            return;
        }
        if (state.change(window.side)) {
            changed(window.side);
        } else {
            event.setCancelled(true);
        }
    }

    private void shiftIn(Window window, InventoryClickEvent event) {
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.isEmpty() || !fits(window.inventory, moving) || !state.change(window.side)) {
            return;
        }
        ItemStack rest = moving.clone();
        for (int pass = 0; pass < 2 && rest.getAmount() > 0; pass++) {
            for (int slot : OFFER) {
                ItemStack there = window.inventory.getItem(slot);
                boolean empty = there == null || there.isEmpty();
                if (pass == 0 && !empty && there.isSimilar(rest)) {
                    int moved = Math.min(rest.getAmount(), there.getMaxStackSize() - there.getAmount());
                    if (moved > 0) {
                        there.setAmount(there.getAmount() + moved);
                        window.inventory.setItem(slot, there);
                        rest.setAmount(rest.getAmount() - moved);
                    }
                } else if (pass == 1 && empty) {
                    window.inventory.setItem(slot, rest.clone());
                    rest.setAmount(0);
                }
                if (rest.getAmount() == 0) {
                    break;
                }
            }
        }
        event.getView().setItem(event.getRawSlot(), rest.getAmount() == 0 ? null : rest);
        changed(window.side);
    }

    private static boolean fits(Inventory inventory, ItemStack item) {
        for (int slot : OFFER) {
            ItemStack there = inventory.getItem(slot);
            if (there == null || there.isEmpty()
                    || (there.isSimilar(item) && there.getAmount() < there.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOffer(int raw) {
        return raw < 45 && raw % 9 < 4;
    }

    private void toggle(int side) {
        TradeState.Toggle result = state.toggle(side, System.currentTimeMillis());
        boolean moved = result == TradeState.Toggle.CONFIRMED || result == TradeState.Toggle.UNCONFIRMED;
        sound(players[side], moved ? UiSounds.DEFAULTS.click() : UiSounds.DEFAULTS.denied());
        if (state.phase() == TradeState.Phase.COMPLETING) {
            complete();
            return;
        }
        render(side);
        if (moved) {
            dispatch(1 - side, () -> render(1 - side));
        }
    }

    /** A side's offer changed: snapshot it next tick, once vanilla has applied the click. */
    private void changed(int side) {
        if (snapPending[side].compareAndSet(false, true)) {
            tasks.runAtEntityLater(players[side], 1L, () -> snapshot(side));
        }
    }

    private void snapshot(int side) {
        snapPending[side].set(false);
        Inventory inventory = windows[side].inventory;
        if (inventory == null || !state.editable()) {
            return;
        }
        ItemStack[] items = new ItemStack[OFFER.length];
        boolean empty = true;
        for (int i = 0; i < OFFER.length; i++) {
            ItemStack item = inventory.getItem(OFFER[i]);
            if (item != null && !item.isEmpty()) {
                items[i] = item.clone();
                empty = false;
            }
        }
        int revision = state.revision(side);
        snapshots.set(side, new Snapshot(revision, items));
        state.snapped(side, revision, empty);
        render(side);
        dispatch(1 - side, () -> render(1 - side));
    }

    // -------------------------------------------------------------- rendering

    /** Redraws a side's copy of the other offer and its buttons, on that side's thread. */
    private void render(int side) {
        Inventory inventory = windows[side].inventory;
        if (inventory == null || !state.editable()) {
            return;
        }
        Snapshot other = snapshots.get(1 - side);
        for (int i = 0; i < MIRROR.length; i++) {
            inventory.setItem(MIRROR[i], other == null ? null : other.items()[i]);
        }
        state.shown(side, other == null ? 0 : other.revision());
        Snapshot own = snapshots.get(side);
        boolean emptyOwn = own == null || java.util.Arrays.stream(own.items()).allMatch(java.util.Objects::isNull);
        inventory.setItem(CONFIRM, confirmButton(side, emptyOwn));
        inventory.setItem(STATUS, statusButton(side));
        inventory.setItem(INFO, infoItem());
    }

    private ItemStack confirmButton(int side, boolean empty) {
        if (state.confirmed(side)) {
            return Icons.glowing(Material.LIME_DYE, Phrases.tr("{success}&lCONFIRMED"), List.of("",
                    Phrases.tr("{secondary}Status:"),
                    Phrases.tr(" {letters_black}▎ {letters}You {success}confirmed {letters}this trade."),
                    "",
                    Phrases.tr("{warning}➥ Click to take it back"),
                    ""));
        }
        List<String> lore = new ArrayList<>(List.of("",
                Phrases.tr("{secondary}Status:"),
                Phrases.tr(" {letters_black}▎ {letters}You have {error}not confirmed {letters}yet.")));
        if (empty && !options.allowEmpty()) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}Put at least one item in first."));
        }
        lore.addAll(List.of("", Phrases.tr("{warning}➥ Click to confirm your offer"), ""));
        return Icons.button(Material.GRAY_DYE, Phrases.tr("{warning}&lCONFIRM"), lore);
    }

    private ItemStack statusButton(int side) {
        boolean confirmed = state.confirmed(1 - side);
        String name = "{primary}&l" + Text.escape(players[1 - side].getName());
        List<String> lore = List.of("",
                Phrases.tr("{secondary}Status:"),
                confirmed ? Phrases.tr(" {letters_black}▎ {letters}Has {success}confirmed {letters}the trade.")
                        : Phrases.tr(" {letters_black}▎ {letters}Has {error}not confirmed {letters}yet."),
                "");
        return confirmed ? Icons.glowing(Material.LIME_DYE, name, lore) : Icons.button(Material.GRAY_DYE, name, lore);
    }

    private ItemStack infoItem() {
        List<String> lore = new ArrayList<>(List.of("",
                Phrases.tr("{secondary}Information:"),
                Phrases.tr(" {letters_black}▎ {letters}Your offer on the left, theirs on the right."),
                Phrases.tr(" {letters_black}▎ {letters}Any change resets both confirmations.")));
        String extra = info();
        if (extra != null && !extra.isBlank()) {
            lore.add(" {letters_black}▎ " + extra);
        }
        long remaining = state.remaining(System.currentTimeMillis());
        lore.addAll(List.of("", Phrases.tr("{secondary}Status:"),
                remaining > 0
                        ? Phrases.tr(" {letters_black}▎ {letters}Completes in {letters_black}» {info}{0}s ⌚",
                        (remaining + 999) / 1000)
                        : Phrases.tr(" {letters_black}▎ {letters}Waiting for both confirmations."),
                ""));
        return Icons.button(remaining > 0 ? Material.CLOCK : Material.NETHER_STAR, Phrases.tr("{primary}&lTRADE"),
                lore);
    }

    private String info() {
        if (options.info() == null) {
            return null;
        }
        try {
            return options.info().get();
        } catch (RuntimeException failure) {
            Debug.of(plugin).error("Trade: the info line of a trade window failed.", failure);
            return null;
        }
    }

    private static ItemStack filler() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        pane.setItemMeta(meta);
        return pane;
    }

    // ------------------------------------------------------------------ timer

    private void tick() {
        long now = System.currentTimeMillis();
        if (state.tick(now)) {
            complete();
            return;
        }
        if (!options.timeout().isZero() && now - openedAt >= options.timeout().toMillis()) {
            cancel(TradeOutcome.TIMED_OUT, null);
            return;
        }
        // Once a second: the countdown and the caller's info line move.
        long second = now / 1000;
        if (second != shownSecond) {
            shownSecond = second;
            boolean counting = state.phase() == TradeState.Phase.COUNTDOWN;
            for (int side = 0; side < 2; side++) {
                int s = side;
                dispatch(side, () -> {
                    render(s);
                    if (counting) {
                        sound(players[s], UiSounds.DEFAULTS.click());
                    }
                });
            }
        }
    }

    // ----------------------------------------------------------------- ending

    private void complete() {
        for (int side = 0; side < 2; side++) {
            int s = side;
            dispatch(side, () -> empty(s, true));
        }
    }

    private void cancel(TradeOutcome why, UUID by) {
        if (!state.cancel()) {
            return;
        }
        outcome = why;
        cancelledBy = by;
        for (int side = 0; side < 2; side++) {
            int s = side;
            dispatch(side, () -> empty(s, true));
        }
    }

    /** A player's window closed: their close, a quit or a death. On their thread. */
    public void closed(@NotNull Window window, @NotNull InventoryCloseEvent event) {
        int side = window.side;
        Player player = players[side];
        TradeOutcome why = switch (event.getReason()) {
            case DISCONNECT -> TradeOutcome.LEFT;
            case DEATH -> TradeOutcome.DIED;
            default -> player.isDead() ? TradeOutcome.DIED : TradeOutcome.CLOSED;
        };
        cancel(why, ids[side]);
        empty(side, false);
    }

    /** The library's quit hook, for a window the quit did not close. On the player's thread. */
    public void left(@NotNull Player player) {
        int side = ids[0].equals(player.getUniqueId()) ? 0 : 1;
        cancel(TradeOutcome.LEFT, ids[side]);
        empty(side, false);
    }

    /**
     * Empties a side's window once the trade has ended: into the result when
     * it completed, back to the player when it was called off.
     */
    private void empty(int side, boolean close) {
        if (!state.take(side)) {
            return;
        }
        Player player = players[side];
        List<ItemStack> items = takeOffer(side);
        if (close && player.isOnline() && player.getOpenInventory().getTopInventory() == windows[side].inventory
                && windows[side].inventory != null) {
            player.closeInventory();
        }
        if (state.phase() == TradeState.Phase.COMPLETING) {
            taken[side] = items;
            // The items left the player for good: written to the player file now,
            // so a crash before the next autosave cannot load them holding the
            // items again while the escrow also gives them back.
            if (player.isOnline() && tasks.isOwnedBy(player)) {
                try {
                    player.saveData();
                } catch (RuntimeException failure) {
                    Debug.of(plugin).error("Could not save " + player.getName() + "'s data after a trade", failure);
                }
            }
            sound(player, SUCCESS_SOUND);
        } else {
            giveBack(side, items);
        }
        if (state.handed(side)) {
            finish();
        }
    }

    private List<ItemStack> takeOffer(int side) {
        Inventory inventory = windows[side].inventory;
        List<ItemStack> items = new ArrayList<>();
        if (inventory == null) {
            return items;
        }
        for (int slot : OFFER) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty()) {
                items.add(item);
                inventory.setItem(slot, null);
            }
        }
        for (int slot : MIRROR) {
            inventory.setItem(slot, null);
        }
        return items;
    }

    /** Back into the inventory when they are here and alive; the rest through the escrow's owed rows. */
    private void giveBack(int side, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Player player = players[side];
        List<ItemStack> rest = items;
        if (player.isOnline() && !player.isDead()) {
            rest = new ArrayList<>(player.getInventory().addItem(items.toArray(new ItemStack[0])).values());
        }
        if (!rest.isEmpty()) {
            escrow.giveItems(ids[side], rest);
        }
    }

    private void finish() {
        TaskHandle running = timer;
        if (running != null) {
            running.cancel();
        }
        onEnd.accept(this);
        TradeOutcome ended = outcome;
        future.complete(ended.confirmed()
                ? new TradeResult(ended, ids[0], taken[0], ids[1], taken[1], null)
                : new TradeResult(ended, ids[0], List.of(), ids[1], List.of(), cancelledBy));
    }

    /**
     * Ends the trade here and now, on whatever thread the plugin is being
     * disabled on: there is no next tick to schedule onto. Touching the
     * windows and inventories from here is the one exception to the
     * own-thread rule, as it is for a teleport during shutdown.
     */
    public void shutdown() {
        if (state.cancel()) {
            outcome = TradeOutcome.SHUT_DOWN;
        }
        for (int side = 0; side < 2; side++) {
            boolean close = tasks.isOwnedBy(players[side]);
            empty(side, close);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Runs on a side's thread; if the player is gone, runs anyway, against nobody's inventory. */
    private void dispatch(int side, Runnable task) {
        tasks.runAtEntity(players[side], task, task);
    }

    private static void sound(Player player, String sound) {
        if (sound != null && !sound.isBlank() && player.isOnline()) {
            Effects.soundFrom(sound).show(player);
        }
    }
}
