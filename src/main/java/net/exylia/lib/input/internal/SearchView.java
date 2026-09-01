package net.exylia.lib.input.internal;

import net.exylia.lib.input.SearchInput;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The chest window that shows search results, and the filter behind it.
 *
 * <h2>Why results are never drawn in the bottom inventory</h2>
 * The obvious layout for a search is "type in the anvil, list the matches in the
 * player's own inventory grid". It is also destructive, and this class exists to
 * make that impossible.
 *
 * <p>Against paper-api 1.21.4, {@code InventoryView#setItem(int)} interprets its
 * argument as a <em>raw</em> slot: every index past the top inventory's size
 * addresses the player's real {@code PlayerInventory}. Writing a result icon
 * there does not paint a decoration, it <strong>overwrites the item the player
 * was carrying in that slot</strong>, and the overwritten stack is gone the
 * moment the write lands — there is nothing to restore it from. Snapshotting the
 * 36 slots first and putting them back on close does not fix it either, because
 * the window can end without a close event we get to act on (a crash, a kick
 * during shutdown, a world unload), and every one of those endings leaves the
 * player holding icons instead of their inventory.
 *
 * <p>The other tempting variant — leaving the real inventory alone and sending
 * fake {@code SET_SLOT} packets for the bottom 36 slots — is unsafe for a
 * different reason. Those slots stay genuinely clickable and genuinely
 * server-authoritative: the client believes the fake stack is real, sends a
 * click carrying it, and the server answers from the true contents. That is a
 * desync in the one place a desync costs items, and neither the container
 * state id nor a cancelled click closes the window between "the client thinks it
 * is holding a result icon" and "the server thinks it is holding a diamond".
 *
 * <p>So every pixel this module draws lives in a plugin-owned top inventory,
 * and every click anywhere in the view is cancelled. The player's real items are
 * never read, never written, and never depended upon.
 *
 * <h2>Cost</h2>
 * Filtering compares strings that {@link SearchInput#normalizedSearchStrings()}
 * lowercased once when the request opened; a keystroke never re-derives a label
 * nor calls a caller-supplied function per element. {@link #draw()} builds
 * {@link ItemStack}s for the visible page only, so a 5000-element collection
 * costs at most one page of item construction per redraw instead of 5000.
 *
 * @param <T> option type carried by the request
 * @since 1.31.0
 */
final class SearchView<T> implements InventoryHolder {

    /** Result area: the first five rows. The sixth row is the control bar. */
    static final int PAGE_CAPACITY = 45;
    static final int INVENTORY_SIZE = 54;

    static final int PREVIOUS_SLOT = 45;
    static final int SEARCH_SLOT = 47;
    static final int CLEAR_SLOT = 48;
    static final int INFO_SLOT = 49;
    static final int CANCEL_SLOT = 51;
    static final int NEXT_SLOT = 53;

    private final InputSession session;
    private final SearchInput<T> request;
    private final Map<T, String> normalized;
    private final int capacity;

    /** Whether results are fetched a page at a time instead of held in full. */
    private final boolean paged;

    private List<T> matches;
    private String query = "";
    private int page;
    private Inventory inventory;

    /** How many results the current query has, when a source reported it. */
    private int total;

    /** True between asking a source for a page and receiving it. */
    private boolean loading;

    /** True when the last fetch failed, so the window can say so. */
    private boolean unavailable;

    /**
     * Which fetch the drawn page belongs to.
     *
     * <p>A player typing a query and paging twice has three requests in flight
     * over one window. Without a generation the slowest answer wins and the
     * chest shows a page nobody asked for, so every answer carries the number
     * it was issued with and a stale one is dropped.
     */
    private int generation;

    private SearchView(InputSession session, SearchInput<T> request) {
        this.session = session;
        this.request = request;
        this.normalized = request.normalizedSearchStrings();
        this.capacity = Math.min(PAGE_CAPACITY, Math.max(1, request.pageSize()));
        this.paged = request.source() != null;
        this.matches = request.choices();
        this.total = matches.size();
    }

    /**
     * Creates the view for a request whose element type is only known as a
     * wildcard at the call site.
     *
     * <p>The capture happens here so the rest of the class can name {@code T} and
     * keep {@link SearchInput#keyOf(Object)} and {@link SearchInput#iconOf(Object)}
     * type-checked, instead of every call being an unchecked cast.
     *
     * @param session owning session
     * @param request public searchable request
     * @return a view bound to a freshly created inventory
     */
    static @NotNull SearchView<?> create(@NotNull InputSession session,
                                         @NotNull SearchInput<?> request) {
        return createTyped(session, request);
    }

    private static <T> SearchView<T> createTyped(InputSession session, SearchInput<T> request) {
        SearchView<T> view = new SearchView<>(session, request);
        view.inventory = Bukkit.createInventory(view, INVENTORY_SIZE, view.title());
        view.draw();
        return view;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    @NotNull InputSession session() {
        return session;
    }

    @NotNull SearchInput<T> request() {
        return request;
    }

    /** The query currently applied to the visible results; never {@code null}. */
    @NotNull String query() {
        return query;
    }

    int pages() {
        int size = paged ? total : matches.size();
        return Math.max(1, (size + capacity - 1) / capacity);
    }

    /** Whether results are fetched one page at a time. */
    boolean paged() {
        return paged;
    }

    /** How many results fit on one page. */
    int capacity() {
        return capacity;
    }

    /** How many results the visible page skips. */
    int offset() {
        return page * capacity;
    }

    /**
     * Marks a fetch as started and returns the number its answer must carry.
     *
     * @return the generation to hand back to {@link #accept(int)}
     */
    int begin() {
        this.loading = true;
        this.unavailable = false;
        return ++generation;
    }

    /** Whether an answer belongs to the fetch still being waited on. */
    boolean accept(int issued) {
        return issued == generation;
    }

    /** Installs a fetched page. */
    void apply(@NotNull List<T> items, int count) {
        this.matches = List.copyOf(items);
        this.total = Math.max(count, items.size());
        this.loading = false;
        this.unavailable = false;
    }

    /** Records that the source could not answer, leaving the page empty. */
    void unavailable() {
        this.matches = List.of();
        this.total = 0;
        this.loading = false;
        this.unavailable = true;
    }

    boolean hasPrevious() {
        return page > 0;
    }

    boolean hasNext() {
        return page + 1 < pages();
    }

    /**
     * Applies a query and rewinds to the first page.
     *
     * <p>Paging is reset because the page index refers to a result list that no
     * longer exists: keeping page 4 after a query that matches three elements
     * would show an empty window and no way back except cancelling.
     *
     * @param rawQuery text typed in the anvil; blank means "no filter"
     */
    void query(@Nullable String rawQuery) {
        this.query = normalize(rawQuery);
        this.page = 0;
        if (!paged) {
            this.matches = filter(request, normalized, this.query);
        }
    }

    /** Moves by one page, clamped, so a stale click cannot address a missing page. */
    void movePage(int delta) {
        this.page = Math.max(0, Math.min(pages() - 1, page + delta));
    }

    /**
     * Resolves a clicked top-inventory slot to the element drawn there.
     *
     * <p>The answer comes from this view's own paging state, never from the item
     * stack the client reported. A client that lies about what it clicked
     * therefore selects whatever the server actually drew, or nothing.
     *
     * @param slot raw slot inside the top inventory
     * @return the element occupying that slot, or {@code null} when empty
     */
    @Nullable T entryAt(int slot) {
        if (slot < 0 || slot >= capacity) {
            return null;
        }
        if (paged) {
            // The page in hand is the page on screen: it was fetched at this
            // offset, so the slot indexes it directly.
            return slot < matches.size() ? matches.get(slot) : null;
        }
        int index = page * capacity + slot;
        return index < matches.size() ? matches.get(index) : null;
    }

    /**
     * Counts matches for a candidate query without disturbing what is displayed.
     *
     * <p>Used for the live count shown while typing, which must not mutate the
     * chest behind the anvil: the player has not confirmed that query yet, and
     * closing the anvil with nothing typed must leave the previous results
     * exactly as they were.
     *
     * @param rawQuery text being typed
     * @return how many elements that query would match
     */
    int previewCount(@Nullable String rawQuery) {
        if (paged) {
            // Counting would be an HTTP request per keystroke. The count comes
            // back with the page, once, when the query is confirmed.
            return -1;
        }
        return filter(request, normalized, normalize(rawQuery)).size();
    }

    /**
     * Repaints the whole window from current state.
     *
     * <p>The inventory is cleared first so a shrinking result set cannot leave
     * icons from the previous query behind — a leftover icon is clickable and
     * would select an element the current query excluded.
     */
    void draw() {
        inventory.clear();

        int start = paged ? 0 : page * capacity;
        int end = Math.min(start + capacity, matches.size());
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, icon(matches.get(index)));
        }

        if (hasPrevious()) {
            inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, "{primary}&lPREVIOUS",
                    "{letters_black}▎ {letters}Page {info}" + page + "{letters_black}/{info}" + pages()));
        }
        inventory.setItem(SEARCH_SLOT, button(Material.NAME_TAG, "{primary}&lSEARCH",
                "{letters_black}▎ {letters}Type to filter these results.",
                "",
                "{warning}➥ Click to open the text box"));
        if (!query.isEmpty()) {
            inventory.setItem(CLEAR_SLOT, button(Material.STRUCTURE_VOID, "{secondary}&lCLEAR",
                    "{letters_black}▎ {letters}Show every option again.",
                    "",
                    "{warning}➥ Click to clear the search"));
        }
        inventory.setItem(INFO_SLOT, info());
        inventory.setItem(CANCEL_SLOT, button(Material.BARRIER, "{error}&lCANCEL",
                "{letters_black}▎ {letters}Close without choosing."));
        if (hasNext()) {
            inventory.setItem(NEXT_SLOT, button(Material.ARROW, "{primary}&lNEXT",
                    "{letters_black}▎ {letters}Page {info}" + (page + 2) + "{letters_black}/{info}" + pages()));
        }
    }

    /**
     * Window title: the prompt, and deliberately nothing that changes.
     *
     * <p>A title cannot be edited on an open view, so putting the page counter
     * or the match count here would force a close-and-reopen on every page turn
     * and every keystroke-applied query. That is a burst of window packets, a
     * visible flicker, and — worse — a close event for a window we still want,
     * which has to be masked with a programmatic-close marker each time. The
     * counter lives in the info button instead, where it redraws in place.
     */
    @NotNull Component title() {
        // Formatted, not literal: the prompt is the asking plugin's own text and
        // is written in the same notation as its messages. As a literal value,
        // "{warning}Search a material" arrived in the anvil with the braces.
        return Text.of("%prompt%").withFormatted("%prompt%", request.prompt()).build();
    }

    /**
     * Filters and ranks against the open-time snapshot.
     *
     * <p>A blank query returns the untouched collection rather than asking the
     * request to match {@code ""}. A caller-supplied {@code matcher} is free to
     * treat the empty string as "nothing matches", and an empty box must mean
     * "no filter applied", not "no results".
     *
     * <p>Ranking is three stable buckets — exact, prefix, contains — and never
     * an edit-distance score. Fuzzy ranking makes a long list feel random: with
     * 4000 entries a one-character typo promotes dozens of unrelated rows above
     * the exact name the player finished typing.
     */
    private static <T> List<T> filter(SearchInput<T> request, Map<T, String> normalized, String query) {
        if (query.isEmpty()) {
            return request.choices();
        }

        List<T> found;
        try {
            found = request.search(query);
        } catch (RuntimeException matcherFailure) {
            // A caller-supplied matcher threw. Showing everything is the safe
            // reading: a broken filter must not look like "no such option".
            return request.choices();
        }
        if (found.size() < 2) {
            return found;
        }

        List<T> exact = new ArrayList<>();
        List<T> prefix = new ArrayList<>();
        List<T> contains = new ArrayList<>();
        for (T value : found) {
            String text = normalized.get(value);
            if (text == null) {
                contains.add(value);
            } else if (text.equals(query)) {
                exact.add(value);
            } else if (text.startsWith(query)) {
                prefix.add(value);
            } else {
                contains.add(value);
            }
        }
        List<T> ranked = new ArrayList<>(found.size());
        ranked.addAll(exact);
        ranked.addAll(prefix);
        ranked.addAll(contains);
        return ranked;
    }

    /** Matches the normalization {@link SearchInput#search(String)} performs. */
    private static String normalize(@Nullable String rawQuery) {
        return rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
    }

    private ItemStack icon(T value) {
        ItemStack base;
        String label;
        try {
            base = request.itemOf(value);
            label = request.labelOf(value);
        } catch (RuntimeException brokenElement) {
            // One unreadable element must not blank the page. Commons swallowed
            // this silently; here the row still renders and stays selectable.
            base = null;
            label = String.valueOf(value);
        }
        if (base == null) {
            Material material;
            try {
                material = request.iconOf(value);
            } catch (RuntimeException brokenElement) {
                material = Material.PAPER;
            }
            base = new ItemStack(material);
        }
        return written(base, Text.of("{primary}&l%label%").with("%label%", label).build(),
                "{letters_black}▎ {letters}Click to choose this option.");
    }

    private ItemStack info() {
        String state = query.isEmpty() ? "{muted}none" : "{highlight}" + '"' + query + '"';
        if (loading) {
            return button(Material.CLOCK, "{primary}&lSEARCHING",
                    "{letters_black}▎ {letters}Query {letters_black}» " + state,
                    "{letters_black}▎ {letters}Fetching results…");
        }
        if (unavailable) {
            return button(Material.BARRIER, "{error}&lUNAVAILABLE",
                    "{letters_black}▎ {letters}The catalogue could not be reached.",
                    "",
                    "{warning}➥ Search again to retry");
        }
        return button(Material.PAPER, "{primary}&lSEARCH RESULTS",
                "{letters_black}▎ {letters}Query {letters_black}» " + state,
                "{letters_black}▎ {letters}Matches {letters_black}» {info}"
                        + (paged ? total : matches.size()),
                "{letters_black}▎ {letters}Page {letters_black}» {info}" + (page + 1)
                        + "{letters_black}/{info}" + pages());
    }

    private static ItemStack button(Material material, String name, String... lore) {
        return build(material, Text.of(name).build(), lore);
    }

    /**
     * Writes a name and lore onto a stack somebody else built.
     *
     * <p>The label goes in through {@link Text#with(String, Object)}, so an
     * element labelled {@code &cFREE} prints those characters instead of turning
     * red: caller data is data, never formatting.
     */
    private static ItemStack written(ItemStack item, Component name, String... lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>(lore.length);
            for (String line : lore) {
                lines.add(Text.of(line).build()
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Italics are switched off explicitly, because vanilla italicises any item
     * name or lore a plugin sets and the palette's intent would otherwise be
     * rendered in a style nobody asked for.
     */
    private static ItemStack build(Material material, Component name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>(lore.length);
                for (String line : lore) {
                    lines.add(Text.of(line).build()
                            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                }
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
