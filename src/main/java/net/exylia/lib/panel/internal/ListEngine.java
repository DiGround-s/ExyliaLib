package net.exylia.lib.panel.internal;

import net.exylia.lib.input.InputResult;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.Items;
import net.exylia.lib.panel.FieldDescriptor;
import net.exylia.lib.panel.PanelDiff;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.ui.Pages;
import net.exylia.lib.ui.UiEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The list panel: one paginated editor for every element type.
 *
 * <p>ExyliaCommons had five of these — rewards, potions, commands, messages,
 * items — copy-pasted from each other and drifting apart. Four addressed a row
 * by a UUID; the potion one addressed it by list index, so pagination plus a
 * deletion removed the wrong effect. Here there is exactly one implementation,
 * parameterised by a {@link FieldDescriptor}: supporting a new element type is a
 * descriptor and nothing else — no panel, no menu, no session, no registry, no
 * clipboard class.
 *
 * <h2>A row is addressed by what it carries</h2>
 * Every drawn row is a {@link UiEntry} carrying its element, and every operation
 * — edit, copy, delete — resolves its target from that carried value. No slot
 * number, page number or list index is ever entry identity. That is not
 * tidiness: it is the only thing that stays correct when the list is filtered,
 * paginated, or reordered under a screen somebody is already looking at.
 *
 * <h2>Nothing is written until save</h2>
 * Every operation lands in a working copy owned by the {@link Session}. Cancel
 * discards the whole of it — deletions, pastes and edits alike — and a save with
 * an empty {@link PanelDiff} writes nothing at all.
 *
 * <h2>Threads</h2>
 * The working copy, the filter and the diff touch no Bukkit API and are safe
 * anywhere. Drawing and click handling belong on the thread that owns the
 * viewer, which is where an inventory event already is. The write is
 * {@code runAsync} → {@link FieldDescriptor#save} → back via {@code runAtEntity}.
 *
 * @param <T> the element type being edited
 */
@ApiStatus.Internal
public final class ListEngine<T> {

    private final Plugin plugin;
    private final Player viewer;
    private final FieldDescriptor<T> descriptor;
    private final @Nullable Consumer<List<T>> onSaved;

    /**
     * The session this panel is: the working copy, the undo stack, the
     * clipboard, and everything given back when the screen goes.
     *
     * <p>Deliberately no second working copy here. The session already is one,
     * and two would drift the moment a plugin took back an operation through
     * {@link net.exylia.lib.panel.PanelSession#undo()}.
     */
    private final Session session;

    /**
     * What each drawn slot is about.
     *
     * <p>Rebuilt on every draw, so a redraw is never stale, and read on every
     * click, so a click resolves against what the server drew rather than
     * against what the client says it clicked.
     */
    private final Map<Integer, UiEntry> rows = new LinkedHashMap<>();

    /** The buttons, by slot, so a click on one is recognised as a button. */
    private final Map<Integer, ControlKind> chrome = new LinkedHashMap<>();

    /** What a viewer typed into the search, or empty for no filter. */
    private String query = "";

    /** Which page is on screen, one-based, as {@link Pages} counts them. */
    private int page = 1;

    private ListEngine(Plugin plugin, Player viewer, FieldDescriptor<T> descriptor,
                       @Nullable Consumer<List<T>> onSaved) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.descriptor = descriptor;
        this.onSaved = onSaved;
        this.session = Session.of(PanelRuntime.of(plugin), viewer,
                ListEntries.wrap(descriptor.load()), this::write);
    }

    /**
     * Starts editing a list.
     *
     * @param plugin     who owns it
     * @param viewer     who is editing
     * @param descriptor everything the panel needs to know about the elements
     * @param onSaved    run with the persisted list afterwards, or {@code null}
     */
    public static <T> @NotNull ListEngine<T> of(@NotNull Plugin plugin, @NotNull Player viewer,
                                                @NotNull FieldDescriptor<T> descriptor,
                                                @Nullable Consumer<List<T>> onSaved) {
        return new ListEngine<>(plugin, viewer, descriptor, onSaved);
    }

    /** The session a click is validated against, and a caller can save or cancel. */
    public @NotNull Session session() {
        return session;
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /**
     * Shows the panel and draws its first page.
     *
     * <p>Must be called on the thread that owns the viewer;
     * {@code ListPanel.open} relocates there first, which is what makes the
     * public entry point safe from anywhere.
     *
     * @param title the title to show, or {@code null} for the default
     */
    @SuppressWarnings("deprecation")
    public void open(@Nullable String title) {
        PanelHolder holder = new PanelHolder();
        // The legacy-string overload deliberately, and for the reason
        // ui/internal/Session gives at the same call: the component-taking one
        // is Paper's, and this library has to load on pure Spigot.
        Inventory window = Bukkit.createInventory(holder, Layouts.SIZE,
                Text.of(title == null ? "{primary}&lLIST" : title).legacy());
        holder.bind(session, window);
        // Bound before anything is drawn, so a click arriving during the draw
        // finds a session rather than nothing.
        session.bind(window, this::activateHere);

        draw();
        viewer.openInventory(window);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * Draws the current page and its buttons.
     *
     * <p>Announces every slot through {@link PanelRenderer}, which is what a test
     * asserts against: an {@code ItemStack} cannot be built without a running
     * server, so what went where has to be observable separately.
     */
    public void draw() {
        drawRows();
        drawChrome();
    }

    /**
     * Draws only the rows.
     *
     * <p>Separate from the buttons because paging changes the list and nothing
     * else: re-sending eight identical buttons is packets for items that did not
     * move, and a client that receives them repaints them.
     */
    private void drawRows() {
        rows.clear();
        clearRowSlots();
        List<T> visible = shown();
        for (int index = 0; index < visible.size() && index < Layouts.LIST_ROW_SLOTS; index++) {
            T element = visible.get(index);
            UiEntry row = rowFor(element);
            rows.put(index, row);
            PanelRenderer.drew(index, ControlKind.ROW, row);
            put(index, itemFor(element));
        }
        if (visible.isEmpty()) {
            drawEmpty();
        }
    }

    /**
     * Draws the explanation a list with nothing in it owes its viewer.
     *
     * <p>The pagination filler and the background filler are two different
     * things (see {@code AGENTS.md} §Menús): the background is decoration, and
     * this one <em>says why</em>. A viewer whose search matched nothing has a
     * search to clear; one whose list is empty has an entry to add. Drawing the
     * background here would leave both of them staring at grey glass.
     */
    private void drawEmpty() {
        int slot = Layouts.LIST_ROW_SLOTS / 2;
        PanelRenderer.drew(slot, ControlKind.EMPTY, null);
        put(slot, Item.of("BARRIER")
                .name("{warning}&lNOTHING TO SHOW")
                .lore(List.of(" {letters_black}▎ {letters}" + emptyReason()))
                .build());
    }

    /**
     * Why the list is showing nothing.
     *
     * <p>Two different situations, said two different ways on purpose: a viewer
     * who cannot tell them apart clears a search that was not the problem, or
     * looks for a search that was never set.
     *
     * @return a sentence naming the reason; never {@code null}
     */
    public @NotNull String emptyReason() {
        return query.isEmpty()
                ? "This list is empty. Use add to start one."
                : "No entry matches your search. Clear it to see them all.";
    }

    private void drawChrome() {
        chrome.clear();
        chrome.put(Layouts.CANCEL_SLOT, ControlKind.CANCEL);
        chrome.put(Layouts.SEARCH_SLOT, ControlKind.SEARCH);
        chrome.put(Layouts.UNDO_SLOT, ControlKind.UNDO);
        chrome.put(Layouts.PREVIOUS_SLOT, ControlKind.PAGE_PREVIOUS);
        chrome.put(Layouts.SAVE_SLOT, ControlKind.SAVE);
        chrome.put(Layouts.NEXT_SLOT, ControlKind.PAGE_NEXT);
        chrome.put(Layouts.ADD_SLOT, ControlKind.ADD);
        chrome.put(Layouts.PASTE_SLOT, ControlKind.PASTE);
        for (Map.Entry<Integer, ControlKind> button : chrome.entrySet()) {
            PanelRenderer.drew(button.getKey(), button.getValue(), null);
            put(button.getKey(), chromeItem(button.getValue()));
        }
    }

    /**
     * The row an element is drawn as.
     *
     * <p>{@link UiEntry#of} is what makes the element travel with the row rather
     * than having to be worked back out of the item it became. That is the whole
     * of the identity contract, and it is one call.
     */
    private UiEntry rowFor(T element) {
        return UiEntry.of(element)
                .with("entry_label", descriptor.label(element))
                .with("entry_identity", descriptor.identity(element))
                .build();
    }

    private Item itemFor(T element) {
        return Item.of(descriptor.icon(element))
                .name("{primary}&l" + descriptor.label(element))
                .lore(List.of(
                        "",
                        "{warning}➥ Left-click to edit",
                        "{warning}➥ Shift-left to copy",
                        "{warning}➥ Right-click to delete"))
                .build();
    }

    /**
     * The button a chrome slot is drawn as.
     *
     * <p>A default, not a decision: an owner retheming panels replaces the
     * layout, and nothing here is written into control flow.
     */
    private static Item chromeItem(ControlKind kind) {
        return switch (kind) {
            case SAVE -> Item.of("LIME_DYE").name("{success}&lSAVE").build();
            case CANCEL -> Item.of("BARRIER").name("{error}&lCANCEL").build();
            case UNDO -> Item.of("CLOCK").name("{warning}&lUNDO").build();
            case SEARCH -> Item.of("COMPASS").name("{info}&lSEARCH").build();
            case ADD -> Item.of("EMERALD").name("{success}&lADD").build();
            case PASTE -> Item.of("BOOK").name("{secondary}&lPASTE").build();
            case PAGE_PREVIOUS -> Item.of("ARROW").name("{secondary}&lPREVIOUS").build();
            case PAGE_NEXT -> Item.of("ARROW").name("{secondary}&lNEXT").build();
            default -> Item.of("GRAY_DYE").name("{neutral}&l-").build();
        };
    }

    /**
     * Puts an item in a slot, when there is a window to put it in.
     *
     * <p>A session with no window draws nothing, deliberately: a test cannot open
     * one, because {@code Bukkit.createInventory} answers nothing without a
     * running server and an {@code ItemStack} cannot even class-initialise. What
     * a test asserts on is the draw sink, which has already been told.
     */
    private void put(int slot, Item item) {
        Inventory window = session.window();
        if (window == null) {
            return;
        }
        window.setItem(slot, Items.of(plugin).render(item, viewer));
    }

    /**
     * Blanks the row area before redrawing it.
     *
     * <p>Without this, a page with fewer rows than the one before leaves the
     * previous page's items in the slots it did not reach — which reads as rows
     * that cannot be clicked, since {@link #rows} no longer knows about them.
     */
    private void clearRowSlots() {
        Inventory window = session.window();
        if (window == null) {
            return;
        }
        for (int slot = 0; slot < Layouts.LIST_ROW_SLOTS; slot++) {
            window.setItem(slot, null);
        }
    }

    // ------------------------------------------------------------------
    // Clicking
    // ------------------------------------------------------------------

    /** Answers a click routed by {@link PanelListener}, with the kind it recorded. */
    private boolean activateHere(int slot) {
        return activate(slot, session.observedClick());
    }

    /**
     * Acts on a click in a slot.
     *
     * <p>Resolved against what this engine drew, never against the item the
     * client says it clicked: a packet carries a slot number and a button, and
     * the server already knows what it put there. A slot with no row is not an
     * empty row — it is not a row.
     *
     * @param slot  the raw slot clicked
     * @param click which button
     * @return whether the click meant anything
     */
    public boolean activate(int slot, @NotNull ClickKind click) {
        ControlKind button = chrome.get(slot);
        if (button != null) {
            return chrome(button);
        }
        UiEntry row = rows.get(slot);
        if (row == null) {
            return false;
        }
        // The element the row carries. Never the element at the row's index —
        // see the class documentation, and ListEntryIdentityTest.
        @SuppressWarnings("unchecked")
        T element = (T) row.value();
        if (element == null) {
            return false;
        }
        return switch (click) {
            case RIGHT, SHIFT_RIGHT -> {
                delete(element);
                yield true;
            }
            case SHIFT_LEFT -> {
                copy(element);
                yield true;
            }
            default -> {
                edit(element);
                yield true;
            }
        };
    }

    private boolean chrome(ControlKind button) {
        return switch (button) {
            case SAVE -> save();
            case CANCEL -> {
                session.cancel();
                yield true;
            }
            case UNDO -> undo();
            case ADD -> {
                commit(added(entries(), descriptor.create()));
                yield true;
            }
            case PASTE -> paste();
            case SEARCH -> search();
            case PAGE_NEXT -> turn(page + 1);
            case PAGE_PREVIOUS -> turn(page - 1);
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /**
     * Asks the viewer to change an element, and applies what comes back.
     *
     * <p>The element is captured, not its position: by the time the answer
     * arrives the list may have been paged, filtered or edited, and the row that
     * was clicked may sit somewhere else entirely.
     */
    private void edit(T element) {
        descriptor.edit(viewer, element).thenAccept(result -> onAnswer(result, replacement ->
                commit(replaced(entries(), element, replacement))));
    }

    /**
     * Copies an element onto this screen's clipboard.
     *
     * <p>Not an edit: nothing about the list changed, so nothing is pushed onto
     * undo. A viewer who copies and then undoes expects their last real change
     * back, not the copy taken away.
     */
    private void copy(T element) {
        session.clipboard(element);
    }

    /**
     * Adds a distinct copy of whatever is on the clipboard.
     *
     * <p>An empty clipboard is a no-op and not an error: pressing paste before
     * copying is an ordinary thing a person does, and it must cost them nothing.
     *
     * <p>{@link FieldDescriptor#duplicate} rather than the element itself,
     * because a paste that shared an identity with its original would give the
     * list two rows that address each other's deletes.
     */
    private boolean paste() {
        Object copied = session.clipboard();
        if (copied == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        T element = (T) copied;
        commit(added(entries(), descriptor.duplicate(element)));
        return true;
    }

    /**
     * Removes an element, once the viewer has said so.
     *
     * <p>Asked dangerously — typed rather than clicked — because removing a row a
     * server owner configured is not something a misclick may do. An unanswered
     * question is not a yes, which is why only a completed {@code true} removes
     * anything: most confirmations end in a timeout, not a denial.
     */
    private void delete(T element) {
        PanelPrompts.get()
                .confirm(plugin, viewer, "Delete " + descriptor.label(element) + "?", true)
                .thenAccept(result -> onAnswer(result, confirmed -> {
                    if (Boolean.TRUE.equals(confirmed)) {
                        commit(removed(entries(), element));
                    }
                }));
    }

    /**
     * Offers the whole list through the input module's search.
     *
     * <p>The existing {@code SearchInput}, through {@link PanelPrompts}, rather
     * than a second search written here: it already solved paging, filtering and
     * every transport, and the engine never calls {@code Inputs} directly so a
     * test can script the answer.
     *
     * <p>Offered every entry rather than the page on screen: searching what you
     * can already see is not searching.
     */
    private boolean search() {
        List<T> all = entries();
        if (all.isEmpty()) {
            return false;
        }
        PanelPrompts.get()
                .search(plugin, viewer, "Search", all, descriptor::label)
                .thenAccept(result -> onAnswer(result, chosen -> filter(descriptor.label(chosen))));
        return true;
    }

    /**
     * Narrows what is shown.
     *
     * <p>The view only. The working copy is untouched, which is what makes
     * clearing a search free and stops a save after a search from persisting a
     * truncated list — silently, because the screen would look exactly right.
     *
     * <p>Not an undo step either: a viewer taking back their last change must
     * get the change back, not the search.
     *
     * @param query what the viewer typed, or empty to show everything
     */
    public void filter(@NotNull String query) {
        this.query = query.trim();
        // Back to the first page: staying on page three of a result with one page
        // shows an empty screen for a search that matched.
        this.page = 1;
        drawRows();
    }

    /**
     * Turns to a page.
     *
     * <p>Clamped by {@link Pages} rather than refused, so paging past the end
     * shows the last page that exists. The number is computed here and never
     * taken from a caller: the list already knows how many rows it has and which
     * page it is on, and asking the caller is asking them to recompute it.
     */
    private boolean turn(int wanted) {
        int next = Pages.clamp(wanted, filtered().size(), Layouts.LIST_ROW_SLOTS);
        if (next == page) {
            return false;
        }
        page = next;
        // Only the rows. A page change changes the list and nothing else.
        drawRows();
        return true;
    }

    // ------------------------------------------------------------------
    // The working copy
    // ------------------------------------------------------------------

    /** Everything the panel is holding, in order. */
    public @NotNull List<T> entries() {
        return ListEntries.unwrap(session.values());
    }

    /** What is on screen right now: this page of the filtered view. */
    public @NotNull List<T> shown() {
        return Pages.slice(filtered(), page, Layouts.LIST_ROW_SLOTS);
    }

    /** The whole filtered view, across every page. */
    private List<T> filtered() {
        if (query.isEmpty()) {
            return entries();
        }
        List<T> matches = new ArrayList<>();
        for (T element : entries()) {
            if (descriptor.matches(element, query)) {
                matches.add(element);
            }
        }
        return List.copyOf(matches);
    }

    /** How many pages the filtered view needs; at least one. */
    public int pages() {
        return Pages.count(filtered().size(), Layouts.LIST_ROW_SLOTS);
    }

    /** Which page is on screen, one-based. */
    public int page() {
        return page;
    }

    /**
     * The element a slot's row carries, or {@code null} when nothing was drawn
     * there.
     *
     * <p>The read a click makes, exposed so a test can assert that the two agree
     * — which is the identity contract stated as one equality.
     */
    @SuppressWarnings("unchecked")
    public @Nullable T entryAt(int slot) {
        UiEntry row = rows.get(slot);
        return row == null ? null : (T) row.value();
    }

    /** Whether this screen has something copied. */
    public boolean hasClipboard() {
        return session.clipboard() != null;
    }

    /** How many operations can still be taken back. */
    public int undoDepth() {
        return session.undoDepth();
    }

    /**
     * Takes back the last operation, redrawing what it restored.
     *
     * @return whether there was anything to take back
     */
    public boolean undo() {
        if (!session.undo()) {
            return false;
        }
        page = Pages.clamp(page, filtered().size(), Layouts.LIST_ROW_SLOTS);
        drawRows();
        return true;
    }

    /**
     * What a save would change, by entry.
     *
     * <p>Entries rather than components: a list panel edits one component, so a
     * component diff could only ever say "one thing changed", and what a viewer
     * needs before confirming is how many rows they added, removed and edited.
     */
    public @NotNull PanelDiff diff() {
        return ListEntries.between(ListEntries.unwrap(session.originalValues()),
                entries(), descriptor);
    }

    /**
     * Commits a new list, remembering what it replaced.
     *
     * <p>One snapshot per operation, so undo takes back what the viewer thinks
     * they did rather than a fragment of it. The page is clamped afterwards,
     * because deleting the only row of the last page must not leave somebody
     * looking at a page that no longer exists.
     */
    private void commit(List<T> replacement) {
        session.editAll(ListEntries.wrap(replacement));
        page = Pages.clamp(page, filtered().size(), Layouts.LIST_ROW_SLOTS);
        drawRows();
    }

    private static <E> List<E> added(List<E> entries, E element) {
        List<E> next = new ArrayList<>(entries);
        next.add(element);
        return next;
    }

    /**
     * The list without one element, matched by reference first.
     *
     * <p>Never by index. Two elements that compare equal are still two rows, so
     * the reference is tried before equality and only one occurrence ever goes.
     */
    private static <E> List<E> removed(List<E> entries, E element) {
        List<E> next = new ArrayList<>(entries);
        for (int index = 0; index < next.size(); index++) {
            if (next.get(index) == element) {
                next.remove(index);
                return next;
            }
        }
        next.remove(element);
        return next;
    }

    /** The list with one element swapped for another, in the place it was. */
    private static <E> List<E> replaced(List<E> entries, E element, E replacement) {
        List<E> next = new ArrayList<>(entries);
        for (int index = 0; index < next.size(); index++) {
            if (next.get(index) == element || Objects.equals(next.get(index), element)) {
                next.set(index, replacement);
                return next;
            }
        }
        return next;
    }

    /** Runs an operation only when the viewer actually answered. */
    private static <V> void onAnswer(InputResult<V> result, Consumer<V> action) {
        if (result.completed()) {
            action.accept(result.value());
        }
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * Writes the list back, if anything changed.
     *
     * <p>Asked of the session rather than performed here, so that the panel's own
     * button and a plugin calling
     * {@link net.exylia.lib.panel.PanelSession#save()} take the same path and
     * meet the same guard. A guard on only one of the two doors is a guard the
     * other caller walks straight past.
     *
     * @return whether a write was started
     */
    public boolean save() {
        return session.save();
    }

    /**
     * Hands the list to the descriptor.
     *
     * <p>The one place this class writes anything, and it is reached only when
     * the diff was not empty — the session's own rule, which is what makes
     * opening a list to look at it free.
     */
    private void write(Map<String, Object> values) {
        List<T> entries = ListEntries.unwrap(values);

        Tasks.of(plugin).runAsync(() -> {
            descriptor.save(entries);
            if (onSaved != null) {
                // Back on the thread that owns the player: whoever asked to be
                // told is going to touch the game.
                Tasks.of(plugin).runAtEntity(viewer, () -> onSaved.accept(entries));
            }
        });
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /**
     * Test seam: an engine with no window.
     *
     * <p>Opening a real one needs {@code Bukkit.createInventory}, which answers
     * nothing without a running server, and drawing needs an {@code ItemStack},
     * whose class initialiser reaches the registry. What these tests are about is
     * which element a click resolves to — decided before anything becomes an item.
     */
    public static <T> @NotNull ListEngine<T> forTests(@NotNull Plugin plugin, @NotNull Player viewer,
                                                      @NotNull FieldDescriptor<T> descriptor,
                                                      @Nullable Consumer<List<T>> onSaved) {
        return of(plugin, viewer, descriptor, onSaved);
    }

    /**
     * Test seam: moves the backing list without redrawing.
     *
     * <p>The one shape that separates "resolved the right index" from "resolved
     * the right element". A correct page calculation still resolves the wrong row
     * when the list moves under a screen somebody is already looking at, and that
     * is not hypothetical — a plugin editing the same list from a command while a
     * panel is open does exactly this.
     *
     * @param change what to do to the list; must not redraw
     */
    public void reorderForTests(@NotNull UnaryOperator<List<T>> change) {
        session.editAll(ListEntries.wrap(change.apply(entries())));
    }
}
