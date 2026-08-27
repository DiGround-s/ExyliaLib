package net.exylia.lib.util.editor.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.util.editor.Clipboard;
import net.exylia.lib.util.editor.EditorButton;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One open list editor, and the window it is drawn in.
 *
 * <h2>State lives on the window</h2>
 * Never in a {@code Map<UUID, Session>}. A player with a chest open on top of an
 * editor is looking at the chest; a map would still say "this player has an
 * editor", so a click in the chest would be handed to it. The holder knows what
 * a window is; the player does not.
 *
 * <h2>Nothing is written until save</h2>
 * The list here is a working copy. Adding, deleting and pasting change it and
 * nothing else; cancel throws it away, and an editor opened to look at something
 * writes nothing at all.
 *
 * @param <T> what is being edited
 */
public final class EditorHolder<T> implements InventoryHolder {

    /** Rows on a page when the editor has no buttons of its own. */
    static final int FULL_PAGE = 45;

    /**
     * Rows on a page when it does.
     *
     * <p>The bottom row of entries becomes the band the buttons sit in. Nine
     * fewer rows per page is the price of them, and it is only paid by a screen
     * that has any &mdash; ExyliaCommons charged every editor that row whether
     * it used it or not.
     */
    static final int SHORT_PAGE = 36;

    /** Where the button band starts, on a screen that has one. */
    static final int BAND = SHORT_PAGE;

    static final int SLOT_ADD = 45;
    static final int SLOT_PASTE = 46;
    static final int SLOT_PREVIOUS = 47;
    static final int SLOT_COPY_ALL = 48;
    static final int SLOT_INFO = 49;
    static final int SLOT_NEXT = 51;
    static final int SLOT_SAVE = 52;
    static final int SLOT_CANCEL = 53;

    private final Plugin plugin;
    private final EditorDescriptor<T> descriptor;
    private final Class<T> type;
    private final String title;
    private final List<T> entries;
    private final List<EditorButton<T>> buttons;
    private final Consumer<List<T>> onSave;
    private final Runnable onCancel;
    private final UUID viewerId;

    private Inventory inventory;
    private int page;
    private boolean reopening;
    private boolean finished;

    EditorHolder(Plugin plugin, EditorDescriptor<T> descriptor, Class<T> type, String title,
                 List<T> entries, List<EditorButton<T>> buttons, Consumer<List<T>> onSave,
                 Runnable onCancel, Player viewer) {
        this.plugin = plugin;
        this.descriptor = descriptor;
        this.type = type;
        this.title = title;
        this.entries = new ArrayList<>(entries);
        this.buttons = List.copyOf(buttons);
        this.onSave = onSave;
        this.onCancel = onCancel;
        this.viewerId = viewer.getUniqueId();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    Plugin plugin() {
        return plugin;
    }

    EditorDescriptor<T> descriptor() {
        return descriptor;
    }

    Class<T> type() {
        return type;
    }

    String title() {
        return title;
    }

    List<T> entries() {
        return entries;
    }

    UUID viewerId() {
        return viewerId;
    }

    @Nullable Player viewer() {
        return Bukkit.getPlayer(viewerId);
    }

    int page() {
        return page;
    }

    void page(int page) {
        this.page = Math.max(0, Math.min(page, pages() - 1));
    }

    int pages() {
        int size = pageSize();
        return Math.max(1, (entries.size() + size - 1) / size);
    }

    /** How many rows fit on a page, which depends on whether there is a band. */
    int pageSize() {
        return buttons.isEmpty() ? FULL_PAGE : SHORT_PAGE;
    }

    List<EditorButton<T>> buttons() {
        return buttons;
    }

    /** The button a band slot holds, or {@code null} when the slot is not one. */
    @Nullable EditorButton<T> buttonAt(int slot) {
        if (buttons.isEmpty() || slot < BAND || slot >= BAND + buttons.size()) {
            return null;
        }
        return buttons.get(slot - BAND);
    }

    /** The open editor, as a button sees it. */
    @NotNull EditorView<T> view(Player viewer) {
        return new EditorView<>() {

            @Override
            public @NotNull Player viewer() {
                return viewer;
            }

            @Override
            public @NotNull List<T> entries() {
                return List.copyOf(entries);
            }

            @Override
            public void replaceAll(@NotNull List<T> replacement) {
                entries.clear();
                entries.addAll(replacement);
                // Clamped, so a button that shortens the list does not leave the
                // viewer looking at a page that is no longer there.
                page(page());
            }

            @Override
            public void ask(@NotNull Supplier<CompletionStage<?>> question) {
                EditorHolder.this.ask(viewer, question);
            }
        };
    }

    /**
     * Takes the window down for a button's question and puts it back after.
     *
     * <p>The same door {@code EditorRuntime.edit} uses, offered to a plugin's
     * own button: without it a button that opens a dialog closes the editor
     * under itself, because a close nobody claimed is the viewer walking away.
     */
    private void ask(Player viewer, Supplier<CompletionStage<?>> question) {
        Objects.requireNonNull(question, "question");
        EditorRuntime.closeForQuestion(this, viewer);
        CompletionStage<?> asked;
        try {
            asked = question.get();
        } catch (RuntimeException broken) {
            Debug.of(plugin).error("A button in a list editor could not ask its question.", broken);
            EditorRuntime.reopen(this);
            return;
        }
        if (asked == null) {
            EditorRuntime.reopen(this);
            return;
        }
        asked.whenComplete((answer, failure) -> {
            if (failure != null) {
                Debug.of(plugin)
                        .error("A button in a list editor asked a question that failed.", failure);
            }
            EditorRuntime.reopen(this);
        });
    }

    /**
     * Whether the window is about to be reopened by the editor itself.
     *
     * <p>Editing a row closes the window to ask a question and opens it again
     * with the answer. Without this flag the close in the middle would read as
     * the viewer walking away, and the edit they are halfway through would be
     * thrown out from under them.
     */
    boolean isReopening() {
        return reopening;
    }

    void reopening(boolean reopening) {
        this.reopening = reopening;
    }

    /**
     * Claims the one ending this editor gets.
     *
     * <p>Save, cancel, closing the window, leaving the server and the plugin
     * being disabled are five ways to the same place, and the first one there
     * wins. A cleanup branch per ending is how the leak gets in.
     *
     * @return whether this call is the ending
     */
    boolean finish() {
        if (finished) {
            return false;
        }
        finished = true;
        return true;
    }

    boolean isFinished() {
        return finished;
    }

    void save() {
        if (!finish()) {
            return;
        }
        onSave.accept(List.copyOf(entries));
    }

    void cancel() {
        if (!finish()) {
            return;
        }
        onCancel.run();
    }

    /** The element a slot on the current page is showing, if any. */
    @Nullable T at(int slot) {
        if (slot < 0 || slot >= pageSize()) {
            return null;
        }
        int index = page * pageSize() + slot;
        return index < entries.size() ? entries.get(index) : null;
    }

    /**
     * Replaces an element by identity, not by index.
     *
     * <p>The bug ExyliaCommons' editors had: a row was addressed by its slot, so
     * an edit that landed after the list had changed underneath — a search, a
     * paste, a second screen — edited a different row. Rows carry their element
     * here, and the element is what is looked for.
     *
     * @param original what was being edited
     * @param edited   what it became
     */
    void replace(T original, T edited) {
        int index = indexOf(original);
        if (index < 0) {
            entries.add(edited);
            return;
        }
        entries.set(index, edited);
    }

    void remove(T entry) {
        int index = indexOf(entry);
        if (index >= 0) {
            entries.remove(index);
            page(page);
        }
    }

    void add(T entry) {
        entries.add(entry);
        page(pages() - 1);
    }

    private int indexOf(T entry) {
        for (int index = 0; index < entries.size(); index++) {
            // Identity first: two rows can be equal and be different rows.
            if (entries.get(index) == entry) {
                return index;
            }
        }
        return entries.indexOf(entry);
    }

    // ------------------------------------------------------------------ drawing

    /** Redraws the whole page. */
    void draw() {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        drawRows();
        drawControls();
    }

    private void drawRows() {
        int size = pageSize();
        int start = page * size;
        int end = Math.min(start + size, entries.size());
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, rowItem(entries.get(index)));
        }
        for (int index = 0; index < buttons.size(); index++) {
            EditorButton<T> button = buttons.get(index);
            inventory.setItem(BAND + index,
                    Icons.row(button.icon(), button.name(), button.lore(), button.isGlowing()));
        }
    }

    private ItemStack rowItem(T entry) {
        String icon;
        String label;
        List<String> details;
        try {
            icon = descriptor.icon(entry);
            label = descriptor.label(entry);
            details = descriptor.lore(entry, entries);
        } catch (RuntimeException brokenElement) {
            // One unreadable element must not blank the page: the row still
            // draws and stays deletable, which is the only thing an admin can
            // usefully do with it.
            return Icons.row("BARRIER", "{error}&lUNREADABLE ENTRY",
                    List.of("{letters_black}▎ {letters}This row could not be described.",
                            "", "{error}● {letters}Right Click {letters_black}» Delete"));
        }

        List<String> lore = new ArrayList<>(details.size() + 6);
        lore.addAll(details);
        if (!descriptor.isComplete(entry)) {
            if (!lore.isEmpty()) {
                lore.add("");
            }
            lore.add("{warning}⚠ {letters}Not finished yet");
        }
        lore.add("");
        lore.add("{success}● {letters}Left Click {letters_black}» Edit");
        lore.add("{error}● {letters}Right Click {letters_black}» Delete");
        lore.add("{highlight}● {letters}Shift + Left {letters_black}» Copy");
        return Icons.row(icon, label, lore);
    }

    private void drawControls() {
        inventory.setItem(SLOT_ADD, Icons.button(Material.EMERALD, "{success}&lADD",
                List.of("{letters_black}▎ {letters}Create a new entry and",
                        "{letters_black}▎ {letters}configure it right away.",
                        "",
                        "{warning}➥ Click to add")));

        Player viewer = viewer();
        int pending = viewer == null ? 0 : Clipboard.size(viewer, descriptor.typeKey());
        if (pending > 0) {
            inventory.setItem(SLOT_PASTE, Icons.glowing(Material.WRITABLE_BOOK,
                    "{highlight}&lPASTE",
                    List.of("{letters_black}▎ {letters}Add {info}" + pending + " {letters}copied "
                                    + (pending == 1 ? "entry" : "entries") + " to",
                            "{letters_black}▎ {letters}this list.",
                            "",
                            "{warning}➥ Click to paste")));
        }

        if (!entries.isEmpty()) {
            inventory.setItem(SLOT_COPY_ALL, Icons.button(Material.BOOKSHELF, "{info}&lCOPY ALL",
                    List.of("{letters_black}▎ {letters}Copy every entry here {letters_black}(" + entries.size() + ")",
                            "{letters_black}▎ {letters}ready to paste somewhere else.",
                            "",
                            "{warning}➥ Click to copy")));
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREVIOUS, Icons.button(Material.ARROW,
                    "{secondary}&l« PREVIOUS", List.of()));
        }
        if (page + 1 < pages()) {
            inventory.setItem(SLOT_NEXT, Icons.button(Material.ARROW,
                    "{secondary}&lNEXT »", List.of()));
        }

        inventory.setItem(SLOT_INFO, Icons.button(Material.PAPER, "{primary}&lLIST",
                List.of("{letters_black}▎ {letters}Entries {letters_black}» {info}" + entries.size(),
                        "{letters_black}▎ {letters}Page {letters_black}» {info}" + (page + 1)
                                + "{letters_black}/{info}" + pages())));

        inventory.setItem(SLOT_SAVE, Icons.glowing(Material.LIME_DYE, "{success}&lSAVE",
                List.of("{letters_black}▎ {letters}Keep every change made here.",
                        "",
                        "{warning}➥ Click to save")));

        inventory.setItem(SLOT_CANCEL, Icons.button(Material.RED_DYE, "{error}&lCANCEL",
                List.of("{letters_black}▎ {letters}Discard every change and",
                        "{letters_black}▎ {letters}close this screen.",
                        "",
                        "{warning}➥ Click to discard")));
    }
}
