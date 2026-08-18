package net.exylia.lib.input.internal;

import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.SearchInput;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Searchable-choice transport: a chest of results plus an anvil text box.
 *
 * <h2>Why the results are not drawn in the player's own inventory</h2>
 * A search that filters as you type wants a text field and a result list on
 * screen at once, and the anvil only owns three slots. The tempting shortcut is
 * to render the matches into the bottom half of the anvil view. It is
 * <strong>destructive</strong>, and this transport is shaped specifically to
 * avoid it.
 *
 * <p>Verified against paper-api 1.21.4: {@code InventoryView#setItem(int)} takes
 * a raw slot, and any raw slot past the top inventory addresses the player's real
 * {@code PlayerInventory}. Drawing a result there does not decorate the screen,
 * it <em>deletes the stack that was in that slot</em>, with nothing left to
 * restore from. Faking the bottom 36 slots with packets instead avoids the write
 * but not the loss: those slots stay server-authoritative, so the client clicks
 * carrying an item the server does not believe in, and item-duplication or
 * item-loss follows from the resulting desync in cursor and container state.
 *
 * <p>Therefore: <strong>results live in a plugin-owned chest top inventory, the
 * anvil is used only as a text field, and every click and drag in either window
 * is cancelled before anything else runs.</strong> No code path in this
 * transport reads or writes {@code Player#getInventory()}.
 *
 * <h2>Why the anvil's query item can never be kept</h2>
 * An anvil's rename box is inert unless top slot 0 holds an item, so the
 * transport puts a disposable, plugin-created stack there. Vanilla returns anvil
 * input items to the player when the view closes; left alone, that hands the
 * player a free item every single search. All three anvil slots are therefore
 * cleared <em>before</em> the window is closed, on every ending: the player
 * confirming, the player closing, the session ending for any reason, disconnect,
 * plugin disable, and server stop. See {@link #clearAnvil(Player)}.
 *
 * @since 1.31.0
 */
public final class SearchTransport implements Transport {

    /** Anvil top inventory: 0 first input, 1 second input, 2 result. */
    private static final int ANVIL_SLOTS = 3;
    private static final int ANVIL_RESULT_SLOT = 2;

    private final Plugin plugin;

    /**
     * Sessions whose window is being replaced by us rather than abandoned.
     *
     * <p>Chest to anvil and anvil back to chest both fire a close event for the
     * old window. Without this marker the transition would read as the player
     * giving up and cancel the session mid-search. The marker is added before
     * Bukkit is asked to open or close anything and removed after, exactly as
     * {@link MenuTransport} does.
     */
    private final Set<UUID> programmaticCloses = ConcurrentHashMap.newKeySet();

    /** Anvil views we own, by player, so close and prepare events can find them. */
    private final Map<UUID, AnvilQuery> anvils = new ConcurrentHashMap<>();

    /** Reflective entry point used by {@link InputRuntime}'s transport discovery. */
    public SearchTransport(@NotNull Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull TransportKind kind() {
        return TransportKind.ANVIL_SEARCH;
    }

    /**
     * Opens the result chest, or declines so the runtime can fall back.
     *
     * <p>Declines rather than throws when the player is gone or the request is
     * not searchable; an absent precondition is expected, not exceptional.
     */
    @Override
    public boolean show(@NotNull InputSession session) {
        java.util.Objects.requireNonNull(session, "session");
        if (!(session.request() instanceof SearchInput<?> request)) {
            return false;
        }
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (request.choices().isEmpty()) {
            return false;
        }

        SearchView<?> view = SearchView.create(session, request);
        openChest(session, player, view, false);
        return true;
    }

    /**
     * Removes whatever this transport is showing, for any ending.
     *
     * <p>Idempotent and silent by contract: completion, timeout, disconnect,
     * replacement, and shutdown race each other, so this can be called after the
     * window already vanished.
     *
     * <p>Ownership is decided <em>before</em> the anvil record is dropped, and
     * the slots are emptied before the window is closed. Clearing first would
     * erase the very state that proves the open window is ours, leaving a
     * plugin-owned anvil on screen after its session ended; closing first would
     * let vanilla hand the player the query item on the way out.
     */
    @Override
    public void close(@NotNull InputSession session) {
        java.util.Objects.requireNonNull(session, "session");
        Player player = Bukkit.getPlayer(session.playerId());
        try {
            if (player == null) {
                // Offline: the view is gone with the connection. Drop our record
                // so a rejoining player cannot inherit a stale anvil.
                anvils.remove(session.playerId());
                return;
            }
            boolean owned = ownsOpenWindow(player, session);
            clearAnvil(player);
            if (!player.isOnline()) {
                return;
            }
            if (owned) {
                programmaticCloses.add(session.id());
                try {
                    player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
                } finally {
                    programmaticCloses.remove(session.id());
                }
            }
        } catch (Throwable ignored) {
            // Contract: close never throws. A failure here must not stop the
            // runtime from completing the caller's future.
        } finally {
            anvils.remove(session.playerId());
            programmaticCloses.remove(session.id());
        }
    }

    // ------------------------------------------------------------------
    // Event entry points — called by InputListener.
    // ------------------------------------------------------------------

    /**
     * Handles a click in either of our windows.
     *
     * <p>Cancellation is unconditional and happens first, before any decision
     * about what the click meant. Every click type — including ones this Bukkit
     * version does not yet define — therefore fails closed: shift-click, number
     * key, offhand swap, double-click collect, drop, and creative middle-click
     * cannot move a single item. Only a plain left or right click on a slot we
     * actually drew is then interpreted as a button press.
     *
     * @param event the click, already identified as belonging to us
     */
    public void click(@NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        AnvilQuery anvil = anvils.get(player.getUniqueId());
        if (anvil != null && anvil.isView(event.getView())) {
            anvilClick(event, player, anvil);
            return;
        }
        SearchView<?> view = viewOf(event.getView().getTopInventory());
        if (view != null) {
            chestClick(event, player, view);
        }
    }

    /**
     * Cancels every drag touching one of our windows.
     *
     * <p>Includes drags whose slots are all in the player's own inventory: a
     * drag started inside our view and finished below it is still a drag the
     * client sequenced against a container we control.
     */
    public void drag(@NotNull InventoryDragEvent event) {
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
    }

    /**
     * Reads a genuine window close as the player giving up.
     *
     * <p>Our own transitions also close a window, so the session id is consumed
     * from {@link #programmaticCloses} first. When the anvil closes, its slots
     * are emptied here as well, which is the disconnect and shutdown path: those
     * both fire a close event, and clearing before vanilla's return-items step
     * is what stops the query item from being handed over.
     */
    public void closed(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        AnvilQuery anvil = anvils.get(player.getUniqueId());
        if (anvil != null && anvil.isView(event.getView())) {
            anvilClosed(player, anvil);
            return;
        }
        SearchView<?> view = viewOf(event.getInventory());
        if (view == null) {
            return;
        }
        InputSession session = view.session();
        if (programmaticCloses.remove(session.id())) {
            return;
        }
        if (InputRuntime.active(session.playerId()) == session
                && session.transportKind() == TransportKind.ANVIL_SEARCH) {
            session.end(InputOutcome.CANCELLED);
        }
    }

    /**
     * Filters live on each keystroke and keeps the anvil free.
     *
     * <p>{@code PrepareAnvilEvent} fires once per typed character. The rename
     * text is read, the match count is recomputed against already-normalized
     * strings, and the repair cost is forced to zero so the player is never
     * charged experience for typing. The result slot carries a confirm button
     * rather than a renamed copy of the query item, so what the player clicks to
     * accept is unmistakably ours.
     */
    public void prepare(@NotNull PrepareAnvilEvent event) {
        AnvilView view = event.getView();
        if (!(view.getPlayer() instanceof Player player)) {
            return;
        }
        AnvilQuery anvil = anvils.get(player.getUniqueId());
        if (anvil == null || !anvil.isView(view)) {
            return;
        }

        String typed = view.getRenameText();
        anvil.typed(typed);

        // No XP cost: this is a text box, not a repair. Only the cost itself is
        // zeroed. Zeroing the maximum as well would put every result at or above
        // the limit, and the client renders that as "Too Expensive" with the
        // result slot greyed out — the confirm button would stop being clickable.
        view.setRepairCost(0);

        int count = anvil.view().previewCount(typed);
        event.setResult(confirmItem(typed, count));
    }

    // ------------------------------------------------------------------
    // Chest behaviour.
    // ------------------------------------------------------------------

    private void chestClick(InventoryClickEvent event, Player player, SearchView<?> view) {
        InputSession session = view.session();
        if (InputRuntime.active(player.getUniqueId()) != session
                || session.transportKind() != TransportKind.ANVIL_SEARCH) {
            return;
        }

        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SearchView.INVENTORY_SIZE) {
            return;
        }

        switch (slot) {
            case SearchView.CANCEL_SLOT -> session.end(InputOutcome.CANCELLED);
            case SearchView.SEARCH_SLOT -> openAnvil(session, player, view);
            case SearchView.CLEAR_SLOT -> {
                if (!view.query().isEmpty()) {
                    view.query("");
                    view.draw();
                }
            }
            case SearchView.PREVIOUS_SLOT -> {
                if (view.hasPrevious()) {
                    view.movePage(-1);
                    view.draw();
                }
            }
            case SearchView.NEXT_SLOT -> {
                if (view.hasNext()) {
                    view.movePage(1);
                    view.draw();
                }
            }
            case SearchView.INFO_SLOT -> {
                // A label, not a button.
            }
            default -> select(session, player, view, slot);
        }
    }

    /** Resolves the clicked slot through the request's own key and parser. */
    private static <T> void select(InputSession session, Player player,
                                   SearchView<T> view, int slot) {
        T value = view.entryAt(slot);
        if (value == null) {
            return;
        }
        SearchInput<T> request = view.request();
        final InputParser.Parsed<T> parsed;
        try {
            parsed = request.parseRaw(request.keyOf(value));
        } catch (RuntimeException failure) {
            Text.of("{error}That option could not be read.").send(player);
            return;
        }
        if (parsed.ok() && parsed.value() != null) {
            session.complete(parsed.value());
            return;
        }
        Text.of("{error}%error%")
                .with("%error%", parsed.error() == null
                        ? "That option is not accepted." : parsed.error())
                .send(player);
    }

    /**
     * Shows the result chest.
     *
     * <p>Paging and clearing do <em>not</em> come through here: they mutate the
     * same inventory and call {@link SearchView#draw()}, so the window is never
     * reopened and the client sees only the slots that changed. Reopening is
     * reserved for genuinely returning from the anvil.
     *
     * @param replacing whether another window of ours is being taken over, in
     *                  which case its close must not read as a cancel
     */
    private void openChest(InputSession session, Player player, SearchView<?> view, boolean replacing) {
        if (replacing) {
            programmaticCloses.add(session.id());
        }
        try {
            player.openInventory(view.getInventory());
        } finally {
            if (replacing) {
                programmaticCloses.remove(session.id());
            }
        }
    }

    // ------------------------------------------------------------------
    // Anvil behaviour.
    // ------------------------------------------------------------------

    /**
     * Opens the anvil text box for the chest that is currently showing.
     *
     * <p>{@code checkReachable(false)} is required: without it the server
     * validates that the player stands next to a real anvil block and closes the
     * view immediately, which would make search silently do nothing.
     */
    private void openAnvil(InputSession session, Player player, SearchView<?> view) {
        AnvilView anvilView;
        try {
            anvilView = MenuType.ANVIL.builder()
                    .title(Text.of("{primary}Search").build())
                    .checkReachable(false)
                    .build(player);
        } catch (Throwable unsupported) {
            // No anvil menu on this server: stay on the chest rather than
            // stranding the player in a window that never opened.
            Text.of("{error}The search box is unavailable here.").send(player);
            return;
        }

        AnvilQuery query = new AnvilQuery(session, view, anvilView);
        anvils.put(player.getUniqueId(), query);

        programmaticCloses.add(session.id());
        try {
            // The rename box is inert without an item in slot 0. This stack is
            // disposable and is removed before any close; see clearAnvil.
            anvilView.getTopInventory().setItem(0, queryItem(view.query()));
            anvilView.setRepairCost(0);
            player.openInventory(anvilView);
        } catch (Throwable failure) {
            anvils.remove(player.getUniqueId());
            clearAnvilSlots(anvilView);
            openChest(session, player, view, false);
        } finally {
            programmaticCloses.remove(session.id());
        }
    }

    private void anvilClick(InventoryClickEvent event, Player player, AnvilQuery anvil) {
        InputSession session = anvil.session();
        if (InputRuntime.active(player.getUniqueId()) != session
                || session.transportKind() != TransportKind.ANVIL_SEARCH) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        if (event.getRawSlot() != ANVIL_RESULT_SLOT) {
            return;
        }
        applyQuery(player, anvil, anvil.typed());
    }

    /**
     * Returns to the chest when the anvil closes for any reason.
     *
     * <p>Closing without confirming still applies what was typed: the player
     * pressed escape on a text box, not on the search, and re-typing a query
     * they already finished is pure friction. The session is untouched here —
     * only the chest's own close cancels.
     */
    private void anvilClosed(Player player, AnvilQuery anvil) {
        anvils.remove(player.getUniqueId());
        clearAnvilSlots(anvil.anvilView());

        InputSession session = anvil.session();
        boolean programmatic = programmaticCloses.remove(session.id());
        if (session.terminalResult() != null
                || InputRuntime.active(session.playerId()) != session) {
            return;
        }
        if (programmatic) {
            // We closed it ourselves in applyQuery; the chest is already coming.
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        anvil.view().query(anvil.typed());
        reopenChest(session, player, anvil.view());
    }

    /**
     * Applies the typed query and goes back to the results.
     *
     * <p>The anvil is emptied before it is closed, which is the whole reason the
     * player never receives the query item: vanilla's "give the inputs back"
     * step runs on a container that no longer holds anything.
     */
    private void applyQuery(Player player, AnvilQuery anvil, String typed) {
        InputSession session = anvil.session();
        anvils.remove(player.getUniqueId());
        anvil.view().query(typed);

        clearAnvilSlots(anvil.anvilView());
        programmaticCloses.add(session.id());
        try {
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        } finally {
            programmaticCloses.remove(session.id());
        }
        reopenChest(session, player, anvil.view());
    }

    /**
     * Reopens the chest on the tick after the anvil closed.
     *
     * <p>Opening an inventory from inside a close handler is refused by the
     * server, so this hops one tick through the task module. It is scheduled on
     * the player's own thread, which is what keeps it correct on Folia.
     */
    private void reopenChest(InputSession session, Player player, SearchView<?> view) {
        Runnable reopen = () -> {
            if (!player.isOnline()
                    || session.terminalResult() != null
                    || InputRuntime.active(session.playerId()) != session) {
                return;
            }
            view.draw();
            openChest(session, player, view, true);
        };
        try {
            Tasks.of(plugin).runAtEntityLater(player, 1L, reopen);
        } catch (Throwable schedulingFailure) {
            session.end(InputOutcome.UNAVAILABLE);
        }
    }

    /**
     * Empties every anvil slot this player may have open with us.
     *
     * <p>Called from {@link #close(InputSession)} so the guarantee also covers
     * endings that never produce a close event we handle: timeout, replacement,
     * plugin disable, and server stop all route through the runtime's terminal
     * cleanup.
     */
    private void clearAnvil(Player player) {
        AnvilQuery anvil = anvils.remove(player.getUniqueId());
        if (anvil != null) {
            clearAnvilSlots(anvil.anvilView());
        }
    }

    /**
     * Blanks the three anvil slots.
     *
     * <p>Runs before every close, and is safe to run twice. Skipping it once
     * would hand the player a free item, so it is deliberately unconditional
     * rather than guarded by "did we still own this view".
     */
    private static void clearAnvilSlots(AnvilView view) {
        try {
            Inventory top = view.getTopInventory();
            for (int slot = 0; slot < ANVIL_SLOTS; slot++) {
                top.setItem(slot, null);
            }
        } catch (Throwable ignored) {
            // A view already discarded by the server holds nothing to return.
        }
    }

    // ------------------------------------------------------------------
    // Lookup and items.
    // ------------------------------------------------------------------

    /**
     * Finds the transport that owns an inventory, from server-held holder state.
     *
     * <p>Mirrors {@link MenuTransport#transportOf(Inventory)} so the listener can
     * keep cancelling clicks in a stale window during the gap between terminal
     * arbitration and scheduled cleanup.
     *
     * @param inventory the top inventory of the clicked view
     * @return the owning transport, or {@code null} when it is not ours
     */
    public static @Nullable SearchTransport transportOf(@NotNull Inventory inventory) {
        SearchView<?> view = viewOf(inventory);
        if (view == null) {
            return null;
        }
        Transport transport = view.session().transport();
        return transport instanceof SearchTransport search ? search : null;
    }

    /**
     * Finds the transport owning an anvil this player has open with us.
     *
     * <p>An anvil's top inventory is server-created, so it carries no holder of
     * ours to recognise; identity of the view is the authority instead.
     *
     * @param player the viewing player
     * @param view   the view being examined
     * @return the owning transport, or {@code null}
     */
    public static @Nullable SearchTransport anvilTransportOf(@NotNull Player player,
                                                             @NotNull InventoryView view) {
        InputSession session = InputRuntime.active(player.getUniqueId());
        if (session == null || session.transportKind() != TransportKind.ANVIL_SEARCH) {
            return null;
        }
        if (!(session.transport() instanceof SearchTransport search)) {
            return null;
        }
        AnvilQuery anvil = search.anvils.get(player.getUniqueId());
        return anvil != null && anvil.isView(view) ? search : null;
    }

    private static @Nullable SearchView<?> viewOf(Inventory inventory) {
        return inventory.getHolder(false) instanceof SearchView<?> view ? view : null;
    }

    /**
     * Whether the window the player is looking at is this session's.
     *
     * <p>Checked before closing anything so terminal cleanup cannot shut a
     * window that already belongs to something else — a player who cancelled and
     * immediately opened their own chest must keep that chest.
     */
    private boolean ownsOpenWindow(Player player, InputSession session) {
        InventoryView open = player.getOpenInventory();
        AnvilQuery anvil = anvils.get(player.getUniqueId());
        if (anvil != null && anvil.session() == session && anvil.isView(open)) {
            return true;
        }
        SearchView<?> view = viewOf(open.getTopInventory());
        return view != null && view.session() == session;
    }

    /** The disposable stack that makes the rename box usable. */
    private static ItemStack queryItem(String current) {
        return named(Material.PAPER, current.isEmpty()
                ? Text.of("{letters}Type to search").build()
                : Text.of("%query%").with("%query%", current).build());
    }

    private static ItemStack confirmItem(String typed, int matches) {
        Component name = typed == null || typed.isBlank()
                ? Text.of("{muted}Type to filter").build()
                : Text.of("{success}&l%count% {letters}match(es)")
                        .with("%count%", matches).build();
        return named(matches == 0 && typed != null && !typed.isBlank()
                ? Material.BARRIER : Material.NAME_TAG, name);
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * One open anvil, remembered so its events can be recognised and its slots
     * emptied even after the player stopped looking at it.
     */
    private static final class AnvilQuery {

        private final InputSession session;
        private final SearchView<?> view;
        private final AnvilView anvilView;
        private volatile String typed = "";

        private AnvilQuery(InputSession session, SearchView<?> view, AnvilView anvilView) {
            this.session = session;
            this.view = view;
            this.anvilView = anvilView;
        }

        private InputSession session() {
            return session;
        }

        private SearchView<?> view() {
            return view;
        }

        private AnvilView anvilView() {
            return anvilView;
        }

        private String typed() {
            return typed;
        }

        private void typed(@Nullable String text) {
            this.typed = text == null ? "" : text;
        }

        private boolean isView(InventoryView candidate) {
            return anvilView == candidate
                    || anvilView.getTopInventory() == candidate.getTopInventory();
        }
    }
}
